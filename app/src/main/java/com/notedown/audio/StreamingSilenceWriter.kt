package com.notedown.audio

import java.io.OutputStream
import java.util.ArrayDeque

/** The same 32 ms decisions and 480 ms padding as SilenceTrimmer, with bounded lookbehind. */
internal class StreamingSilenceWriter(
    private val output: OutputStream,
    private val isSpeech: (ByteArray) -> Boolean
) {
    private data class Frame(val index: Int, val bytes: ByteArray)
    private val pending = ArrayDeque<Frame>()
    private val frame = ByteArray(SilenceTrimmer.FRAME_BYTES)
    private var filled = 0
    private var index = 0
    private var lastWritten = -1
    private var trailing = 0
    private var foundSpeech = false
    var inputBytes: Int = 0
        private set
    var retainedBytes: Int = 0
        private set

    fun accept(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size && count % 2 == 0)
        inputBytes += count
        var offset = 0
        while (offset < count) {
            val length = minOf(frame.size - filled, count - offset)
            bytes.copyInto(frame, filled, offset, offset + length)
            filled += length
            offset += length
            if (filled == frame.size) { process(filled); filled = 0 }
        }
    }

    fun finish(): Boolean {
        if (filled != 0) {
            frame.fill(0, filled)
            process(filled)
            filled = 0
        }
        return foundSpeech && retainedBytes != inputBytes
    }

    private fun write(value: Frame) {
        if (value.index <= lastWritten) return
        output.write(value.bytes)
        retainedBytes += value.bytes.size
        lastWritten = value.index
    }

    private fun process(count: Int) {
        val current = Frame(index++, frame.copyOf(count))
        if (isSpeech(frame)) {
            foundSpeech = true
            pending.forEach(::write)
            write(current)
            trailing = SilenceTrimmer.PADDING_FRAMES
        } else if (trailing > 0) {
            write(current)
            trailing--
        }
        pending.addLast(current)
        if (pending.size > SilenceTrimmer.PADDING_FRAMES) pending.removeFirst()
    }

}
