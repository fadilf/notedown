package com.notedown

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Experimental reusable session. Identical model, normalization, state, and threshold to android-vad. */
internal class LabSilero(context: Context, private val frameSize: Int = 512) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(context.assets.open("silero_vad.onnx").use { it.readBytes() }, options)
    }
    private fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val audio = floats(frameSize)
    private val h = floats(128)
    private val c = floats(128)
    private val tensors = linkedMapOf(
        "input" to OnnxTensor.createTensor(env, audio, longArrayOf(1, frameSize.toLong())),
        "sr" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf(1)),
        "h" to OnnxTensor.createTensor(env, h, longArrayOf(2, 1, 64)),
        "c" to OnnxTensor.createTensor(env, c, longArrayOf(2, 1, 64))
    )

    fun reset() { for (index in 0 until 128) { h.put(index, 0f); c.put(index, 0f) } }

    fun speech(bytes: ByteArray): Boolean {
        for (index in 0 until frameSize) {
            val sample = (bytes[2 * index].toInt() and 255) or (bytes[2 * index + 1].toInt() shl 8)
            audio.put(index, sample / 32767.0f)
        }
        return session.run(tensors).use { result ->
            @Suppress("UNCHECKED_CAST")
            val hn = result[1].value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val cn = result[2].value as Array<Array<FloatArray>>
            for (layer in 0..1) for (index in 0 until 64) {
                h.put(layer * 64 + index, hn[layer][0][index])
                c.put(layer * 64 + index, cn[layer][0][index])
            }
            @Suppress("UNCHECKED_CAST")
            ((result[0].value as Array<FloatArray>)[0][0] > 0.5f)
        }
    }

    override fun close() { tensors.values.forEach { it.close() }; session.close() }
}
