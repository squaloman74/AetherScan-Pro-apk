package com.aetherscan.pro.aetherscan_pro

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : FlutterActivity(), TextToSpeech.OnInitListener, SensorEventListener {
    private val CHANNEL        = "com.aetherscan.pro/audio"
    private val SENSOR_CHANNEL = "com.aetherscan.pro/sensors"
    private val PREFS_NAME     = "AetherScanProPrefs"
    private val PREF_KEY_LICENSE = "saved_license_key"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var staticTrack: AudioTrack? = null
    private var isStaticPlaying = false
    private var pendingSpeechText: String? = null
    private var pendingSpeechVolume: Float = 1.0f

    // Sensori
    private lateinit var sensorManager: SensorManager
    private var sensorEventSink: EventChannel.EventSink? = null
    private var emfBaseline: Double = -1.0
    private var emfSpikeActive = false
    private var lastProximityNear = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        tts = TextToSpeech(this, this)

        // ── MethodChannel ──
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
                    result.success(prefs.getString(PREF_KEY_LICENSE, "") ?: "")
                }
                "saveLicense" -> {
                    val keyToSave = call.argument<String>("licenseKey") ?: ""
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_KEY_LICENSE, keyToSave).apply()
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
                "playChime" -> { playChimeSound(); result.success(true) }
                "playProximityAlert" -> { playProximityAlert(); result.success(true) }
                "playEmfAlert" -> { playEmfAlert(); result.success(true) }
                "startStatic" -> { startRadioStatic(); result.success(true) }
                "stopStatic"  -> { stopRadioStatic(); result.success(true) }
                "setWakeLock" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    runOnUiThread {
                        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    result.success(true)
                }
                "openUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        result.success(true)
                    } catch (e: Exception) { result.success(false) }
                }
                else -> result.notImplemented()
            }
        }

        // ── EventChannel (sensori) ──
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, SENSOR_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    sensorEventSink = events
                    startSensors()
                }
                override fun onCancel(arguments: Any?) {
                    stopSensors()
                    sensorEventSink = null
                }
            })
    }

    // ──────────────────────────────────────────────────────────────────
    // Gestione Sensori
    // ──────────────────────────────────────────────────────────────────
    private fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = event.sensor.maximumRange
                val near = event.values[0] < (maxRange / 2f)
                if (near != lastProximityNear) {
                    lastProximityNear = near
                    runOnUiThread {
                        sensorEventSink?.success(mapOf("type" to "proximity", "near" to near))
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val magnitude = sqrt(x*x + y*y + z*z)

                if (emfBaseline < 0) {
                    emfBaseline = magnitude
                    return
                }

                val delta = abs(magnitude - emfBaseline)
                val spikeThreshold = 25.0

                val isSpike: Boolean
                if (delta > spikeThreshold && !emfSpikeActive) {
                    emfSpikeActive = true
                    isSpike = true
                } else if (delta <= spikeThreshold * 0.5) {
                    emfSpikeActive = false
                    isSpike = false
                } else {
                    isSpike = false
                }

                emfBaseline = emfBaseline * 0.98 + magnitude * 0.02

                runOnUiThread {
                    sensorEventSink?.success(mapOf(
                        "type" to "emf",
                        "value" to magnitude,
                        "delta" to delta,
                        "spike" to isSpike
                    ))
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ──────────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.ITALIAN)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.85f)
            tts?.setPitch(0.95f)
            ttsReady = true
            pendingSpeechText?.let { speakText(it, pendingSpeechVolume) }
            pendingSpeechText = null
        }
    }

    private fun speakText(text: String, volume: Float) {
        if (ttsReady && tts != null) {
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "EVP_ID_" + System.currentTimeMillis())
        } else {
            pendingSpeechText = text
            pendingSpeechVolume = volume
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Audio
    // ──────────────────────────────────────────────────────────────────
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
                    AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    numSamples * 2, AudioTrack.MODE_STATIC
                )
                track.write(samples, 0, numSamples)
                track.play()
                Thread.sleep(durationMs.toLong() + 50)
                track.release()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun playChimeSound() {
        thread {
            for (f in intArrayOf(650, 950, 1300)) { playTone(f, 90); Thread.sleep(85) }
        }
    }

    private fun playProximityAlert() {
        thread {
            for (f in intArrayOf(1200, 900, 1200, 900)) { playTone(f, 80); Thread.sleep(120) }
        }
    }

    private fun playEmfAlert() {
        thread {
            for (f in intArrayOf(200, 220, 200)) { playTone(f, 150); Thread.sleep(180) }
        }
    }

    private fun startRadioStatic() {
        if (isStaticPlaying) return
        isStaticPlaying = true
        thread {
            try {
                val sampleRate = 16000
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val buffer = ShortArray(minBuf)
                val random = Random(System.currentTimeMillis())
                staticTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf, AudioTrack.MODE_STREAM)
                staticTrack?.play()
                while (isStaticPlaying) {
                    for (i in buffer.indices) buffer[i] = random.nextInt(-2000, 2000).toShort()
                    staticTrack?.write(buffer, 0, buffer.size)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopRadioStatic() {
        isStaticPlaying = false
        try { staticTrack?.stop(); staticTrack?.release(); staticTrack = null }
        catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        stopSensors()
        stopRadioStatic()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
