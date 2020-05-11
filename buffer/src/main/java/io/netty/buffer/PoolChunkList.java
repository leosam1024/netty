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

package io.netty.buffer;

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    /**
     * 所属 PoolArena 对象
     */
    private final PoolArena<T> arena;
    /**
     * 下一个 PoolChunkList 对象
     */
    private final PoolChunkList<T> nextList;
    /**
     * Chunk 最小内存使用率
     */
    private final int minUsage;
    /**
     * Chunk 最大内存使用率
     */
    private final int maxUsage;
    /**
     * 每个 Chunk 最大可分配的容量
     *
     * @see #calculateMaxCapacity(int, int) 方法
     */
    private final int maxCapacity;
    /**
     * PoolChunk 头节点
     */
    private PoolChunk<T> head;
    private final int freeMinThreshold;
    private final int freeMaxThreshold;

    /**
     * 前一个 PoolChunkList 对象
     */
    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        // 计算 maxUsage 属性
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);

        // the thresholds are aligned with PoolChunk.usage() logic:
        // 1) basic logic: usage() = 100 - freeBytes * 100L / chunkSize
        //    so, for example: (usage() >= maxUsage) condition can be transformed in the following way:
        //      100 - freeBytes * 100L / chunkSize >= maxUsage
        //      freeBytes <= chunkSize * (100 - maxUsage) / 100
        //      let freeMinThreshold = chunkSize * (100 - maxUsage) / 100, then freeBytes <= freeMinThreshold
        //
        //  2) usage() returns an int value and has a floor rounding during a calculation,
        //     to be aligned absolute thresholds should be shifted for "the rounding step":
        //       freeBytes * 100 / chunkSize < 1
        //       the condition can be converted to: freeBytes < 1 * chunkSize / 100
        //     this is why we have + 0.99999999 shifts. A example why just +1 shift cannot be used:
        //       freeBytes = 16777216 == freeMaxThreshold: 16777216, usage = 0 < minUsage: 1, chunkSize: 16777216
        //     At the same time we want to have zero thresholds in case of (maxUsage == 100) and (minUsage == 100).
        //
        freeMinThreshold = (maxUsage == 100) ? 0 : (int) (chunkSize * (100.0 - maxUsage + 0.99999999) / 100L);
        freeMaxThreshold = (minUsage == 100) ? 0 : (int) (chunkSize * (100.0 - minUsage + 0.99999999) / 100L);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        // 计算 minUsage 值
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity, PoolThreadCache threadCache) {
        // 双向链表中无 Chunk
        // 申请分配的内存超过 ChunkList 的每个 Chunk 最大可分配的容量
        if (normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        // 遍历双向链表。注意，遍历的是 ChunkList 的内部双向链表。
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            if (cur.allocate(buf, reqCapacity, normCapacity, threadCache)) {
                if (cur.freeBytes <= freeMinThreshold) {
                    // 从当前 ChunkList 节点移除
                    remove(cur);
                    // 添加到下一个 ChunkList 节点
                    nextList.add(cur);
                }
                return true; // 分配成功
            }
        }
        return false;
    }

    boolean free(PoolChunk<T> chunk, long handle, ByteBuffer nioBuffer) {
        chunk.free(handle, nioBuffer);
        if (chunk.freeBytes > freeMaxThreshold) {
            // 从当前 ChunkList 节点移除
            remove(chunk);
            // 添加到上一个 ChunkList 节点
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        // 释放成功
        return true;
    }

    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.freeBytes > freeMaxThreshold) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // 执行真正的添加
        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        // 无前置 ChunList 节点，移动失败
        if (prevList == null) {
            // 无前置节点时，PoolChunk 的内存使用率是 0 ，按照 Netty 目前的实践。
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;
            return false;
        }
        // 移动到前置节点
        return prevList.move(chunk);
    }

    void add(PoolChunk<T> chunk) {
        if (chunk.freeBytes <= freeMinThreshold) {
            nextList.add(chunk);
            return;
        }
        // 执行真正的添加
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        // 无头节点，自己成为头节点
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        // 有头节点，自己成为头节点，原头节点成为自己的下一个节点
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    private void remove(PoolChunk<T> cur) {
        // 当前节点为首节点，将下一个节点设置为头节点
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        // 当前节点非首节点，将节点的上一个节点指向节点的下一个节点
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    // 保证最小 >= 1
    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            // 空，返回 EMPTY_METRICS
            if (head == null) {
                return EMPTY_METRICS;
            }
            // 生成数组，后生成 Iterator
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        // 循环，销毁 ChunkList 管理的所有 Chunk
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        // 置空
        head = null;
    }
}
