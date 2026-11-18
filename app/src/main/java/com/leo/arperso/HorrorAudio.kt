package com.leo.arperso

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sons générés à la volée (aucun fichier audio) :
 * - grésillement de vieille caméra en mode found footage,
 * - battements de cœur qui accélèrent quand la ghoule approche,
 * - bruit de glitch quand elle « saute » vers toi,
 * - hurlement distordu pour le jumpscare.
 */
class HorrorAudio {
    private val sampleRate = 22050

    @Volatile var staticOn = false
    @Volatile var muted = false

    /** Intervalle entre deux battements de cœur, en secondes (0 = pas de cœur). */
    @Volatile var heartbeatInterval = 0f
    @Volatile var heartbeatVolume = 0f

    @Volatile private var screamPos = -1
    @Volatile private var burstPos = -1

    private val scream: FloatArray = buildScream()
    private val burst: FloatArray = FloatArray((sampleRate * 0.18f).toInt()) {
        val t = it / sampleRate.toFloat()
        (Random.nextFloat() * 2f - 1f) * exp(-t * 14f) * 0.8f
    }

    @Volatile private var running = true
    private val thread = Thread(::loop, "horror-audio").apply { priority = Thread.MAX_PRIORITY }

    fun start() = thread.start()

    fun release() {
        running = false
    }

    fun scream() {
        screamPos = 0
    }

    fun glitch() {
        burstPos = 0
    }

    private fun loop() {
        val chunk = 441 // 20 ms
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, chunk * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val out = ShortArray(chunk)
        var filtered = 0f
        var beatClock = 0f
        var beatT = 10f // temps depuis le dernier battement
        val dt = 1f / sampleRate

        while (running) {
            for (i in 0 until chunk) {
                var s = 0f

                if (staticOn) {
                    val n = Random.nextFloat() * 2f - 1f
                    filtered = filtered * 0.6f + n * 0.4f
                    s += filtered * 0.07f
                    if (Random.nextInt(9000) == 0) s += (Random.nextFloat() - 0.5f) * 0.9f // craquement
                }

                val interval = heartbeatInterval
                if (interval > 0f) {
                    beatClock += dt
                    if (beatClock >= interval) {
                        beatClock = 0f
                        beatT = 0f
                    }
                }
                if (beatT < 0.6f) {
                    // « boum-boum » : deux coups graves rapprochés
                    val a = if (beatT < 0.22f) exp(-beatT * 18f) else 0.7f * exp(-(beatT - 0.22f) * 18f)
                    val tt = if (beatT < 0.22f) beatT else beatT - 0.22f
                    s += heartbeatVolume * a * (sin(2 * PI * 52 * tt).toFloat() * 0.8f + sin(2 * PI * 38 * tt).toFloat() * 0.6f)
                    beatT += dt
                }

                val bp = burstPos
                if (bp >= 0) {
                    s += burst[bp]
                    burstPos = if (bp + 1 < burst.size) bp + 1 else -1
                }

                val sp = screamPos
                if (sp >= 0) {
                    s += scream[sp]
                    screamPos = if (sp + 1 < scream.size) sp + 1 else -1
                }

                if (muted) s = 0f
                out[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(out, 0, chunk)
        }
        track.stop()
        track.release()
    }

    private fun buildScream(): FloatArray {
        val len = (sampleRate * 1.9f).toInt()
        val res = FloatArray(len)
        var p1 = 0.0
        var p2 = 0.0
        var p3 = 0.0
        for (i in 0 until len) {
            val t = i / sampleRate.toFloat()
            val env = minOf(1f, t / 0.015f) * if (t < 1.3f) 1f else exp(-(t - 1.3f) * 6f)
            val f0 = 760.0 - 260.0 * t + 70.0 * sin(2 * PI * 8.0 * t) + 30.0 * sin(2 * PI * 23.0 * t)
            p1 += f0 / sampleRate
            p2 += f0 * 1.49 / sampleRate
            p3 += f0 * 0.5 / sampleRate
            val saw = { p: Double -> (2.0 * (p - Math.floor(p)) - 1.0).toFloat() }
            var x = saw(p1) * 0.5f + saw(p2) * 0.35f + saw(p3) * 0.4f + (Random.nextFloat() * 2f - 1f) * 0.7f
            x *= (0.55f + 0.45f * sin(2 * PI * 85.0 * t).toFloat()) // modulation « gorge »
            x = tanh(x * 3.5f)
            res[i] = x * env * 0.95f
        }
        return res
    }
}
