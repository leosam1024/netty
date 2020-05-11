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

final class PoolSubpage<T> implements PoolSubpageMetric {

    /**
     * ���� PoolChunk ����
     */
    final PoolChunk<T> chunk;
    /**
     * �� {@link PoolChunk#memoryMap} �Ľڵ���
     */
    private final int memoryMapIdx;
    /**
     * �� Chunk �У�ƫ���ֽ���
     *
     * @see PoolChunk#runOffset(int)
     */
    private final int runOffset;
    /**
     * Page ��С {@link PoolChunk#pageSize}
     */
    private final int pageSize;

    /**
     * Subpage ������Ϣ����
     *
     * ÿ�� long �� bits λ����һ�� Subpage �Ƿ���䡣
     * ��Ϊ PoolSubpage ���ܻᳬ�� 64 ��( long �� bits λ�� )������ʹ�����顣
     *   ���磺Page Ĭ�ϴ�СΪ 8KB ��Subpage Ĭ����СΪ 16 B ������һ�� Page ���ɰ��� 8 * 1024 / 16 = 512 �� Subpage ��
     *        ��ˣ�bitmap �����СΪ 512 / 64 = 8 ��
     * ���⣬bitmap �������С��ʹ�� {@link #bitmapLength} ����ǡ�����˵��bitmap ���飬Ĭ�ϰ��� Subpage �Ĵ�СΪ 16B ����ʼ����
     *    Ϊʲô���������趨�أ���Ϊ PoolSubpage �����ã�ͨ�� {@link #init(PoolSubpage, int)} �������³�ʼ����
     */
    private final long[] bitmap;

    /**
     * ˫������ǰһ�� PoolSubpage ����
     */
    PoolSubpage<T> prev;
    /**
     * ˫��������һ�� PoolSubpage ����
     */
    PoolSubpage<T> next;

    /**
     * �Ƿ�δ����
     */
    boolean doNotDestroy;
    /**
     * ÿ�� Subpage ��ռ���ڴ��С
     */
    int elemSize;
    /**
     * �ܹ� Subpage ������
     */
    private int maxNumElems;
    /**
     * {@link #bitmap} ����
     */
    private int bitmapLength;
    /**
     * ��һ���ɷ��� Subpage ������λ��
     */
    private int nextAvail;
    /**
     * ʣ����� Subpage ������
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    // ˫������ͷ�ڵ�
    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    // ˫������Page �ڵ�
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        // ���� bitmap ����
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        // ��ʼ��
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        // δ����
        doNotDestroy = true;
        // ��ʼ�� elemSize
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // ��ʼ�� maxNumElems
            maxNumElems = numAvail = pageSize / elemSize;
            // ��ʼ�� nextAvail
            nextAvail = 0;
            // ���� bitmapLength �Ĵ�С
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) { // δ�������� 1.
                bitmapLength ++;
            }

            // ��ʼ�� bitmap
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        // ��ӵ� Arena ��˫�������С�
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // �����Ա�̣����������������
        if (elemSize == 0) {
            return toHandle(0);
        }

        // ��������Ϊ 0 �����������٣����� -1 �������ɷ��䡣
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // �����һ�����õ� Subpage �� bitmap �е�����λ��
        final int bitmapIdx = getNextAvail();
        // �����һ�����õ� Subpage �� bitmap �������λ��
        int q = bitmapIdx >>> 6;
        // �����һ�����õ� Subpage �� bitmap �������λ�õĵڼ� bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // �޸� Subpage �� bitmap �в��ɷ��䡣
        bitmap[q] |= 1L << r;

        // ���� Subpage �ڴ��ļ�����һ
        if (-- numAvail == 0) { // �޿��� Subpage �ڴ��
            // ��˫���������Ƴ�
            removeFromPool();
        }

        // ���� handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // �����Ա�̣����������������
        if (elemSize == 0) {
            return true;
        }
        // ��� Subpage �� bitmap �������λ��
        int q = bitmapIdx >>> 6;
        // ��� Subpage �� bitmap �������λ�õĵڼ� bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // �޸� Subpage �� bitmap �пɷ��䡣
        bitmap[q] ^= 1L << r;

        // ������һ������Ϊ��ǰ Subpage
        setNextAvail(bitmapIdx);

        // ���� Subpage �ڴ��ļ�����һ
        if (numAvail ++ == 0) {
            // ��ӵ� Arena ��˫�������С�
            addToPool(head);
            return true;
        }

        // ���� Subpage ��ʹ��
        if (numAvail != maxNumElems) {
            return true;
        // û�� Subpage ��ʹ��
        } else {
            // ˫�������У�ֻ�иýڵ㣬�������Ƴ�
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // ���Ϊ������
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // ��˫���������Ƴ�
            removeFromPool();
            return false;
        }
    }

    // ��ӵ� Arena ��˫��������
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        // ����ǰ�ڵ㣬���뵽 head �� head.next �м�
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    // ��˫���������Ƴ�
    private void removeFromPool() {
        assert prev != null && next != null;
        // ǰ��ڵ㣬����ָ��
        prev.next = next;
        next.prev = prev;
        // ��ǰ�ڵ㣬�ÿ�
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // nextAvail ���� 0 ����ζ���Ѿ������桱����һ�����õ�λ�ã�ֱ�ӷ��ؼ��ɡ�
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        // Ѱ����һ�� nextAvail
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // ѭ�� bitmap
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // ~ ��������������� 0 ��˵���п��õ� Subpage
            if (~bits != 0) {
                // ���� bits Ѱ�ҿ��� nextAvail
                return findNextAvail0(i, bits);
            }
        }
        // δ�ҵ�
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // �������ֵ����ʾ�� bitmap �������±�
        final int baseVal = i << 6; // �൱�� * 64

        // ���� 64 bits
        for (int j = 0; j < 64; j ++) {
            // ���㵱ǰ bit �Ƿ�δ����
            if ((bits & 1) == 0) {
                // ���� bitmap ���һ��Ԫ�أ���û�� 64 λ��ͨ�� baseVal | j < maxNumElems ����֤���������ޡ�
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // ȥ����ǰ bit
            bits >>>= 1;
        }

        // δ�ҵ�
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
