package com.aetherscan.pro.aetherscan_pro

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
    private var pendingSpeechGender: String = "female"
    private var creepyMediaPlayer: MediaPlayer? = null

    // Sensori
    private lateinit var sensorManager: SensorManager
    private var sensorEventSink: EventChannel.EventSink? = null
    private var emfBaseline: Double = -1.0
    private var emfSpikeActive = false
    private var lastProximityNear = false

    // Rilevamento tocco / sfioramento intelligente (anti-falsi positivi impugnatura)
    private val gravity = FloatArray(3)
    private var lastTouchTriggerTime = 0L

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
                    val gender = call.argument<String>("gender") ?: "female"
                    speakText(text, volume.toFloat(), gender)
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
                "playCreepyLaugh" -> {
                    playCreepyLaughSound()
                    result.success(true)
                }
                "startStatic" -> { startRadioStatic(); result.success(true) }
                "stopStatic"  -> { stopRadioStatic(); result.success(true) }
                "audioDiagnostic" -> {
                    result.success(buildDiagnostic())
                }
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
    // Diagnostica Audio e Sensori Android
    // ──────────────────────────────────────────────────────────────────
    private fun buildDiagnostic(): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(PREF_KEY_LICENSE, "") ?: ""
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        return """
=== DIAGNOSTICA AETHERSCAN (ANDROID) ===
Channel registrato: true
Volume Stream Music: $currentVol / $maxVol
TTS Ready: $ttsReady
TTS Lingua: ${tts?.language?.displayName ?: "N/D"}
Licenza salvata: ${if (savedKey.isEmpty()) "NO" else "SI (${savedKey.length} chars)"}
Prossimita: ${if (proximitySensor != null) "DISPONIBILE" else "NON DISPONIBILE"}
Magnetometro (EMF): ${if (magSensor != null) "DISPONIBILE" else "NON DISPONIBILE"}
Sensore Tocco (Accel): ${if (accelSensor != null) "DISPONIBILE" else "NON DISPONIBILE"}
EMF Baseline: ${if (emfBaseline < 0) "-1.0" else String.format(Locale.US, "%.1f", emfBaseline)} uT
""".trimIndent()
    }

    // ──────────────────────────────────────────────────────────────────
    // Gestione Sensori
    // ──────────────────────────────────────────────────────────────────
    private fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
            Sensor.TYPE_ACCELEROMETER -> {
                // Filtro passa-basso per la gravità (orientamento/movimento lento mano: alpha = 0.85)
                val alpha = 0.85f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                // Lineare (passa-alto): componente istantanea di accelerazione/impulso
                val linearX = event.values[0] - gravity[0]
                val linearY = event.values[1] - gravity[1]
                val linearZ = event.values[2] - gravity[2]
                val impulse = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble())

                // Soglia tarata: 3.2 m/s² (sensibile al tocco/sfioramento, ma ignora oscillazioni lente della mano)
                val now = System.currentTimeMillis()
                if (impulse > 3.2 && (now - lastTouchTriggerTime > 5000)) {
                    lastTouchTriggerTime = now
                    runOnUiThread {
                        sensorEventSink?.success(mapOf(
                            "type" to "touch",
                            "intensity" to impulse
                        ))
                    }
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
            pendingSpeechText?.let { speakText(it, pendingSpeechVolume, pendingSpeechGender) }
            pendingSpeechText = null
        }
    }

    private fun speakText(text: String, volume: Float, gender: String = "female") {
        if (ttsReady && tts != null) {
            val pitch = if (gender == "male") 0.80f else 1.10f
            tts?.setPitch(pitch)
            try {
                val voices = tts?.voices
                if (voices != null) {
                    val matchingVoice = voices.firstOrNull { voice ->
                        voice.locale.language == "it" && 
                        ((gender == "male" && (voice.name.contains("male", ignoreCase = true) || voice.name.contains("masc", ignoreCase = true))) ||
                         (gender == "female" && (voice.name.contains("female", ignoreCase = true) || voice.name.contains("femm", ignoreCase = true))))
                    } ?: voices.firstOrNull { it.locale.language == "it" }
                    if (matchingVoice != null) {
                        tts?.voice = matchingVoice
                    }
                }
            } catch (_: Exception) {}

            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "EVP_ID_" + System.currentTimeMillis())
        } else {
            pendingSpeechText = text
            pendingSpeechVolume = volume
            pendingSpeechGender = gender
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Riproduzione Suono Risata Inquietante (Creepy Laugh)
    // ──────────────────────────────────────────────────────────────────
    private fun playCreepyLaughSound() {
        thread {
            try {
                creepyMediaPlayer?.release()
                creepyMediaPlayer = null

                // Prova 1: Risorsa nativa raw
                val resId = resources.getIdentifier("creepy_laugh", "raw", packageName)
                if (resId != 0) {
                    creepyMediaPlayer = MediaPlayer.create(this@MainActivity, resId)
                }

                // Prova 2: Asset Flutter
                if (creepyMediaPlayer == null) {
                    val afd: AssetFileDescriptor = assets.openFd("flutter_assets/assets/audio/creepy_laugh.mp3")
                    creepyMediaPlayer = MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        prepare()
                    }
                    afd.close()
                }

                creepyMediaPlayer?.apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setVolume(1.0f, 1.0f)
                    setOnCompletionListener { mp ->
                        mp.release()
                        creepyMediaPlayer = null
                    }
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Audio Sintetico (Beep / Chime / Prossimità / EMF)
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
        creepyMediaPlayer?.release()
        creepyMediaPlayer = null
        super.onDestroy()
    }
}
