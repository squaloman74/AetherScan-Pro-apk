package com.aetherscan.pro.aetherscan_pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : FlutterActivity(), TextToSpeech.OnInitListener {
    private val CHANNEL = "com.aetherscan.pro/audio"
    private val PREFS_NAME = "AetherScanProPrefs"
    private val PREF_KEY_LICENSE = "saved_license_key"
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var staticTrack: AudioTrack? = null
    private var isStaticPlaying = false
    // FIX Beta 4.1: coda per speech arrivato prima che onInit sia completato
    private var pendingSpeechText: String? = null
    private var pendingSpeechVolume: Float = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Beta 4: Schermo sempre acceso mentre l'app è aperta
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Inizializza Text-To-Speech nativo di Android
        tts = TextToSpeech(this, this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getDeviceHwid" -> {
                    val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
                    val md5 = MessageDigest.getInstance("MD5")
                    val digest = md5.digest("ANDROID:$androidId".toByteArray())
                    val hwid = digest.joinToString("") { "%02x".format(it) }
                    result.success(hwid)
                }
                "getSavedLicense" -> {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val savedKey = prefs.getString(PREF_KEY_LICENSE, "") ?: ""
                    result.success(savedKey)
                }
                "saveLicense" -> {
                    val keyToSave = call.argument<String>("licenseKey") ?: ""
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(PREF_KEY_LICENSE, keyToSave).apply()
                    result.success(true)
                }
                "speak" -> {
                    val text = call.argument<String>("text") ?: ""
                    val volume = call.argument<Double>("volume") ?: 1.0
                    speakText(text, volume.toFloat())
                    result.success(true)
                }
                "playBeep" -> {
                    val freq = call.argument<Int>("freq") ?: 880
                    val durationMs = call.argument<Int>("durationMs") ?: 100
                    playTone(freq, durationMs)
                    result.success(true)
                }
                "playChime" -> {
                    playChimeSound()
                    result.success(true)
                }
                "startStatic" -> {
                    startRadioStatic()
                    result.success(true)
                }
                "stopStatic" -> {
                    stopRadioStatic()
                    result.success(true)
                }
                // Beta 4: WakeLock dinamico — ON durante scan, OFF quando si ferma
                "setWakeLock" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    runOnUiThread {
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    result.success(true)
                }
                // Beta 4: Apre URL nel browser per installazione aggiornamenti
                "openUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.ITALIAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.85f)
            tts?.setPitch(0.95f)
            ttsReady = true
            // FIX Beta 4.1: riproduci speech in coda se arrivato prima di onInit
            pendingSpeechText?.let { speakText(it, pendingSpeechVolume) }
            pendingSpeechText = null
        }
    }

    private fun speakText(text: String, volume: Float) {
        if (ttsReady && tts != null) {
            // FIX Beta 4.1: KEY_PARAM_VOLUME deprecato in API 35+ → usa Bundle con STREAM_MUSIC
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "EVP_ID_" + System.currentTimeMillis())
        } else {
            // FIX Beta 4.1: TTS non ancora pronto, metti in coda
            pendingSpeechText = text
            pendingSpeechVolume = volume
        }
    }

    private fun playTone(freqHz: Int, durationMs: Int) {
        thread {
            try {
                val sampleRate = 22050
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val samples = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    val env = 1.0 - (i.toDouble() / numSamples)
                    samples[i] = (Math.sin(angle) * Short.MAX_VALUE * 0.4 * env).toInt().toShort()
                }

                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    numSamples * 2,
                    AudioTrack.MODE_STATIC
                )
                track.write(samples, 0, numSamples)
                track.play()
                Thread.sleep(durationMs.toLong() + 50)
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playChimeSound() {
        thread {
            val freqs = intArrayOf(650, 950, 1300)
            for (f in freqs) {
                playTone(f, 90)
                Thread.sleep(85)
            }
        }
    }

    private fun startRadioStatic() {
        if (isStaticPlaying) return
        isStaticPlaying = true
        thread {
            try {
                val sampleRate = 16000
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val buffer = ShortArray(minBufferSize)
                val random = Random(System.currentTimeMillis())

                staticTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize,
                    AudioTrack.MODE_STREAM
                )
                staticTrack?.play()

                while (isStaticPlaying) {
                    for (i in buffer.indices) {
                        buffer[i] = (random.nextInt(-2000, 2000)).toShort()
                    }
                    staticTrack?.write(buffer, 0, buffer.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopRadioStatic() {
        isStaticPlaying = false
        try {
            staticTrack?.stop()
            staticTrack?.release()
            staticTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopRadioStatic()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
