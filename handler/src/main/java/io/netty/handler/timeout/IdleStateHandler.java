/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers an {@link IdleStateEvent} when a {@link Channel} has not performed
 * read, write, or both operation for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr>
 * <th>Property</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code readerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
 *     will be triggered when no read was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code writerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
 *     will be triggered when no write was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code allIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *     will be triggered when neither read nor write was performed for the
 *     specified period of time.  Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 *
 * <pre>
 * // An example that sends a ping message when there is no outbound traffic
 * // for 30 seconds.  The connection is closed when there is no inbound traffic
 * // for 60 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("idleStateHandler", new {@link IdleStateHandler}(60, 30, 0));
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link IdleStateEvent} triggered by {@link IdleStateHandler}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void userEventTriggered({@link ChannelHandlerContext} ctx, {@link Object} evt) throws {@link Exception} {
 *         if (evt instanceof {@link IdleStateEvent}) {
 *             {@link IdleStateEvent} e = ({@link IdleStateEvent}) evt;
 *             if (e.state() == {@link IdleState}.READER_IDLE) {
 *                 ctx.close();
 *             } else if (e.state() == {@link IdleState}.WRITER_IDLE) {
 *                 ctx.writeAndFlush(new PingMessage());
 *             }
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 *
 * @see ReadTimeoutHandler
 * @see WriteTimeoutHandler
 */
public class IdleStateHandler extends ChannelDuplexHandler {

    /**
     * ��С�ĳ�ʱʱ�䣬��λ������
     */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * д�����������
     */
    // Not create a new ChannelFutureListener per write operation to reduce GC pressure.
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // ��¼���дʱ��
            lastWriteTime = ticksInNanos();
            // ���� firstWriterIdleEvent �� firstAllIdleEvent Ϊ true
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }

    };

    /**
     * �Ƿ�۲� {@link ChannelOutboundBuffer} д�����
     */
    private final boolean observeOutput;
    /**
     * ���õĶ�����ʱ�䣬��λ������
     */
    private final long readerIdleTimeNanos;
    /**
     * ���õ�д����ʱ�䣬��λ������
     */
    private final long writerIdleTimeNanos;
    /**
     * ���õ�All( ����д��һ )����λ������
     */
    private final long allIdleTimeNanos;

    /**
     * �����еĶ�ʱ�������
     */
    private ScheduledFuture<?> readerIdleTimeout;
    /**
     * ����ʱ��
     */
    private long lastReadTime;
    /**
     * �Ƿ��״ζ�����
     */
    private boolean firstReaderIdleEvent = true;

    /**
     * д���еĶ�ʱ�������
     */
    private ScheduledFuture<?> writerIdleTimeout;
    /**
     * ���дʱ��
     */
    private long lastWriteTime;
    /**
     * �Ƿ��״�д����
     */
    private boolean firstWriterIdleEvent = true;

    /**
     * All ����ʱ�䣬��λ������
     */
    private ScheduledFuture<?> allIdleTimeout;
    /**
     * �Ƿ��״� All ����
     */
    private boolean firstAllIdleEvent = true;

    /**
     * ״̬
     *
     * 0 - none ��δ��ʼ��
     * 1 - initialized ���Ѿ���ʼ��
     * 2 - destroyed ���Ѿ�����
     */
    private byte state; // 0 - none, 1 - initialized, 2 - destroyed
    /**
     * �Ƿ����ڶ�ȡ
     */
    private boolean reading;

    /**
     * ����⵽ {@link ChannelOutboundBuffer} �����仯��ʱ��
     */
    private long lastChangeCheckTimeStamp;
    /**
     * ��һ��׼�� flash ���Զ˵���Ϣ( {@link ChannelOutboundBuffer#current()} )�� HashCode
     */
    private int lastMessageHashCode;
    /**
     * �ܹ��ȴ� flush ���Զ˵��ڴ��С( {@link ChannelOutboundBuffer#totalPendingWriteBytes()} )
     */
    private long lastPendingWriteBytes;
    private long lastFlushProgress;

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param readerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
             TimeUnit.SECONDS);
    }

    /**
     * @see #IdleStateHandler(boolean, long, long, long, TimeUnit)
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param observeOutput
     *        whether or not the consumption of {@code bytes} should be taken into
     *        consideration when assessing write idleness. The default is {@code false}.
     * @param readerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     * @param unit
     *        the {@link TimeUnit} of {@code readerIdleTime},
     *        {@code writeIdleTime}, and {@code allIdleTime}
     */
    public IdleStateHandler(boolean observeOutput,
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        ObjectUtil.checkNotNull(unit, "unit");

        this.observeOutput = observeOutput;

        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS); // ��֤���ڵ��� MIN_TIMEOUT_NANOS
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS); // ��֤���ڵ��� MIN_TIMEOUT_NANOS
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS); // ��֤���ڵ��� MIN_TIMEOUT_NANOS
        }
    }

    /**
     * Return the readerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * Return the writerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * Return the allIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // channelActive() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelActive() will be invoked
            // and initialization will occur there.
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // ����
        destroy();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // ��ʼ��
        // Initialize early if channel is active already.
        if (ctx.channel().isActive()) {
            initialize(ctx);
        }
        // �������� Channel Registered �¼�����һ���ڵ�
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // ��ʼ��
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        initialize(ctx);
        // �������� Channel Registered �¼�����һ���ڵ�
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // ����
        destroy();
        // �������� Channel Incative �¼�����һ���ڵ�
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // ������ read �� all �Ŀ��м��
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            // ������ڶ�ȡ
            reading = true;
            // ���� firstWriterIdleEvent �� firstAllIdleEvent Ϊ true
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        // �������� Channel Read �¼�����һ���ڵ�
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // ������ read �� all �Ŀ��м��
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            // ��¼����ʱ��
            lastReadTime = ticksInNanos();
            // ��ǲ��ڶ�ȡ
            reading = false;
        }
        // �������� Channel ReadComplete �¼�����һ���ڵ�
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // ������ write �� all �Ŀ��м��
        // Allow writing with void promise if handler is only configured for read timeout events.
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            // д�룬�����д�������
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            // д�룬����Ӽ�����
            ctx.write(msg, promise);
        }
    }

    private void initialize(ChannelHandlerContext ctx) {
        // У��״̬��������Ϊ `#destroy()` ������ `#initialize(ChannelHandlerContext ctx)` ������ִ��֮ǰ
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        switch (state) {
        case 1:
        case 2:
            return;
        }

        // ���Ϊ�ѳ�ʼ��
        state = 1;
        // ��ʼ�� ChannelOutboundBuffer �������
        initOutputChanged(ctx);

        // ��ʼ��Ӧ�Ķ�ʱ����
        lastReadTime = lastWriteTime = ticksInNanos();
        if (readerIdleTimeNanos > 0) {
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                    readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (writerIdleTimeNanos > 0) {
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                    writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (allIdleTimeNanos > 0) {
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                    allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * This method is visible for testing!
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        return ctx.executor().schedule(task, delay, unit);
    }

    private void destroy() {
        // ���Ϊ����
        state = 2;

        // ������Ӧ�Ķ�ʱ����
        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * @see #hasOutputChanged(ChannelHandlerContext, boolean)
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                // ��¼��һ��׼�� flash ���Զ˵���Ϣ�� HashCode
                lastMessageHashCode = System.identityHashCode(buf.current());
                // ��¼�ܹ��ȴ� flush ���Զ˵��ڴ��С
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
                lastFlushProgress = buf.currentProgress();
            }
        }
    }

    /**
     * Returns {@code true} if and only if the {@link IdleStateHandler} was constructed
     * with {@link #observeOutput} enabled and there has been an observed change in the
     * {@link ChannelOutboundBuffer} between two consecutive calls of this method.
     *
     * https://github.com/netty/netty/issues/6150
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        // �����۲� ChannelOutboundBuffer ����
        if (observeOutput) {

            // We can take this shortcut if the ChannelPromises that got passed into write()
            // appear to complete. It indicates "change" on message level and we simply assume
            // that there's change happening on byte level. If the user doesn't observe channel
            // writability events then they'll eventually OOME and there's clearly a different
            // problem and idleness is least of their concerns.
            // ��� lastChangeCheckTimeStamp �� lastWriteTime ��һ����˵��д�������й��ˣ�����´�ֵ
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // But this applies only if it's the non-first call.
                if (!first) { // ���״�
                    return true;
                }
            }

            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                // ����µ� messageHashCode �� pendingWriteBytes
                int messageHashCode = System.identityHashCode(buf.current());
                long pendingWriteBytes = buf.totalPendingWriteBytes();

                // �����˱仯
                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    // �޸����һ�ε� lastMessageHashCode �� lastPendingWriteBytes
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;

                    if (!first) {
                        return true;
                    }
                }

                long flushProgress = buf.currentProgress();
                if (flushProgress != lastFlushProgress) {
                    lastFlushProgress = flushProgress;

                    if (!first) { // ���״�
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * �������������
     */
    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            // ����δ�򿪵� Channel
            if (!ctx.channel().isOpen()) {
                return;
            }

            // ִ������
            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);

    }

    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            // ������һ�μ��Ķ�ʱ������ӳ�
            long nextDelay = readerIdleTimeNanos;
            if (!reading) {
                nextDelay -= ticksInNanos() - lastReadTime;
            }

            // ���С�ڵ��� 0 ��˵����⵽������
            if (nextDelay <= 0) {
                // �ӳ�ʱ��Ϊ readerIdleTimeNanos �����ٴμ��
                // Reader is idle - set a new timeout and notify the callback.
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                // ��õ�ǰ�Ƿ��״μ�⵽������
                boolean first = firstReaderIdleEvent;
                // ��� firstReaderIdleEvent Ϊ false ��Ҳ��˵���´μ�⵽���У��ͷ��״��ˡ�
                firstReaderIdleEvent = false;

                try {
                    // �����������¼�
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    // ֪ͨͨ�������¼�
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    // ���� Exception Caught ����һ���ڵ�
                    ctx.fireExceptionCaught(t);
                }
            // ������� 0 ��˵��δ��⵽������
            } else {
                // �ӳ�ʱ��Ϊ nextDelay �����������һ�ζ���ʱ����Ϊ��ʼ����
                // Read occurred before the timeout - set a new timeout with shorter delay.
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            // ������һ�μ��Ķ�ʱ������ӳ�
            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);

            // ���С�ڵ��� 0 ��˵����⵽д����
            if (nextDelay <= 0) {
                // �ӳ�ʱ��Ϊ writerIdleTimeout �����ٴμ��
                // Writer is idle - set a new timeout and notify the callback.
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                // ��õ�ǰ�Ƿ��״μ�⵽д����
                boolean first = firstWriterIdleEvent;
                // ��� firstWriterIdleEvent Ϊ false ��Ҳ��˵���´μ�⵽���У��ͷ��״��ˡ�
                firstWriterIdleEvent = false;

                try {
                    // �ж� ChannelOutboundBuffer �Ƿ����仯
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    // ����д�����¼�
                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    // ֪ͨͨ�������¼�
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    // ���� Exception Caught ����һ���ڵ�
                    ctx.fireExceptionCaught(t);
                }
            // ������� 0 ��˵��δ��⵽������
            } else {
                // Write occurred before the timeout - set a new timeout with shorter delay.
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            // ������һ�μ��Ķ�ʱ������ӳ�
            long nextDelay = allIdleTimeNanos;
            if (!reading) {
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime); // ȡ��ֵ
            }

            // ���С�ڵ��� 0 ��˵����⵽ all ����
            if (nextDelay <= 0) {
                // �ӳ�ʱ��Ϊ allIdleTimeNanos �����ٴμ��
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                // ��õ�ǰ�Ƿ��״μ�⵽ all ����
                boolean first = firstAllIdleEvent;
                // ��� firstAllIdleEvent Ϊ false ��Ҳ��˵���´μ�⵽���У��ͷ��״��ˡ�
                firstAllIdleEvent = false;

                try {
                    // �ж� ChannelOutboundBuffer �Ƿ����仯
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    // ���� all �����¼�
                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    // ֪ͨͨ�������¼�
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            // ������� 0 ��˵��δ��⵽ all ����
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
