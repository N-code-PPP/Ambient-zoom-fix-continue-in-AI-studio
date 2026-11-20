package com.ambientbrain.recorder.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lock-free DirectByteBuffer pool for zero-allocation PCM16 transfer between the
 * real-time AudioRecord producer thread and the dedicated MediaCodec AAC encoder thread.
 *
 * Uses a lightweight [ReentrantLock] + [java.util.concurrent.locks.Condition] solely to
 * park and wake the encoder consumer thread on buffer arrival or shutdown (eliminating
 * 166 Hz `Thread.sleep(6L)` spin-polling).
 */
class DirectPcmBufferPool(
    val poolSize: Int = DEFAULT_POOL_SIZE,
    val bufferCapacityBytes: Int = FRAME_BYTES_512_SAMPLES
) {
    private val freeQueue = ConcurrentLinkedQueue<ByteBuffer>()
    private val filledQueue = ConcurrentLinkedQueue<ByteBuffer>()
    private val wakeLock = ReentrantLock()
    private val dataAvailable = wakeLock.newCondition()

    @Volatile
    private var stopWakeRequested: Boolean = false

    val starvationDrops = AtomicLong(0L)
    val audioReadErrors = AtomicLong(0L)
    val totalFramesSubmitted = AtomicLong(0L)

    init {
        repeat(poolSize) {
            val directBuf = ByteBuffer.allocateDirect(bufferCapacityBytes)
                .order(ByteOrder.nativeOrder())
            freeQueue.offer(directBuf)
        }
    }

    fun acquireFreeBuffer(): ByteBuffer? {
        val buf = freeQueue.poll()
        if (buf != null) {
            buf.clear()
            buf.order(ByteOrder.nativeOrder())
            return buf
        }
        return runCatching {
            ByteBuffer.allocateDirect(bufferCapacityBytes).order(ByteOrder.nativeOrder())
        }.getOrElse {
            starvationDrops.incrementAndGet()
            null
        }
    }

    fun submitFilledBuffer(buffer: ByteBuffer) {
        if (buffer.remaining() <= 0) {
            returnFreeBuffer(buffer)
            return
        }
        totalFramesSubmitted.incrementAndGet()
        filledQueue.offer(buffer)
        signalConsumerWake()
    }

    fun submitShortArrayChunked(samples: ShortArray) {
        if (samples.isEmpty()) return
        var offset = 0
        val shortsPerBuf = bufferCapacityBytes / 2
        while (offset < samples.size) {
            val count = minOf(shortsPerBuf, samples.size - offset)
            val buf = freeQueue.poll()?.apply {
                clear()
                order(ByteOrder.nativeOrder())
            } ?: ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())

            buf.asShortBuffer().put(samples, offset, count)
            buf.position(0)
            buf.limit(count * 2)
            totalFramesSubmitted.incrementAndGet()
            filledQueue.offer(buf)
            offset += count
        }
        signalConsumerWake()
    }

    fun pollFilledBuffer(): ByteBuffer? = filledQueue.poll()

    /**
     * Lock-free fast check first; if empty, parks cleanly on Condition until a new filled buffer
     * is submitted, [signalConsumerWake] is called, or [timeoutMs] elapses.
     */
    fun awaitFilledBuffer(timeoutMs: Long = 200L): ByteBuffer? {
        filledQueue.poll()?.let { return it }
        if (stopWakeRequested) return filledQueue.poll()

        wakeLock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
            while (remainingNs > 0L && !stopWakeRequested) {
                filledQueue.poll()?.let { return it }
                remainingNs = dataAvailable.awaitNanos(remainingNs)
            }
        }
        return filledQueue.poll()
    }

    fun resetWakeState() {
        stopWakeRequested = false
    }

    fun signalConsumerWake(isStop: Boolean = false) {
        if (isStop) {
            stopWakeRequested = true
        }
        wakeLock.withLock {
            dataAvailable.signalAll()
        }
    }

    fun returnFreeBuffer(buffer: ByteBuffer) {
        if (buffer.capacity() == bufferCapacityBytes && freeQueue.size < poolSize * 2) {
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())
            freeQueue.offer(buffer)
        }
    }

    fun drainFilledToFree() {
        while (true) {
            val buf = filledQueue.poll() ?: break
            returnFreeBuffer(buf)
        }
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 48 // 48 buffers * 32 ms = 1,536 ms (absorbs pre-roll bursts)
        const val FRAME_SAMPLES = 512
        const val FRAME_BYTES_512_SAMPLES = FRAME_SAMPLES * 2 // 1024 bytes = 32 ms @ 16 kHz mono PCM16
    }
}
