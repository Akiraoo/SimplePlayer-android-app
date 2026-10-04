package com.akira.simpleplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through Media3 AudioProcessor.
 *
 * Forwards the decoded PCM unchanged while feeding a mono float copy to
 * [SpectrumEngine]. No microphone / Visualizer involved.
 *
 * Extends BaseAudioProcessor so the output buffer is REUSED between calls.
 * (The previous hand-written version reset its buffer to a zero-capacity
 * EMPTY_BUFFER in getOutput(), so every queueInput() allocated a fresh direct
 * ByteBuffer on the playback thread.)
 */
class SpectrumAudioProcessor(
    private val spectrumEngine: SpectrumEngine
) : BaseAudioProcessor() {

    private var mono = FloatArray(4096)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        analyse(inputBuffer)

        // Forward the exact original PCM (reuses the internal buffer).
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    /** Reads the buffer with absolute gets, so its position is left untouched. */
    private fun analyse(input: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        if (channels <= 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = input.remaining() / (channels * bytesPerSample)
        if (frames <= 0) return

        if (mono.size < frames) mono = FloatArray(frames)

        val buf = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var index = input.position()

        if (isFloat) {
            val inv = 1f / channels
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += buf.getFloat(index)
                    index += 4
                }
                mono[f] = sum * inv
            }
        } else {
            val inv = 1f / (32768f * channels)
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) {
                    sum += buf.getShort(index).toInt()
                    index += 2
                }
                mono[f] = sum * inv
            }
        }

        spectrumEngine.processSamples(mono, frames, inputAudioFormat.sampleRate)
    }

    override fun onFlush() {
        spectrumEngine.stop()
    }

    override fun onReset() {
        spectrumEngine.stop()
        mono = FloatArray(4096)
    }
}
