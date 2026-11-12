package com.assistant.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Procedural Audio Synthesizer for Marvel-Grade Stark OS Sound Effects.
 * Zero external audio files required — generates low-latency acoustic frequencies on the fly.
 */
object SoundEffectManager {

    private val scope = CoroutineScope(Dispatchers.Default)
    private const val SAMPLE_RATE = 44100

    /**
     * Iconic Tony Stark HUD Wake Chime: Ascending dual harmonic pulse (B5 -> E6)
     */
    fun playWakeChime() {
        scope.launch {
            val tone1 = generateSineWave(987.77, 110, 0.7f) // B5
            val tone2 = generateSineWave(1318.51, 160, 0.85f) // E6
            val combined = tone1 + tone2
            playRawPcm(combined)
        }
    }

    /**
     * Tactical Affirmative Confirmation: High-tech dual micro-chirp
     */
    fun playConfirmChirp() {
        scope.launch {
            val chirp1 = generateSineWave(1480.0, 45, 0.6f)
            val silence = ShortArray((SAMPLE_RATE * 0.02).toInt())
            val chirp2 = generateSineWave(1975.5, 60, 0.7f)
            val combined = chirp1 + silence + chirp2
            playRawPcm(combined)
        }
    }

    /**
     * Proactive Alert Chime: "Pardon the interruption, Sir" cautionary pulse
     */
    fun playAlertChime() {
        scope.launch {
            val pulse1 = generateSineWave(659.25, 90, 0.7f) // E5
            val silence = ShortArray((SAMPLE_RATE * 0.03).toInt())
            val pulse2 = generateSineWave(880.0, 140, 0.8f) // A5
            val combined = pulse1 + silence + pulse2
            playRawPcm(combined)
        }
    }

    /**
     * Sentient Security Intrusion Alarm: Ominous low-harmonic warning burst
     */
    fun playSecurityAlert() {
        scope.launch {
            val burst1 = generateSineWave(440.0, 150, 0.9f)
            val burst2 = generateSineWave(370.0, 200, 0.95f)
            val combined = burst1 + burst2
            playRawPcm(combined)
        }
    }

    private fun generateSineWave(frequency: Double, durationMs: Int, peakVolume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000.0)).toInt()
        val samples = ShortArray(numSamples)
        val angularFreq = 2.0 * Math.PI * frequency / SAMPLE_RATE

        for (i in 0 until numSamples) {
            // Apply exponential decay envelope for smooth acoustic bell taper
            val progress = i.toDouble() / numSamples
            val envelope = (1.0 - progress) * (1.0 - progress) * peakVolume
            val sample = (sin(angularFreq * i) * envelope * Short.MAX_VALUE).toInt()
            samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun playRawPcm(pcmData: ShortArray) {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize, pcmData.size * 2)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()

            // Release after playing
            val durationMs = (pcmData.size.toDouble() / SAMPLE_RATE * 1000).toLong() + 50
            Thread.sleep(durationMs)
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            // Non-critical audio failure fallback
        }
    }
}
