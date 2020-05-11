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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raises a {@link WriteTimeoutException} when a write operation cannot finish in a certain period of time.
 *
 * <pre>
 * // The connection is closed when a write operation cannot finish in 30 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("writeTimeoutHandler", new {@link WriteTimeoutHandler}(30);
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link WriteTimeoutException}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void exceptionCaught({@link ChannelHandlerContext} ctx, {@link Throwable} cause)
 *             throws {@link Exception} {
 *         if (cause instanceof {@link WriteTimeoutException}) {
 *             // do something
 *         } else {
 *             super.exceptionCaught(ctx, cause);
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * @see ReadTimeoutHandler
 * @see IdleStateHandler
 */
public class WriteTimeoutHandler extends ChannelOutboundHandlerAdapter {

    /**
     * ��С�ĳ�ʱʱ�䣬��λ������
     */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * ��ʱʱ�䣬��λ������
     */
    private final long timeoutNanos;

    /**
     * WriteTimeoutTask ˫������
     *
     * lastTask Ϊ�����β�ڵ�
     *
     * A doubly-linked list to track all WriteTimeoutTasks
     */
    private WriteTimeoutTask lastTask;

    /**
     * Channel �Ƿ�ر�
     */
    private boolean closed;

    /**
     * Creates a new instance.
     *
     * @param timeoutSeconds
     *        write timeout in seconds
     */
    public WriteTimeoutHandler(int timeoutSeconds) {
        this(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Creates a new instance.
     *
     * @param timeout
     *        write timeout
     * @param unit
     *        the {@link TimeUnit} of {@code timeout}
     */
    public WriteTimeoutHandler(long timeout, TimeUnit unit) {
        ObjectUtil.checkNotNull(unit, "unit");

        if (timeout <= 0) {
            timeoutNanos = 0;
        } else {
            timeoutNanos = Math.max(unit.toNanos(timeout), MIN_TIMEOUT_NANOS); // ��֤���ڵ��� MIN_TIMEOUT_NANOS
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (timeoutNanos > 0) {
            // ��� promise �� VoidPromise �����װ�ɷ� VoidPromise ��Ϊ�˺����Ļص���
            promise = promise.unvoid();
            // ������ʱ����
            scheduleTimeout(ctx, promise);
        }
        // д��
        ctx.write(msg, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        WriteTimeoutTask task = lastTask;
        // �ÿ� lastTask
        lastTask = null;
        // ѭ���Ƴ���֪��Ϊ��
        while (task != null) {
            // ȡ����ǰ����Ķ�ʱ����
            task.scheduledFuture.cancel(false);
            // ��¼ǰһ������
            WriteTimeoutTask prev = task.prev;
            // �ÿյ�ǰ�����ǰ��ڵ�
            task.prev = null;
            task.next = null;
            // ����ǰһ������
            task = prev;
        }
    }

    private void scheduleTimeout(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        // Schedule a timeout.
        // ���� WriteTimeoutTask ����
        final WriteTimeoutTask task = new WriteTimeoutTask(ctx, promise);
        // ��ʱ����
        task.scheduledFuture = ctx.executor().schedule(task, timeoutNanos, TimeUnit.NANOSECONDS);

        if (!task.scheduledFuture.isDone()) {
            // ��ӵ�����
            addWriteTimeoutTask(task);

            // Cancel the scheduled timeout if the flush promise is complete.
            // �� task ��Ϊ����������ӵ� promise �С���д����ɺ󣬿����Ƴ��ö�ʱ����
            promise.addListener(task);
        }
    }

    private void addWriteTimeoutTask(WriteTimeoutTask task) {
        // ��ӵ������β�ڵ�
        if (lastTask != null) {
            lastTask.next = task;
            task.prev = lastTask;
        }
        // �޸� lastTask Ϊ��ǰ����
        lastTask = task;
    }

    private void removeWriteTimeoutTask(WriteTimeoutTask task) {
        // ��˫�������У��Ƴ��Լ�
        if (task == lastTask) { // β�ڵ�
            // task is the tail of list
            assert task.next == null;
            lastTask = lastTask.prev;
            if (lastTask != null) {
                lastTask.next = null;
            }
        } else if (task.prev == null && task.next == null) { // �Ѿ����Ƴ�
            // Since task is not lastTask, then it has been removed or not been added.
            return;
        } else if (task.prev == null) { // ͷ�ڵ�
            // task is the head of list and the list has at least 2 nodes
            task.next.prev = null;
        } else { // �м�Ľڵ�
            task.prev.next = task.next;
            task.next.prev = task.prev;
        }
        // ���� task ǰ��ڵ�Ϊ��
        task.prev = null;
        task.next = null;
    }

    /**
     * Is called when a write timeout was detected
     */
    protected void writeTimedOut(ChannelHandlerContext ctx) throws Exception {
        if (!closed) {
            // ���� Exception Caught �¼��� pipeline �У��쳣Ϊ WriteTimeoutException
            ctx.fireExceptionCaught(WriteTimeoutException.INSTANCE);
            // �ر� Channel ͨ��
            ctx.close();
            // ��� Channel Ϊ�ѹر�
            closed = true;
        }
    }

    private final class WriteTimeoutTask implements Runnable, ChannelFutureListener {

        private final ChannelHandlerContext ctx;
        /**
         * д������� Promise ����
         */
        private final ChannelPromise promise;

        // WriteTimeoutTask is also a node of a doubly-linked list
        /**
         * ǰһ�� task
         */
        WriteTimeoutTask prev;
        /**
         * ��һ�� task
         */
        WriteTimeoutTask next;
        /**
         * ��ʱ����
         */
        ScheduledFuture<?> scheduledFuture;

        WriteTimeoutTask(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.ctx = ctx;
            this.promise = promise;
        }

        @Override
        public void run() {
            // Was not written yet so issue a write timeout
            // The promise itself will be failed with a ClosedChannelException once the close() was issued
            // See https://github.com/netty/netty/issues/2159
            if (!promise.isDone()) { // δ��ɣ�˵��д�볬ʱ
                try {
                    // д�볬ʱ���ر� Channel ͨ��
                    writeTimedOut(ctx);
                } catch (Throwable t) {
                    // ���� Exception Caught �¼��� pipeline ��
                    ctx.fireExceptionCaught(t);
                }
            }
            // �Ƴ�������
            removeWriteTimeoutTask(this);
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // scheduledFuture has already be set when reaching here
            // ȡ����ʱ����
            scheduledFuture.cancel(false);
            // �Ƴ�������
            removeWriteTimeoutTask(this);
        }
    }
}
