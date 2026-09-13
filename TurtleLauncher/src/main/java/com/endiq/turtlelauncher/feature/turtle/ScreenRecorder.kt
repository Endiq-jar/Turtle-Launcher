package com.endiq.turtlelauncher.feature.turtle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.TaskExecutors
import net.kdt.pojavlaunch.services.ScreenRecorderAudioService
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TurtleLauncher: in-game screen recording (roadmap item 22 - "controls shown live, hidden in
 * the final video").
 *
 * How the "hidden in the video" part actually works: this records ONLY the Minecraft render
 * surface (the SurfaceView or TextureView MinecraftGLSurface hands off to the native
 * renderer - see that class's start()), never the Activity's full window. The touch
 * controls/HUD/record button are separate sibling Views layered on top of that surface in the
 * same window, added by ControlLayout/GameMenuViewWrapper. PixelCopy(SurfaceView) and
 * TextureView.getBitmap() each only ever read the buffer belonging to that one View - they
 * have no way to see sibling views even if they wanted to. So "controls visible on screen, gone
 * from the recording" isn't a filter being applied - it's a direct consequence of which View's
 * pixels are being read, the same property ScreenshotHelper already relies on for the SurfaceView
 * case. This is a real, standard Android technique (not MediaProjection, which mirrors the whole
 * physical display compositor output including overlay windows and can't selectively exclude a
 * View - there's no public API for that).
 *
 * Frame delivery: a background thread requests a frame (PixelCopy or getBitmap) on a timer at
 * the configured frame rate, draws each bitmap onto the MediaCodec encoder's input Surface via
 * Canvas, and periodically drains encoder output into a MediaMuxer writing an .mp4. This is a
 * software blit per frame rather than a zero-copy GPU path (which would mean hooking into
 * MinecraftGLSurface's native EGL rendering directly - a much larger, riskier change to code
 * this project can't rebuild/test on real hardware from here) - fine for a gameplay-recording
 * feature at moderate resolutions/frame rates, not intended to compete with a dedicated
 * screen-recorder app's encoding pipeline.
 *
 * Audio: captured via AudioPlaybackCaptureConfiguration (Android 10+), which requires a
 * MediaProjection token - NOT used to mirror the display (this class still never does that, see
 * above), only to authorize reading this app's own game/media-usage audio output. Getting that
 * token means showing Android's system screen-capture consent dialog once per recording (no way
 * around that - it's the same underlying API screen recorders use, and it's the only public,
 * non-root way to capture another app's audio output without recording the microphone). If the
 * user declines, or capture setup fails for any reason, recording proceeds video-only exactly as
 * before this existed - audio is strictly best-effort and never blocks the video path. Turning
 * off Settings -> Recording -> Capture Audio skips the consent prompt entirely.
 *
 * Caveat worth remembering: AudioPlaybackCaptureConfiguration only captures audio actually
 * tagged with a matching AudioAttributes usage (here: USAGE_GAME, USAGE_MEDIA, USAGE_UNKNOWN -
 * matched broadly since which usage OpenAL-soft's Android AudioTrack backend tags its output
 * with isn't something this sandbox can verify without a real device/build). If the bundled
 * OpenAL-soft ends up using a usage outside that set, or an app is inside a DRM/opt-out
 * capture-policy boundary (not applicable to MC's own audio), the recorded video could still
 * come out silent even with permission granted and no error reported - that's a capture-source
 * question, not a bug in the plumbing here, and would need an on-device recording to confirm.
 *
 * Also requires running net.kdt.pojavlaunch.services.ScreenRecorderAudioService as a
 * foregroundServiceType="mediaProjection" service for the lifetime of the MediaProjection -
 * mandatory since targetSdk 34/Android 14 for any use of a MediaProjection instance, audio-only
 * or not. See that class's doc for details.
 */
object ScreenRecorder {
    private const val TAG = "ScreenRecorder"
    private const val MIME_TYPE = "video/avc" // H.264 - broadest device/player compatibility
    private const val AUDIO_MIME_TYPE = "audio/mp4a-latm" // AAC-LC
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_COUNT = 2
    private const val AUDIO_BIT_RATE = 128_000

    /** Request code for the MediaProjection consent activity, handled in onActivityResult(). */
    const val REQUEST_CODE_AUDIO_CAPTURE = 8422

    private val nameFormat = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US)

    private val isRecordingFlag = AtomicBoolean(false)
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var muxerStarted = false
    private val muxerLock = Any()
    private var videoTrackIndex = -1
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private var addedTrackCount = 0
    private var expectedTrackCount = 1

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var frameBitmap: Bitmap? = null
    private var startTimeNanos = 0L
    private var frameIntervalMs = 1000L / 30L
    private var outputFile: File? = null
    private var targetSurfaceView: SurfaceView? = null
    private var targetTextureView: TextureView? = null
    private var maxDurationMs = 0L

    // Audio capture state - all null/unused whenever recording video-only.
    private var mediaProjection: MediaProjection? = null
    private var audioServiceContext: Context? = null
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioTrackIndex = -1
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private var audioCaptureThread: HandlerThread? = null
    private var audioCaptureHandler: Handler? = null
    private var pendingConsentActivity: WeakReference<Activity>? = null

    fun isRecording(): Boolean = isRecordingFlag.get()

    /** @return elapsed recording time in whole seconds, or 0 if not currently recording. */
    fun getElapsedSeconds(): Long {
        if (!isRecordingFlag.get() || startTimeNanos == 0L) return 0L
        return (System.nanoTime() - startTimeNanos) / 1_000_000_000L
    }

    fun toggle(activity: Activity) {
        if (isRecordingFlag.get()) stop(activity) else start(activity)
    }

    /**
     * Public entry point. If audio capture is enabled in settings and available on this API
     * level, this launches the MediaProjection consent dialog first and actually starts
     * recording from onActivityResult() once that returns - the caller (a click listener) does
     * not need to know about this indirection, same as before audio existed.
     */
    fun start(activity: Activity) {
        if (isRecordingFlag.get()) return

        val wantsAudio = AllSettings.recordingCaptureAudio.getValue() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (wantsAudio) {
            val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as? MediaProjectionManager
            if (projectionManager != null) {
                try {
                    pendingConsentActivity = WeakReference(activity)
                    activity.startActivityForResult(
                        projectionManager.createScreenCaptureIntent(),
                        REQUEST_CODE_AUDIO_CAPTURE
                    )
                    return
                } catch (t: Throwable) {
                    Logging.e(TAG, "Couldn't request audio-capture consent, recording video-only", t)
                    pendingConsentActivity = null
                }
            }
        }
        startInternal(activity, null)
    }

    /**
     * Must be called from the host Activity's onActivityResult() for REQUEST_CODE_AUDIO_CAPTURE.
     * Resolves the consent result and starts recording either way - with audio on RESULT_OK, or
     * video-only on denial/failure. Best-effort throughout: a problem obtaining the
     * MediaProjection never blocks the recording itself from starting.
     */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_AUDIO_CAPTURE) return

        val target = pendingConsentActivity?.get() ?: activity
        pendingConsentActivity = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                val projectionManager = target.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                // Must be running before the MediaProjection is actually used - see
                // ScreenRecorderAudioService's class doc.
                ScreenRecorderAudioService.start(target.applicationContext)
                val projection = projectionManager.getMediaProjection(resultCode, data)
                startInternal(target, projection)
                return
            } catch (t: Throwable) {
                Logging.e(TAG, "Failed to obtain MediaProjection, recording video-only", t)
                ScreenRecorderAudioService.stop(target.applicationContext)
            }
        } else {
            Toast.makeText(target, "Audio capture declined - recording video only", Toast.LENGTH_SHORT).show()
        }
        startInternal(target, null)
    }

    private fun startInternal(activity: Activity, projection: MediaProjection?) {
        if (isRecordingFlag.get()) {
            // A second consent round-trip could in theory land here after another start() already
            // went through video-only in the meantime - don't leak an unused projection.
            try { projection?.stop() } catch (t: Throwable) { Logging.e(TAG, "projection.stop() failed", t) }
            if (projection != null) ScreenRecorderAudioService.stop(activity.applicationContext)
            return
        }

        val renderSurfaceView = findRenderSurface(activity.window.decorView)
        if (renderSurfaceView == null) {
            Toast.makeText(activity, "Recording unavailable - couldn't locate the game surface", Toast.LENGTH_SHORT).show()
            try { projection?.stop() } catch (t: Throwable) { Logging.e(TAG, "projection.stop() failed", t) }
            if (projection != null) ScreenRecorderAudioService.stop(activity.applicationContext)
            return
        }

        val width = evenify(scaleDimension(renderSurfaceView.width))
        val height = evenify(scaleDimension(renderSurfaceView.height))
        if (width <= 0 || height <= 0) {
            Toast.makeText(activity, "Recording unavailable - game surface isn't ready yet", Toast.LENGTH_SHORT).show()
            try { projection?.stop() } catch (t: Throwable) { Logging.e(TAG, "projection.stop() failed", t) }
            if (projection != null) ScreenRecorderAudioService.stop(activity.applicationContext)
            return
        }

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, AllSettings.recordingBitrateMbps.getValue().coerceIn(2, 20) * 1_000_000)
                val fps = AllSettings.recordingFrameRate.getValue().coerceIn(15, 60)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                frameIntervalMs = 1000L / fps.toLong()
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "recordings").apply { mkdirs() }
            val file = File(dir, "turtle_${nameFormat.format(Date())}.mp4")
            val mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            encoder = codec
            inputSurface = surface
            muxer = mediaMuxer
            outputFile = file
            videoTrackIndex = -1
            audioTrackIndex = -1
            addedTrackCount = 0
            expectedTrackCount = 1
            muxerStarted = false

            when (renderSurfaceView) {
                is SurfaceView -> {
                    targetSurfaceView = renderSurfaceView
                    targetTextureView = null
                }
                is TextureView -> {
                    targetSurfaceView = null
                    targetTextureView = renderSurfaceView
                }
            }
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val maxMinutes = AllSettings.recordingMaxDurationMin.getValue()
            maxDurationMs = if (maxMinutes > 0) maxMinutes * 60_000L else 0L

            mediaProjection = projection
            audioServiceContext = if (projection != null) activity.applicationContext else null
            val audioReady = projection != null && setupAudioCapture(projection)
            if (projection != null && !audioReady) {
                // Setup failed - the consent was granted but capture itself couldn't start.
                // Don't hold onto an unused projection/service.
                try { projection.stop() } catch (t: Throwable) { Logging.e(TAG, "projection.stop() failed", t) }
                mediaProjection = null
                audioServiceContext?.let { ScreenRecorderAudioService.stop(it) }
                audioServiceContext = null
            }
            expectedTrackCount = if (audioReady) 2 else 1

            val thread = HandlerThread("ScreenRecorder-Capture").apply { start() }
            captureThread = thread
            captureHandler = Handler(thread.looper)

            startTimeNanos = System.nanoTime()
            isRecordingFlag.set(true)
            captureHandler?.post(captureLoop(activity))

            Toast.makeText(
                activity,
                if (audioReady) "Recording started (with audio)" else "Recording started",
                Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            Logging.e(TAG, "Failed to start recording", t)
            try { projection?.stop() } catch (inner: Throwable) { Logging.e(TAG, "projection.stop() failed", inner) }
            if (projection != null) ScreenRecorderAudioService.stop(activity.applicationContext)
            releaseQuietly()
            Toast.makeText(activity, "Recording failed to start", Toast.LENGTH_SHORT).show()
        }
    }

    fun stop(activity: Activity?) {
        if (!isRecordingFlag.compareAndSet(true, false)) return

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        audioCaptureThread?.quitSafely()
        audioCaptureThread = null
        audioCaptureHandler = null

        // Drain and tear down on a background thread - MediaCodec/MediaMuxer teardown can
        // block briefly and this is invoked directly from a UI click listener.
        TaskExecutors.getDefault().execute {
            try {
                drainVideoEncoder(endOfStream = true)
            } catch (t: Throwable) {
                Logging.e(TAG, "Error draining video encoder on stop", t)
            }
            if (audioEncoder != null) {
                try {
                    try { audioRecord?.stop() } catch (t: Throwable) { Logging.e(TAG, "audioRecord.stop() failed", t) }
                    finishAudioEncoding()
                } catch (t: Throwable) {
                    Logging.e(TAG, "Error draining audio encoder on stop", t)
                }
            }
            val savedFile = outputFile
            releaseQuietly()
            TaskExecutors.getAndroidUI().execute {
                if (activity != null) {
                    val message = if (savedFile != null && savedFile.length() > 0)
                        "Recording saved: ${savedFile.name}" else "Recording failed"
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---- Video capture ----

    private fun captureLoop(activity: Activity): Runnable = object : Runnable {
        override fun run() {
            if (!isRecordingFlag.get()) return

            if (maxDurationMs > 0 && (System.nanoTime() - startTimeNanos) / 1_000_000L >= maxDurationMs) {
                TaskExecutors.getAndroidUI().execute { stop(activity) }
                return
            }

            try {
                captureFrame()
            } catch (t: Throwable) {
                Logging.e(TAG, "Frame capture failed, stopping recording", t)
                TaskExecutors.getAndroidUI().execute { stop(activity) }
                return
            }

            captureHandler?.postDelayed(this, frameIntervalMs)
        }
    }

    private fun captureFrame() {
        val bitmap = frameBitmap ?: return
        val surfaceView = targetSurfaceView
        val textureView = targetTextureView

        when {
            surfaceView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                // PixelCopy is async but this loop only ever has one request in flight since
                // each tick waits for the previous drawFrame() before scheduling the next.
                PixelCopy.request(surfaceView, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) drawFrame(bitmap)
                }, captureHandler ?: return)
            }
            textureView != null -> {
                textureView.getBitmap(bitmap)
                drawFrame(bitmap)
            }
            else -> { /* no capturable surface this tick - skip the frame rather than crash */ }
        }
    }

    private fun drawFrame(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        try {
            val canvas = surface.lockCanvas(null)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        } catch (t: Throwable) {
            Logging.e(TAG, "Failed to draw frame to encoder surface", t)
        }
        drainVideoEncoder(endOfStream = false)
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        if (muxer == null) return
        if (endOfStream) {
            try { codec.signalEndOfInputStream() } catch (t: Throwable) { Logging.e(TAG, "signalEndOfInputStream failed", t) }
        }
        drainEncoderCommon(codec, videoBufferInfo, endOfStream, { videoTrackIndex }, { videoTrackIndex = it })
    }

    // ---- Audio capture ----

    /** @return true if audio capture was actually started; false leaves recording video-only. */
    private fun setupAudioCapture(projection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelMask, encoding)
            if (minBufferSize <= 0) return false

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            val encodeFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }
            val codec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE)
            codec.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            audioRecord = record
            audioEncoder = codec
            audioTrackIndex = -1

            record.startRecording()

            val thread = HandlerThread("ScreenRecorder-Audio").apply { start() }
            audioCaptureThread = thread
            audioCaptureHandler = Handler(thread.looper)
            audioCaptureHandler?.post(audioCaptureLoop())
            true
        } catch (t: Throwable) {
            Logging.e(TAG, "Audio capture setup failed, continuing video-only", t)
            false
        }
    }

    private fun audioCaptureLoop(): Runnable = object : Runnable {
        private val pcmBuffer = ByteArray(4096)
        override fun run() {
            if (!isRecordingFlag.get()) return
            try {
                captureAudioChunk(pcmBuffer)
            } catch (t: Throwable) {
                Logging.e(TAG, "Audio capture failed, video continues without further audio", t)
                return
            }
            audioCaptureHandler?.post(this)
        }
    }

    private fun captureAudioChunk(buffer: ByteArray) {
        val record = audioRecord ?: return
        val codec = audioEncoder ?: return
        val read = record.read(buffer, 0, buffer.size)
        if (read <= 0) return

        val inputIndex = codec.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(buffer, 0, read)
                val presentationTimeUs = System.nanoTime() / 1000L
                codec.queueInputBuffer(inputIndex, 0, read, presentationTimeUs, 0)
            }
        }
        drainAudioEncoder(endOfStream = false)
    }

    private fun finishAudioEncoding() {
        val codec = audioEncoder ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (t: Throwable) {
            Logging.e(TAG, "Failed to signal audio end-of-stream", t)
        }
        drainAudioEncoder(endOfStream = true)
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val codec = audioEncoder ?: return
        if (muxer == null) return
        drainEncoderCommon(codec, audioBufferInfo, endOfStream, { audioTrackIndex }, { audioTrackIndex = it })
    }

    // ---- Shared encoder draining ----

    /**
     * Shared by both the video and audio encoders. Each keeps its own MediaCodec.BufferInfo
     * (the two run on separate HandlerThreads and would otherwise race on a shared one) and its
     * own track-index slot, but they share one MediaMuxer - addTrack()/start()/writeSampleData()
     * calls are serialized via muxerLock since MediaMuxer itself isn't thread-safe for
     * concurrent use from two threads. muxer.start() only fires once every expected track (1 for
     * video-only, 2 once audio capture is active) has been added.
     */
    private fun drainEncoderCommon(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        getTrackIndex: () -> Int,
        setTrackIndex: (Int) -> Unit
    ) {
        val mediaMuxer = muxer ?: return
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (getTrackIndex() == -1) {
                            val idx = mediaMuxer.addTrack(codec.outputFormat)
                            setTrackIndex(idx)
                            addedTrackCount++
                            if (addedTrackCount >= expectedTrackCount && !muxerStarted) {
                                mediaMuxer.start()
                                muxerStarted = true
                            }
                        }
                    }
                }
                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    val trackIndex = getTrackIndex()
                    if (encodedData != null && info.size > 0 && trackIndex != -1) {
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        synchronized(muxerLock) {
                            if (muxerStarted) mediaMuxer.writeSampleData(trackIndex, encodedData, info)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ---- Teardown ----

    private fun releaseQuietly() {
        try { encoder?.stop() } catch (t: Throwable) { Logging.e(TAG, "encoder.stop() failed", t) }
        try { encoder?.release() } catch (t: Throwable) { Logging.e(TAG, "encoder.release() failed", t) }
        try { audioEncoder?.stop() } catch (t: Throwable) { Logging.e(TAG, "audioEncoder.stop() failed", t) }
        try { audioEncoder?.release() } catch (t: Throwable) { Logging.e(TAG, "audioEncoder.release() failed", t) }
        try { audioRecord?.release() } catch (t: Throwable) { Logging.e(TAG, "audioRecord.release() failed", t) }
        try { if (muxerStarted) muxer?.stop() } catch (t: Throwable) { Logging.e(TAG, "muxer.stop() failed", t) }
        try { muxer?.release() } catch (t: Throwable) { Logging.e(TAG, "muxer.release() failed", t) }
        try { inputSurface?.release() } catch (t: Throwable) { Logging.e(TAG, "inputSurface.release() failed", t) }
        try { mediaProjection?.stop() } catch (t: Throwable) { Logging.e(TAG, "mediaProjection.stop() failed", t) }
        audioServiceContext?.let {
            try { ScreenRecorderAudioService.stop(it) } catch (t: Throwable) { Logging.e(TAG, "ScreenRecorderAudioService.stop() failed", t) }
        }
        frameBitmap?.recycle()

        encoder = null
        muxer = null
        inputSurface = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        addedTrackCount = 0
        expectedTrackCount = 1
        frameBitmap = null
        targetSurfaceView = null
        targetTextureView = null
        outputFile = null
        startTimeNanos = 0L
        audioRecord = null
        audioEncoder = null
        mediaProjection = null
        audioServiceContext = null
    }

    /** Finds the SurfaceView/TextureView Minecraft actually renders into - see MinecraftGLSurface.start(),
     * which adds one or the other as a real sibling child under its own parent depending on the
     * "Alternate Surface" renderer setting. */
    private fun findRenderSurface(root: View): View? {
        if (root is SurfaceView || root is TextureView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findRenderSurface(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun scaleDimension(px: Int): Int {
        val percent = AllSettings.recordingResolutionScale.getValue().coerceIn(50, 100)
        return px * percent / 100
    }

    /** H.264 encoders require even width/height. */
    private fun evenify(px: Int): Int = if (px % 2 == 0) px else px - 1
}
