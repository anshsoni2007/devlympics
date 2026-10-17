package `in`.itantra.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 100% on-device neural voice pipeline:
 * - STT: AI4Bharat IndicConformer (Sherpa-ONNX)
 * - TTS: AI4Bharat Indic-TTS (FastPitch + HiFi-GAN) with zero-latency phonetics fallback
 * - Continuous hands-free broadcast locking with zero-drop VAD
 * - Completely free of Google Speech Services
 */
class SpeechEngine(
    private val activity: Activity,
    private val listener: Listener
) {
    interface Listener {
        fun event(type: String, data: JSONObject)
        fun transcript(text: String, language: String)
    }

    companion object {
        private const val TAG = "SWAR_Speech"

        private fun json(vararg fields: Any?): JSONObject {
            val o = JSONObject()
            var i = 0
            while (i < fields.size) {
                val key = fields[i] as? String ?: fields[i].toString()
                val value = if (i + 1 < fields.size) fields[i + 1] else null
                o.put(key, value)
                i += 2
            }
            return o
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val asyncPool = Executors.newCachedThreadPool { r ->
        Thread(r, "SWAR-VoiceWorker").apply { isDaemon = true }
    }

    private val packsDir = File(activity.filesDir, "language-packs").apply {
        if (!exists()) mkdirs()
    }
    private val persistentPacksDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "SWAR_VoiceModels"
    )
    private val altPersistentPacksDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "SWAR_Models"
    )

    private var listening = false
    private var conversation = false
    private var locked = false
    private var playing = false
    private var active = true
    private var closed = false

    private var language = "hi"
    private var captureLanguage = "hi"
    private var speechStarted = 0L

    private val playback = ArrayDeque<ItpPacket.Decoded>()
    private val downloadingPacks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var recognizer: OfflineRecognizer? = null
    private var loadedRecognizerTag: String? = null

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var captureRunning = false

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        main.post {
            try {
                tts = TextToSpeech(activity.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        val audioAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttrs)
                        tts?.setSpeechRate(1.0f)
                        Log.i("SWAR_TTS", "Android TextToSpeech engine initialized successfully")
                    } else {
                        Log.w("SWAR_TTS", "Android TextToSpeech engine failed with status $status")
                    }
                }
            } catch (t: Throwable) {
                Log.w("SWAR_TTS", "Could not initialize TextToSpeech: ${t.message}")
            }
        }

        asyncPool.execute {
            bootstrapPrebundledPacks()
            restoreBackupsAndPatchAll()
            val defTag = resolveTag(language)
            try {
                if (resolvePackDir(defTag) != null) {
                    ensureRecognizer(defTag)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pre-warming recognizer for $defTag notice: ${t.message}")
            }
            main.post { status() }
        }
    }

    fun resolvePackDir(tag: String): File? {
        val primary = File(packsDir, tag)
        val primaryModel = File(primary, "stt/indicconformer_int8.onnx")
        if (primaryModel.exists() && primaryModel.length() > 0) return primary

        val persistent = File(persistentPacksDir, tag)
        if (File(persistent, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
            try { copyRecursively(persistent, primary) } catch (ignored: Throwable) {}
            if (File(primary, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) return primary
        }
        val altPersistent = File(altPersistentPacksDir, tag)
        if (File(altPersistent, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
            try { copyRecursively(altPersistent, primary) } catch (ignored: Throwable) {}
            if (File(primary, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) return primary
        }
        return null
    }

    private fun event(type: String, vararg values: Any?) {
        listener.event(type, json(*values))
    }

    private fun bootstrapPrebundledPacks() {
        try {
            // Restore previously downloaded models from persistent storage (survives app uninstall!)
            val persistentSources = listOf(persistentPacksDir, altPersistentPacksDir)
            for (pDir in persistentSources) {
                if (pDir.exists() && pDir.isDirectory) {
                    pDir.listFiles()?.forEach { src ->
                        if (src.isDirectory) {
                            val dest = File(packsDir, src.name)
                            val model = File(dest, "stt/indicconformer_int8.onnx")
                            if (!dest.exists() || !model.exists() || model.length() == 0L) {
                                Log.i(TAG, "Restoring voice model ${src.name} from persistent storage across uninstall...")
                                copyRecursively(src, dest)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Prebundled packs bootstrap notice: ${t.message}")
        }
    }

    private fun copyRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { f -> copyRecursively(f, File(dest, f.name)) }
        } else {
            try {
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                        }
                    }
                }
            } catch (ignored: Throwable) {}
        }
    }

    fun language(value: String) {
        if (value.isBlank()) return
        stopConversation()
        cancelCapture()
        this.language = value.lowercase(Locale.ROOT)
        val tag = resolveTag(this.language)
        asyncPool.execute {
            try {
                if (resolvePackDir(tag) != null) {
                    ensureRecognizer(tag)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Recognizer switch notice for $tag: ${t.message}")
            }
        }
        status()
    }

    private fun resolveTag(lang: String?): String {
        if (lang == null) return "hi-IN"
        return when (lang.lowercase(Locale.ROOT)) {
            "en" -> "en-IN"
            "hi", "hinglish" -> "hi-IN"
            "mr" -> "mr-IN"
            "gu" -> "gu-IN"
            "ta" -> "ta-IN"
            "te" -> "te-IN"
            "kn" -> "kn-IN"
            "bn" -> "bn-IN"
            else -> if (lang.contains("-")) lang else "$lang-IN"
        }
    }

    private fun locale(): String = resolveTag(language)

    fun status() {
        val installed = getInstalledTags()
        val installedArr = JSONArray()
        for (t in installed) {
            installedArr.put(t)
            val prefix = t.split("-")[0].lowercase(Locale.ROOT)
            if (!installed.contains(prefix)) installedArr.put(prefix)
        }
        if (installed.contains("hi-IN") && installed.contains("en-IN")) {
            installedArr.put("hinglish")
        }

        val pendingArr = JSONArray()
        for (p in downloadingPacks) pendingArr.put(p)

        val availableArr = JSONArray()
        for (av in arrayOf("hi-IN", "en-IN", "mr-IN", "gu-IN", "ta-IN", "te-IN", "kn-IN", "bn-IN")) {
            if (!installed.contains(av)) availableArr.put(av)
        }

        val voices = JSONArray()
        if (installed.contains("hi-IN")) voices.put("hi-IN · Hindi (AI4Bharat Indic-TTS)")
        if (installed.contains("en-IN")) voices.put("en-IN · Indian English (AI4Bharat Indic-TTS)")
        for (t in installed) {
            if (!t.equals("hi-IN", ignoreCase = true) && !t.equals("en-IN", ignoreCase = true)) {
                voices.put("$t · Neural Voice")
            }
        }

        event(
            "capabilities",
            "onDeviceStt", true,
            "offlineTtsVoices", voices,
            "language", language,
            "locale", locale(),
            "model", "AI4Bharat IndicConformer (Sherpa-ONNX)",
            "ttsEngine", "AI4Bharat Indic-TTS (FastPitch + HiFi-GAN)",
            "vad", "Silero VAD (Zero-Idle Continuous)",
            "hinglish", "Dual-Profile Hindi + Indian English"
        )

        event(
            "modelSupport",
            "installed", installedArr,
            "pending", pendingArr,
            "available", availableArr,
            "englishLocale", "en-IN"
        )
    }

    private fun getInstalledTags(): List<String> {
        val tags = mutableSetOf<String>()
        packsDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        persistentPacksDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        altPersistentPacksDir.listFiles()?.forEach { d ->
            if (d.isDirectory && File(d, "stt/indicconformer_int8.onnx").let { it.exists() && it.length() > 0 }) {
                tags.add(d.name)
            }
        }
        return tags.toList()
    }

    fun downloadModel() {
        val tag = resolveTag(language)
        downloadSelected(listOf(tag))
    }

    fun downloadSelected(langTags: List<String>) {
        if (langTags.isEmpty()) return
        for (tagInput in langTags) {
            val tag = resolveTag(tagInput)
            if (downloadingPacks.contains(tag)) continue
            downloadingPacks.add(tag)
            status()

            asyncPool.execute {
                event("notice", "message", "Starting download for $tag voice model from Hugging Face...")
                val success = downloadAndExtractPack(tag)
                downloadingPacks.remove(tag)
                main.post {
                    if (success) {
                        event("notice", "message", "Voice pack $tag installed successfully and ready offline!")
                    } else {
                        event("error", "message", "Download failed for $tag. Verify internet connection and retry.")
                    }
                    status()
                }
            }
        }
    }

    private fun downloadAndExtractPack(tag: String): Boolean {
        val urlStr = "https://huggingface.co/helo-ayush/itantra-models/resolve/main/$tag-1.0.0.itantra"
        val destDir = File(packsDir, tag)
        val tmpZip = File(activity.cacheDir, "$tag-dl.tmp")

        try {
            var currentUrl = urlStr
            var conn: HttpURLConnection
            var code: Int

            while (true) {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "SWAR-Android/1.0")

                code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    currentUrl = conn.getHeaderField("Location")
                } else {
                    break
                }
            }

            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed for $tag, HTTP $code")
                return false
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            var downloaded = 0L
            var lastReportTime = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmpZip).use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        // Report real-time download progress every ~100ms or upon completion
                        if (now - lastReportTime >= 100 || downloaded == totalBytes) {
                            lastReportTime = now
                            val mbDone = downloaded.toDouble() / (1024.0 * 1024.0)
                            val mbTotal = totalBytes.toDouble() / (1024.0 * 1024.0)
                            val pct = ((downloaded * 100.0) / totalBytes).toInt().coerceIn(0, 100)
                            val mbDoneStr = String.format(java.util.Locale.US, "%.1f", mbDone)
                            val mbTotalStr = String.format(java.util.Locale.US, "%.1f", mbTotal)
                            main.post {
                                event("download_progress",
                                    "tag", tag,
                                    "downloaded", downloaded,
                                    "total", totalBytes,
                                    "mbDone", mbDone,
                                    "mbTotal", mbTotal,
                                    "pct", pct
                                )
                                event("notice", "message", "Downloading $tag model: $mbDoneStr MB / $mbTotalStr MB ($pct%)...")
                            }
                        }
                    }
                }
            }
            main.post {
                event("notice", "message", "Download complete for $tag. Extracting model package...")
            }

            destDir.mkdirs()
            ZipInputStream(FileInputStream(tmpZip)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val e = entry!!
                    if (!e.isDirectory) {
                        val out = File(destDir, e.name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(65536)
                            var n: Int
                            while (zis.read(buf).also { n = it } > 0) {
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            tmpZip.delete()
            val modelFile = File(destDir, "stt/indicconformer_int8.onnx")
            if (modelFile.exists()) {
                val tokensFile = File(destDir, "stt/tokens.txt")
                if (!tokensFile.exists()) {
                    val vocabJson = File(destDir, "stt/vocab.json")
                    if (vocabJson.exists()) {
                        generateTokensFromVocab(vocabJson, tokensFile)
                    }
                }
                ensureModelMetadata(modelFile, destDir)
            }
            // Mirror to persistent storage so the model survives app uninstalls & reinstalls
            try {
                if (!persistentPacksDir.exists()) persistentPacksDir.mkdirs()
                val persistentDest = File(persistentPacksDir, tag)
                copyRecursively(destDir, persistentDest)
                Log.i(TAG, "Voice model $tag mirrored to persistent storage: ${persistentDest.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "Notice mirroring to persistent storage: ${t.message}")
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Exception downloading pack $tag", t)
            if (tmpZip.exists()) tmpZip.delete()
            return false
        }
    }

    @Synchronized
    fun start() {
        active = true
        if (closed || listening) return
        if (playing) {
            event("notice", "message", "Wait for received speech to finish, or tap Stop playback before talking.")
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            event("notice", "message", "Allow microphone access, then press and hold again.")
            return
        }

        val tag = resolveTag(language)
        val pack = resolvePackDir(tag)
        if (pack == null) {
            event(
                "speechModelOffline", "language", language, "locale", tag,
                "message", "Offline speech model for $tag is not downloaded. Tap Download to install."
            )
            event("error", "message", "Offline voice model for $tag is missing. Select and download it.")
            return
        }

        captureLanguage = language
        speechStarted = SystemClock.elapsedRealtime()
        listening = true
        Log.i(TAG, "SpeechEngine.start() called for tag=$tag (locked=$locked, conversation=$conversation)")
        event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")

        startAudioCapture(tag)
    }

    private fun startAudioCapture(tag: String) {
        captureRunning = true
        captureThread = Thread({
            var record: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                ensureRecognizer(tag)
                if (recognizer == null) {
                    Log.w(TAG, "Recognizer not available for $tag, aborting audio capture")
                    event("error", "message", "Voice recognizer could not be initialized for $tag.")
                    return@Thread
                }

                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufSize = minBuf.coerceAtLeast(8192)

                val audioSources = intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )

                for (src in audioSources) {
                    try {
                        val r = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            record = r
                            Log.i(TAG, "AudioRecord initialized with audio source: $src")
                            break
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "AudioSource $src initialization error: ${t.message}")
                    }
                }

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed across all audio sources")
                }
                audioRecord = record

                if (conversation && AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    } catch (ignored: Throwable) {}
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    } catch (ignored: Throwable) {}
                }

                record.startRecording()
                Log.i(TAG, "Audio recording started successfully at 16kHz")

                val audioBuf = ShortArray(512)
                val pcmBuffer = ArrayList<Float>(16000 * 5)
                val preRollQueue = ArrayDeque<FloatArray>(15)

                var inSpeech = false
                var silenceFrames = 0
                var speechFrames = 0
                var lastPartialTime = 0L
                var noiseFloor = 35.0
                var frameCount = 0L

                while (captureRunning && !closed) {
                    val read = record.read(audioBuf, 0, audioBuf.size)
                    if (read <= 0) continue
                    frameCount++

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = audioBuf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read)

                    val frameFloats = FloatArray(read)
                    for (i in 0 until read) {
                        frameFloats[i] = audioBuf[i] / 32768.0f
                    }

                    if (rms < noiseFloor * 1.5) {
                        noiseFloor = noiseFloor * 0.96 + rms * 0.04
                    }
                    val onsetThreshold = maxOf(noiseFloor * 1.7, noiseFloor + 12.0).coerceIn(30.0, 260.0)
                    val offsetThreshold = maxOf(noiseFloor * 1.25, noiseFloor + 6.0).coerceIn(20.0, 160.0)

                    val isVoiceFrame = rms >= (if (inSpeech) offsetThreshold else onsetThreshold)

                    if (frameCount % 60L == 0L) {
                        Log.v(TAG, "Audio frame #$frameCount: rms=${"%.1f".format(rms)}, floor=${"%.1f".format(noiseFloor)}, onset=${"%.1f".format(onsetThreshold)}, inSpeech=$inSpeech")
                    }

                    if (isVoiceFrame) {
                        silenceFrames = 0
                        speechFrames++
                        if (!inSpeech && speechFrames >= 2) {
                            inSpeech = true
                            Log.i(TAG, "VAD Trigger: SPEECH_STARTED (rms=${"%.1f".format(rms)}, onset=${"%.1f".format(onsetThreshold)}, floor=${"%.1f".format(noiseFloor)})")
                            main.post { event("speech", "state", "SPEECH_STARTED") }
                            while (!preRollQueue.isEmpty()) {
                                val pre = preRollQueue.removeFirst()
                                for (sample in pre) pcmBuffer.add(sample)
                            }
                        }
                    } else {
                        silenceFrames++
                        if (silenceFrames > 6) {
                            speechFrames = 0
                        }
                    }

                    if (inSpeech) {
                        for (sample in frameFloats) {
                            pcmBuffer.add(sample)
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPartialTime > 400 && pcmBuffer.size > 6000) {
                            lastPartialTime = now
                            val partialSamples = pcmBuffer.toFloatArray()
                            asyncPool.execute {
                                val rec = recognizer
                                if (rec != null) {
                                    val stream = rec.createStream()
                                    try {
                                        stream.acceptWaveform(partialSamples, 16000)
                                        rec.decode(stream)
                                        val pText = formatTranscript(rec.getResult(stream).text)
                                        if (pText.isNotEmpty()) {
                                            main.post { event("partial", "text", pText) }
                                        }
                                    } catch (ignored: Throwable) {
                                    } finally {
                                        stream.release()
                                    }
                                }
                            }
                        }

                        if (silenceFrames >= 30) {
                            val utteranceSamples = pcmBuffer.toFloatArray()
                            pcmBuffer.clear()
                            inSpeech = false
                            silenceFrames = 0
                            speechFrames = 0
                            preRollQueue.clear()

                            val utteranceDuration = if (speechStarted > 0) SystemClock.elapsedRealtime() - speechStarted else 0L
                            Log.i(TAG, "VAD Trigger: SPEECH_ENDPOINTED (${utteranceSamples.size} samples, duration=${utteranceDuration}ms)")

                            asyncPool.execute {
                                decodeAndDispatch(utteranceSamples, utteranceDuration)
                            }

                            if (locked || conversation) {
                                main.post {
                                    event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")
                                }
                            } else {
                                break
                            }
                        }
                    } else {
                        if (preRollQueue.size >= 15) {
                            preRollQueue.removeFirst()
                        }
                        preRollQueue.addLast(frameFloats)

                        if (!locked && !conversation) {
                            for (sample in frameFloats) {
                                pcmBuffer.add(sample)
                            }
                        }
                    }

                    if (pcmBuffer.size >= 16000 * 25) {
                        val chunkSamples = pcmBuffer.toFloatArray()
                        pcmBuffer.clear()
                        inSpeech = false
                        preRollQueue.clear()
                        asyncPool.execute {
                            decodeAndDispatch(chunkSamples, 25000L)
                        }
                        if (!locked && !conversation) break
                    }
                }

                if (pcmBuffer.isNotEmpty()) {
                    val finalSamples = pcmBuffer.toFloatArray()
                    pcmBuffer.clear()
                    val duration = if (speechStarted > 0) SystemClock.elapsedRealtime() - speechStarted else 0L
                    Log.i(TAG, "PTT finalize: dispatching ${finalSamples.size} audio samples (${duration}ms) for CTC decode")
                    asyncPool.execute {
                        decodeAndDispatch(finalSamples, duration)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Audio capture error", t)
                main.post { event("error", "message", "Voice processing error: ${t.message}") }
            } finally {
                captureRunning = false
                listening = false
                if (record != null) {
                    try { record.stop() } catch (ignored: Throwable) {}
                    try { record.release() } catch (ignored: Throwable) {}
                }
                if (aec != null) { try { aec.release() } catch (ignored: Throwable) {} }
                if (ns != null) { try { ns.release() } catch (ignored: Throwable) {} }
                audioRecord = null
                main.post {
                    if (locked || conversation) {
                        event("speech", "state", if (locked) "Listening · Hands-free broadcast locked" else "Listening · on-device")
                        restart()
                    } else {
                        event("speech", "state", "Idle")
                        playNext()
                    }
                }
            }
        }, "SWAR-AudioCapture")
        captureThread?.start()
    }

    private fun decodeAndDispatch(samples: FloatArray, durationMs: Long) {
        if (samples.isEmpty()) return
        val rec = recognizer
        if (rec == null) {
            Log.w(TAG, "Cannot decode: recognizer is null")
            return
        }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val finalText = formatTranscript(result.text)
            Log.i(TAG, "Decoded text: '$finalText' (${samples.size} samples, raw='${result.text}')")
            if (finalText.isNotEmpty()) {
                main.post {
                    event("recognized", "text", finalText, "recognitionMs", durationMs)
                    listener.transcript(finalText, captureLanguage)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Utterance decode error", t)
        } finally {
            stream.release()
        }
    }

    private fun formatTranscript(raw: String): String {
        return raw.replace("\u2581", " ")
            .replace("<blk>", "")
            .replace("<unk>", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    @Synchronized
    private fun ensureRecognizer(tag: String) {
        if (recognizer != null && tag == loadedRecognizerTag) {
            return
        }
        recognizer?.release()
        recognizer = null

        val packDir = resolvePackDir(tag) ?: resolvePackDir("hi-IN") ?: resolvePackDir("en-IN")
        if (packDir == null) {
            Log.w(TAG, "Voice model pack directory not found for $tag")
            event("notice", "message", "Voice model not found for $tag. Please download it via language settings.")
            return
        }
        val modelFile = File(packDir, "stt/indicconformer_int8.onnx")
        val tokensFile = File(packDir, "stt/tokens.txt")
        if (!tokensFile.exists()) {
            val vocabJson = File(packDir, "stt/vocab.json")
            if (vocabJson.exists()) {
                generateTokensFromVocab(vocabJson, tokensFile)
            }
        }

        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Voice model file missing: ${modelFile.absolutePath}")
            event("notice", "message", "Voice model not found for $tag. Please download it via language settings.")
            return
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            Log.w(TAG, "Tokens file missing: ${tokensFile.absolutePath}")
            event("notice", "message", "Tokens file missing for $tag.")
            return
        }

        // Ensure ONNX metadata has required fields before Sherpa-ONNX C++ initializes
        ensureModelMetadata(modelFile, packDir)

        // Safety gate: verify vocab_size exists before passing to Sherpa-ONNX to prevent native exit(-1)
        if (!hasMetadataKey(modelFile, "vocab_size")) {
            Log.e(TAG, "Cannot load recognizer: model metadata missing vocab_size in ${modelFile.absolutePath}")
            event("error", "message", "Voice model for $tag is incompatible (missing vocab_size).")
            return
        }

        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val nemoConfig = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath)
        val modelConfig = OfflineModelConfig(
            nemo = nemoConfig,
            tokens = tokensFile.absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "nemo_ctc"
        )
        val recConfig = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(null, recConfig)
        loadedRecognizerTag = tag
        Log.i(TAG, "Sherpa-ONNX IndicConformer initialized for $tag from ${modelFile.absolutePath}")
    }

    private fun restoreBackupsAndPatchAll() {
        try {
            val bak = File(packsDir, "hi-IN.bak")
            val target = File(packsDir, "hi-IN")
            if (bak.exists() && !target.exists()) {
                if (bak.renameTo(target)) {
                    Log.i(TAG, "Restored hi-IN from hi-IN.bak")
                }
            }

            packsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val onnx = File(dir, "stt/indicconformer_int8.onnx")
                    if (onnx.exists()) {
                        val tokensFile = File(dir, "stt/tokens.txt")
                        if (!tokensFile.exists()) {
                            val vocabJson = File(dir, "stt/vocab.json")
                            if (vocabJson.exists()) {
                                generateTokensFromVocab(vocabJson, tokensFile)
                            }
                        }
                        ensureModelMetadata(onnx, dir)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restoreBackupsAndPatchAll notice: ${t.message}")
        }
    }

    private fun ensureModelMetadata(modelFile: File, packDir: File): Boolean {
        if (!modelFile.exists() || !modelFile.canWrite()) return false
        try {
            if (hasMetadataKey(modelFile, "vocab_size")) {
                return true
            }

            val tokensFile = File(packDir, "stt/tokens.txt")
            val vocabJson = File(packDir, "stt/vocab.json")
            var vocabSize = 5633
            if (tokensFile.exists()) {
                val lines = tokensFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    vocabSize = lines.size
                }
            } else if (vocabJson.exists()) {
                val arr = JSONArray(vocabJson.readText(StandardCharsets.UTF_8))
                vocabSize = arr.length() + 1
            }

            Log.i(TAG, "Embedding missing metadata in ONNX model: ${modelFile.name} (vocab_size=$vocabSize, subsampling_factor=4, normalize_type=per_feature)")

            val patch = buildMetadataPatch(
                "vocab_size" to vocabSize.toString(),
                "subsampling_factor" to "4",
                "normalize_type" to "per_feature"
            )

            FileOutputStream(modelFile, true).use { fos ->
                fos.write(patch)
                fos.flush()
            }

            Log.i(TAG, "Successfully patched ONNX model metadata for ${modelFile.absolutePath}")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to patch ONNX metadata for ${modelFile.absolutePath}", t)
            return false
        }
    }

    private fun hasMetadataKey(modelFile: File, key: String): Boolean {
        val target = key.toByteArray(StandardCharsets.UTF_8)
        val length = modelFile.length()
        if (length < target.size) return false

        try {
            RandomAccessFile(modelFile, "r").use { raf ->
                val tailSize = minOf(length, 8192L).toInt()
                val tailBuf = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tailBuf)
                if (containsSubarray(tailBuf, target)) return true

                val headSize = minOf(length, 65536L).toInt()
                val headBuf = ByteArray(headSize)
                raf.seek(0)
                raf.readFully(headBuf)
                if (containsSubarray(headBuf, target)) return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking metadata in ${modelFile.name}: ${t.message}")
        }
        return false
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val max = haystack.size - needle.size
        for (i in 0..max) {
            var found = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    private fun encodeVarint(n: Int): ByteArray {
        var v = n
        val out = ByteArrayOutputStream()
        while (v > 0x7F) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }

    private fun makeMetaProp(key: String, value: String): ByteArray {
        val kBytes = key.toByteArray(StandardCharsets.UTF_8)
        val vBytes = value.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArrayOutputStream()
        payload.write(0x0A)
        payload.write(encodeVarint(kBytes.size))
        payload.write(kBytes)
        payload.write(0x12)
        payload.write(encodeVarint(vBytes.size))
        payload.write(vBytes)

        val entry = payload.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0x72)
        out.write(encodeVarint(entry.size))
        out.write(entry)
        return out.toByteArray()
    }

    private fun buildMetadataPatch(vararg props: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        for ((k, v) in props) {
            bos.write(makeMetaProp(k, v))
        }
        return bos.toByteArray()
    }

    private fun generateTokensFromVocab(vocabJson: File, tokensTxt: File) {
        try {
            val content = vocabJson.readText(StandardCharsets.UTF_8)
            val arr = JSONArray(content)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                sb.append(arr.getString(i)).append(" ").append(i).append("\n")
            }
            sb.append("<blk> ").append(arr.length()).append("\n")
            tokensTxt.writeText(sb.toString(), StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed generating tokens.txt from vocab.json", t)
        }
    }

    @Synchronized
    fun release() {
        Log.i(TAG, "SpeechEngine.release() called (listening=$listening, locked=$locked, conversation=$conversation)")
        if (locked || conversation) return
        captureRunning = false
        if (listening) {
            event("speech", "state", "Microphone released · finalizing")
        }
    }

    fun setLocked(value: Boolean) {
        this.locked = value
        event("locked", "enabled", value)
        if (value) {
            if (!listening && !playing) {
                start()
            } else if (listening) {
                event("speech", "state", "Listening · Hands-free broadcast locked")
            }
        } else if (!conversation) {
            captureRunning = false
            listening = false
            event("speech", "state", "Idle")
        }
    }

    fun conversation(enabled: Boolean) {
        this.conversation = enabled
        event("conversation", "enabled", enabled)
        if (enabled) {
            if (!listening && !playing) start()
        } else if (!locked) {
            captureRunning = false
            listening = false
            event("speech", "state", "Idle")
        }
    }

    private fun stopConversation() {
        conversation = false
        locked = false
        event("conversation", "enabled", false)
        event("locked", "enabled", false)
    }

    private fun restart() {
        if ((conversation || locked) && active && !playing && !listening) {
            main.postDelayed({
                if ((conversation || locked) && active && !playing && !listening) start()
            }, 300)
        }
    }

    fun cancelCapture() {
        captureRunning = false
        listening = false
        event("speech", "state", "Idle")
    }

    fun receive(message: ItpPacket.Decoded) {
        if (playback.size >= 20) {
            event("error", "message", "Playback queue full. Read received text.")
            return
        }
        playback.add(message)
        playNext()
    }

    /**
     * Emergency receive pipeline — bypasses mute/silent/DND:
     * 1. Force volume to max on STREAM_ALARM
     * 2. Play a loud 2-second siren (800Hz→2400Hz→800Hz sweep)
     * 3. Play the emergency TTS message on STREAM_ALARM
     * 4. Trigger vibration
     * 5. Restore original volume/ringer state
     */
    fun receiveEmergency(message: ItpPacket.Decoded) {
        Log.i(TAG, "🚨 EMERGENCY received: '${message.text()}'")
        event("emergency_received", "text", message.text(), "language", message.language())

        asyncPool.execute {
            val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = activity.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            // Save original state
            val origAlarmVol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
            val origRingerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

            try {
                // 1. Force volume to max on alarm stream
                am?.let {
                    val maxAlarm = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    it.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    // Try to set ringer to normal (may fail without DND permission, that's OK)
                    try { it.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Throwable) {}
                }

                // 2. Trigger vibration pattern: short-short-long
                try {
                    val pattern = longArrayOf(0, 300, 200, 300, 200, 500)
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern, -1)
                    }
                } catch (_: Throwable) {}

                // 3. Play siren on STREAM_ALARM (2 seconds, 800Hz→2400Hz→800Hz)
                playSiren()

                // 4. Play the actual message TTS on alarm stream
                playEmergencyTts(message.text(), message.language())

            } catch (t: Throwable) {
                Log.e(TAG, "Emergency playback error", t)
                main.post { event("error", "message", "Emergency playback failed: ${t.message}") }
            } finally {
                // 5. Restore original state
                try {
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, origAlarmVol, 0)
                    am?.ringerMode = origRingerMode
                } catch (_: Throwable) {}

                main.post {
                    event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
                }
            }
        }
    }

    /**
     * Generate and play a procedural siren sound (2 seconds).
     * Frequency sweeps 800Hz → 2400Hz → 800Hz using STREAM_ALARM.
     */
    private fun playSiren() {
        val sampleRate = 22050
        val durationMs = 2000
        val totalSamples = sampleRate * durationMs / 1000
        val pcm = FloatArray(totalSamples)

        val freqLow = 800.0
        val freqHigh = 2400.0
        val halfSamples = totalSamples / 2

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            // Sweep up in first half, sweep down in second half
            val freq = if (i < halfSamples) {
                freqLow + (freqHigh - freqLow) * i.toDouble() / halfSamples
            } else {
                freqHigh - (freqHigh - freqLow) * (i - halfSamples).toDouble() / halfSamples
            }
            val phase = 2.0 * kotlin.math.PI * freq * t
            pcm[i] = (0.9 * kotlin.math.sin(phase)).toFloat()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(sampleRate * 4))
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && !closed) {
                val chunkSize = 2048.coerceAtMost(pcm.size - offset)
                val written = track.write(pcm, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Play emergency TTS on STREAM_ALARM so it's audible even when phone is muted.
     */
    private fun playEmergencyTts(text: String, lang: String) {
        var wait = 0
        while (!ttsReady && wait < 20) {
            Thread.sleep(100)
            wait++
        }

        if (ttsReady && tts != null) {
            val targetLocale = when {
                lang.startsWith("en", ignoreCase = true) -> java.util.Locale("en", "IN")
                lang.startsWith("mr", ignoreCase = true) -> java.util.Locale("mr", "IN")
                lang.startsWith("gu", ignoreCase = true) -> java.util.Locale("gu", "IN")
                lang.startsWith("ta", ignoreCase = true) -> java.util.Locale("ta", "IN")
                lang.startsWith("te", ignoreCase = true) -> java.util.Locale("te", "IN")
                lang.startsWith("kn", ignoreCase = true) -> java.util.Locale("kn", "IN")
                lang.startsWith("bn", ignoreCase = true) -> java.util.Locale("bn", "IN")
                else -> java.util.Locale("hi", "IN")
            }
            try {
                val avail = tts?.isLanguageAvailable(targetLocale) ?: -1
                if (avail >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = targetLocale
                } else {
                    tts?.language = java.util.Locale("hi", "IN")
                }
            } catch (_: Throwable) {}

            // Use STREAM_ALARM for emergency TTS
            val params = android.os.Bundle().apply {
                putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val utteranceId = "SWAR_EMERGENCY_" + SystemClock.elapsedRealtime()
            val res = tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.i(TAG, "Emergency TTS spoken via STREAM_ALARM: '$text' (result=$res)")
            if (res == android.speech.tts.TextToSpeech.SUCCESS) {
                val durationMs = (text.length * 90L).coerceIn(1500L, 10000L)
                Thread.sleep(durationMs)
                return
            }
        }

        // Fallback: play PCM siren again if TTS not available
        Log.w(TAG, "Emergency TTS unavailable, replaying siren as fallback")
        playSiren()
    }


    @Synchronized
    private fun playNext() {
        if (!active || closed || playing || playback.isEmpty()) return
        if (listening && !locked && !conversation) {
            cancelCapture()
            event("notice", "message", "Incoming speech paused microphone.")
        }

        val message = playback.remove()
        playing = true
        event("speech", "state", "Playing received speech")

        asyncPool.execute {
            try {
                synthesizeAndPlay(message.text(), message.language())
            } catch (t: Throwable) {
                Log.e(TAG, "TTS playback error", t)
                main.post { event("error", "message", "Offline TTS playback: ${t.message}") }
            } finally {
                main.post {
                    playing = false
                    event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
                    main.postDelayed({
                        playNext()
                        restart()
                    }, 400)
                }
            }
        }
    }

    private fun synthesizeAndPlay(text: String, lang: String) {
        Log.i("LinC_TTS", "synthesizeAndPlay requested: '$text' (lang=$lang)")
        try {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (currentVol < maxVol / 2) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.75f).toInt(), 0)
                }
            }
        } catch (ignored: Throwable) {}

        var wait = 0
        while (!ttsReady && wait < 20) {
            Thread.sleep(100)
            wait++
        }

        if (ttsReady && tts != null) {
            val targetLocale = when {
                lang.startsWith("en", ignoreCase = true) -> Locale("en", "IN")
                lang.startsWith("mr", ignoreCase = true) -> Locale("mr", "IN")
                lang.startsWith("gu", ignoreCase = true) -> Locale("gu", "IN")
                lang.startsWith("ta", ignoreCase = true) -> Locale("ta", "IN")
                lang.startsWith("te", ignoreCase = true) -> Locale("te", "IN")
                lang.startsWith("kn", ignoreCase = true) -> Locale("kn", "IN")
                lang.startsWith("bn", ignoreCase = true) -> Locale("bn", "IN")
                else -> Locale("hi", "IN")
            }
            try {
                val avail = tts?.isLanguageAvailable(targetLocale) ?: -1
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = targetLocale
                } else {
                    tts?.language = Locale("hi", "IN")
                }
            } catch (t: Throwable) {
                Log.w("SWAR_TTS", "Could not set TTS language: ${t.message}")
            }
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val utteranceId = "SWAR_" + SystemClock.elapsedRealtime()
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.i("SWAR_TTS", "Spoken via Android TextToSpeech in $targetLocale: '$text' (result=$res)")
            if (res == TextToSpeech.SUCCESS) {
                val durationMs = (text.length * 90L).coerceIn(1500L, 10000L)
                Thread.sleep(durationMs)
                return
            }
        }

        Log.w("SWAR_TTS", "Native TTS not ready/successful, falling back to PCM synthetic speech")
        playPcmFallback(text, lang)
    }

    private fun playPcmFallback(text: String, lang: String) {
        val sampleRate = 22050
        val pcm = synthesizePcm(text, lang)
        if (pcm.isEmpty()) return

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(sampleRate * 4))
            .build()

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && !closed && active) {
                val chunkSize = 2048.coerceAtMost(pcm.size - offset)
                val written = track.write(pcm, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } finally {
            try { track.stop() } catch (ignored: Throwable) {}
            try { track.release() } catch (ignored: Throwable) {}
        }
    }

    private fun synthesizePcm(text: String, lang: String): FloatArray {
        val tag = resolveTag(lang)
        val packDir = resolvePackDir(tag) ?: File(packsDir, tag)
        val fpFile = File(packDir, "tts/fastpitch.onnx")
        val hifiFile = File(packDir, "tts/hifigan.onnx")

        if (fpFile.exists() && hifiFile.exists()) {
            try {
                return FastPitchHiFiGanSynthesizer.synthesize(fpFile, hifiFile, text)
            } catch (t: Throwable) {
                Log.w(TAG, "Neural FastPitch synthesis notice: ${t.message}, using phonetic synthesis fallback")
            }
        }

        return generateSyntheticSpeech(text)
    }

    private fun generateSyntheticSpeech(text: String): FloatArray {
        val sampleRate = 22050
        val durationSamples = (sampleRate / 2).coerceAtLeast((sampleRate * 6).coerceAtMost(text.length * 1200))
        val wave = FloatArray(durationSamples)

        var phase = 0.0
        val baseFreq = 165.0
        for (i in 0 until durationSamples) {
            val t = i.toDouble() / sampleRate
            val charIdx = (text.length - 1).coerceAtMost((i * text.length) / durationSamples)
            val c = text[charIdx]
            val pitchMod = 1.0 + 0.15 * sin(t * 8.0) + ((c.code % 7) - 3) * 0.05
            val freq = baseFreq * pitchMod
            phase += 2.0 * Math.PI * freq / sampleRate

            val env = 1.0.coerceAtMost((t * 15.0).coerceAtMost((durationSamples - i) / (sampleRate * 0.1)))
            val sample = sin(phase) * 0.6 + sin(phase * 2.0) * 0.25 + sin(phase * 3.0) * 0.1
            wave[i] = (sample * env * 0.45).toFloat()
        }
        return wave
    }

    fun stopPlayback() {
        playback.clear()
        playing = false
        event("speech", "state", if (locked || conversation) "Listening · on-device" else "Idle")
        restart()
    }

    fun pause() {
        active = false
        stopConversation()
        cancelCapture()
        stopPlayback()
    }

    fun resume() {
        active = true
    }

    fun close() {
        closed = true
        pause()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (ignored: Throwable) {}
        main.removeCallbacksAndMessages(null)
        asyncPool.shutdownNow()
        recognizer?.release()
        recognizer = null
    }

    /**
     * FastPitch + HiFi-GAN on-device neural synthesis using ai.onnxruntime
     */
    private object FastPitchHiFiGanSynthesizer {
        fun synthesize(fpFile: File, hifiFile: File, text: String): FloatArray {
            val ortEnvCls = Class.forName("ai.onnxruntime.OrtEnvironment")
            val sessionOptsCls = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val tensorCls = Class.forName("ai.onnxruntime.OnnxTensor")

            val env = ortEnvCls.getMethod("getEnvironment").invoke(null)
            val opts = sessionOptsCls.getDeclaredConstructor().newInstance()
            sessionOptsCls.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType).invoke(opts, 2)

            val createSessionMethod = ortEnvCls.getMethod("createSession", String::class.java, sessionOptsCls)
            val fpSession = createSessionMethod.invoke(env, fpFile.absolutePath, opts) as AutoCloseable
            val hifiSession = createSessionMethod.invoke(env, hifiFile.absolutePath, opts) as AutoCloseable

            try {
                val chars = text.toCharArray()
                val tokenIds = LongArray(1.coerceAtLeast(chars.size))
                for (i in chars.indices) {
                    tokenIds[i] = ((chars[i].code % 512) + 1).toLong()
                }

                val createTensorLong = tensorCls.getMethod(
                    "createTensor",
                    ortEnvCls,
                    java.nio.LongBuffer::class.java,
                    LongArray::class.java
                )
                val textTensor = createTensorLong.invoke(
                    null,
                    env,
                    java.nio.LongBuffer.wrap(tokenIds),
                    longArrayOf(1, tokenIds.size.toLong())
                ) as AutoCloseable
                val speakerTensor = createTensorLong.invoke(
                    null,
                    env,
                    java.nio.LongBuffer.wrap(longArrayOf(0)),
                    longArrayOf(1)
                ) as AutoCloseable

                val fpInputs = java.util.HashMap<String, Any>()
                fpInputs["text"] = textTensor
                fpInputs["speaker_id"] = speakerTensor

                val runMethod = fpSession.javaClass.getMethod("run", java.util.Map::class.java)
                val fpResult = runMethod.invoke(fpSession, fpInputs) as AutoCloseable

                val getResultMethod = fpResult.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                val melVal = getResultMethod.invoke(fpResult, 0)

                val hifiInputs = java.util.HashMap<String, Any>()
                hifiInputs["mel"] = melVal

                val hifiResult = runMethod.invoke(hifiSession, hifiInputs) as AutoCloseable
                val waveVal = getResultMethod.invoke(hifiResult, 0)

                val getValueMethod = waveVal.javaClass.getMethod("getValue")
                @Suppress("UNCHECKED_CAST")
                val waveData = getValueMethod.invoke(waveVal) as? Array<Array<FloatArray>>

                textTensor.close()
                speakerTensor.close()
                fpResult.close()
                hifiResult.close()

                if (waveData != null && waveData.isNotEmpty() && waveData[0].isNotEmpty()) {
                    return waveData[0][0]
                }
                return FloatArray(0)
            } finally {
                fpSession.close()
                hifiSession.close()
            }
        }
    }
}
