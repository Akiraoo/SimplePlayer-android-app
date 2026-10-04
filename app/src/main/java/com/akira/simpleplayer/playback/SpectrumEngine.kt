package com.akira.simpleplayer.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Audio spectrum engine.
 *
 * Does NOT touch the microphone or android.media.audiofx.Visualizer.
 * PlaybackService feeds decoded PCM (through SpectrumAudioProcessor) into
 * [processSamples]; the UI polls [readLatest] once per display frame.
 *
 * Performance notes (vs. the previous version):
 *  - FFT twiddles, bit-reversal table and window are precomputed once.
 *  - Float math only, squared magnitude instead of Math.hypot().
 *  - Band -> FFT-bin ranges are precomputed (rebuilt only if the sample rate changes).
 *  - No allocation per frame: results go into a fixed double-buffer.
 *  - No StateFlow: the UI pulls the newest frame and does its own time-based
 *    smoothing, so the bars are decoupled from the (bursty) audio callback rate.
 */
class SpectrumEngine {

    companion object {
        const val BAR_COUNT = 48
        const val FFT_SIZE = 2048

        /** 50% overlap => a new spectrum roughly every 23 ms at 44.1 kHz. */
        private const val HOP_SIZE = FFT_SIZE / 2

        private const val MIN_FREQUENCY = 40.0
        private const val MAX_FREQUENCY = 16000.0

        /*
         * Sensitivity tuning (unchanged):
         *  - Bars still hit the top too often -> raise DB_CEILING (e.g. -8).
         *  - Quiet passages look dead         -> lower DB_FLOOR   (e.g. -75).
         */
        private const val DB_FLOOR = -70f
        private const val DB_CEILING = -10f
        private const val AMPLITUDE_NORMALIZER = FFT_SIZE / 4.0
        private const val MIN_POWER = 1e-10f

        /** If no new frame arrived for this long, playback is paused/stopped. */
        private const val STALE_AFTER_NANOS = 120_000_000L

        val shared: SpectrumEngine by lazy { SpectrumEngine() }
    }

    // ---- precomputed tables -------------------------------------------------
    private val window = FloatArray(FFT_SIZE)
    private val cosTable = FloatArray(FFT_SIZE / 2)
    private val sinTable = FloatArray(FFT_SIZE / 2)
    private val bitReverse = IntArray(FFT_SIZE)

    private val lowBin = IntArray(BAR_COUNT)
    private val highBin = IntArray(BAR_COUNT)
    private val normDb = (20.0 * log10(AMPLITUDE_NORMALIZER)).toFloat()

    // ---- analysis state (guarded by analysisLock) ------------------------------
    private val analysisLock = Any()
    private val samples = FloatArray(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val work = FloatArray(BAR_COUNT)
    private var sampleCount = 0
    private var sampleRate = 44100

    // ---- published frame (guarded by publishLock) -------------------------------
    private val publishLock = Any()
    private val published = FloatArray(BAR_COUNT)
    private var lastFrameNanos = 0L

    init {
        for (i in 0 until FFT_SIZE) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }
        for (i in 0 until FFT_SIZE / 2) {
            val a = -2.0 * PI * i / FFT_SIZE
            cosTable[i] = cos(a).toFloat()
            sinTable[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
        rebuildBands()
    }

    /**
     * Called from the playback thread with mono float PCM in [-1, 1].
     * Only the first [length] entries of [input] are valid.
     */
    fun processSamples(input: FloatArray, length: Int, inputSampleRate: Int) {
        if (length <= 0) return

        synchronized(analysisLock) {
            if (inputSampleRate > 0 && inputSampleRate != sampleRate) {
                sampleRate = inputSampleRate
                rebuildBands()
            }

            var offset = 0
            while (offset < length) {
                val copy = min(FFT_SIZE - sampleCount, length - offset)
                System.arraycopy(input, offset, samples, sampleCount, copy)
                sampleCount += copy
                offset += copy

                if (sampleCount == FFT_SIZE) {
                    analyseFrame()
                    // Keep the newest half for the next (overlapping) frame.
                    System.arraycopy(samples, HOP_SIZE, samples, 0, FFT_SIZE - HOP_SIZE)
                    sampleCount = FFT_SIZE - HOP_SIZE
                }
            }
        }
    }

    /**
     * Copies the newest spectrum (0..1 per bar) into [dest].
     * Returns zeros when playback has stopped/paused so the UI can fall to rest.
     * Safe to call from the UI thread every frame (48 floats, tiny critical section).
     */
    fun readLatest(dest: FloatArray) {
        val now = System.nanoTime()
        synchronized(publishLock) {
            if (lastFrameNanos == 0L || now - lastFrameNanos > STALE_AFTER_NANOS) {
                java.util.Arrays.fill(dest, 0, BAR_COUNT, 0f)
            } else {
                System.arraycopy(published, 0, dest, 0, BAR_COUNT)
            }
        }
    }

    fun stop() {
        synchronized(analysisLock) {
            sampleCount = 0
        }
        synchronized(publishLock) {
            java.util.Arrays.fill(published, 0f)
            lastFrameNanos = 0L
        }
    }

    // -------------------------------------------------------------------------

    private fun rebuildBands() {
        val maxFrequency = min(sampleRate / 2.0, MAX_FREQUENCY)
        val ratio = maxFrequency / MIN_FREQUENCY
        val nyquistBin = FFT_SIZE / 2 - 1

        for (bar in 0 until BAR_COUNT) {
            val lowFreq = MIN_FREQUENCY * ratio.pow(bar.toDouble() / BAR_COUNT)
            val highFreq = MIN_FREQUENCY * ratio.pow((bar + 1).toDouble() / BAR_COUNT)

            val low = min(nyquistBin - 1, max(1, (lowFreq * FFT_SIZE / sampleRate).toInt()))
            val high = min(nyquistBin, max(low + 1, (highFreq * FFT_SIZE / sampleRate).toInt()))

            lowBin[bar] = low
            highBin[bar] = high
        }
    }

    private fun analyseFrame() {
        // Windowing + bit-reversal permutation in one pass.
        for (i in 0 until FFT_SIZE) {
            val j = bitReverse[i]
            re[j] = samples[i] * window[i]
            im[j] = 0f
        }

        fft()

        for (bar in 0 until BAR_COUNT) {
            val lo = lowBin[bar]
            val hi = highBin[bar]

            var power = 0f
            for (bin in lo..hi) {
                power += re[bin] * re[bin] + im[bin] * im[bin]
            }
            power /= (hi - lo + 1)

            work[bar] =
                if (power <= MIN_POWER) {
                    0f
                } else {
                    val db = 10f * log10(power) - normDb
                    ((db - DB_FLOOR) / (DB_CEILING - DB_FLOOR)).coerceIn(0f, 1f)
                }
        }

        synchronized(publishLock) {
            System.arraycopy(work, 0, published, 0, BAR_COUNT)
            lastFrameNanos = System.nanoTime()
        }
    }

    /** In-place radix-2 FFT on already bit-reversed data, using precomputed twiddles. */
    private fun fft() {
        var length = 2
        while (length <= FFT_SIZE) {
            val half = length shr 1
            val step = FFT_SIZE / length

            var i = 0
            while (i < FFT_SIZE) {
                var t = 0
                for (k in 0 until half) {
                    val wr = cosTable[t]
                    val wi = sinTable[t]
                    val a = i + k
                    val b = a + half

                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr

                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi

                    t += step
                }
                i += length
            }
            length = length shl 1
        }
    }
}
