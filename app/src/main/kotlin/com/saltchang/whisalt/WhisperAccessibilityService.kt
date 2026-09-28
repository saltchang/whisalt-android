package com.saltchang.whisalt

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "Whisalt"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64
        private const val EXPAND_MS = 180L

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
        private const val COLOR_CANCEL = 0xFF48484A.toInt()
        private const val COLOR_DONE = 0xFF22A559.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    // Read by the recording thread, written from the main and transcription threads
    @Volatile private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private var recordingThread: Thread? = null
    // Tells the current recording thread to stop; each recording gets its own flag and AudioRecord
    private var recordingActive: AtomicBoolean? = null
    // Cancel / level meter / done, shown in place of the bubble while recording
    private var recordingBar: LinearLayout? = null
    private var levelMeter: LevelMeterView? = null
    // Decodes speech while the user is still talking (local mode only)
    private var segmentedTranscription: SegmentedTranscription? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded lazily)
    @Volatile private var localTranscriber: LocalTranscriber? = null

    // Simplified -> Taiwan Traditional, same as OpenWhispr's s2twp (dictionaries load on first use)
    private val chineseConverter by lazy { ChineseConverter { assets.open("opencc/$it") } }

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        // Load local model and Chinese dictionaries in background
        thread { initLocalModel(); chineseConverter }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    @Synchronized
    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        val name = modelName.ifBlank {
            // Auto-detect first available model
            LocalTranscriber.availableModels(this).firstOrNull()
                ?.also { Log.i(TAG, "Auto-detected model: $it") }
        }
        // Detach before release so no new transcription picks up the old recognizer
        val old = localTranscriber
        localTranscriber = null
        old?.release()
        localTranscriber = name?.let { LocalTranscriber.create(this, it) }
        Log.i(TAG, if (localTranscriber != null) "Local transcription ready" else "No local model loaded")
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        val meter = LevelMeterView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(COLOR_IDLE)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            addView(barButton(R.drawable.ic_close, COLOR_CANCEL, "Cancel recording") { cancelRecording() })
            // Cancel : meter : done = 1 : 2 : 1
            addView(meter, LinearLayout.LayoutParams(2 * buttonSize, buttonSize / 2).apply {
                marginStart = pad; marginEnd = pad
            })
            addView(barButton(R.drawable.ic_check, COLOR_DONE, "Finish recording") { stopAndTranscribe() })
        }

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        recordingBar = bar
        levelMeter = meter
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        recordingBar?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        recordingBar = null
        levelMeter = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun barButton(icon: Int, color: Int, label: String, onClick: () -> Unit): ImageView {
        val size = (BTN_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        return ImageView(this).apply {
            setImageResource(icon)
            setPadding(pad, pad, pad, pad)
            background = circle(color)
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** Swaps the bubble for the recording bar, which grows out of the bubble toward the screen center. */
    private fun showRecordingBar() {
        val bar = recordingBar ?: return
        val bubble = overlayView ?: return
        val bubbleParams = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pad = (PAD_DP * dp).toInt()
        // Two buttons and a meter twice their width, plus the bar's padding and the meter's margins
        val width = 4 * (BTN_DP * dp).toInt() + 3 * pad
        val height = (RING_DP * dp).toInt() // same height as the bubble
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // Anchored on the bubble's outer edge: a bubble docked right expands leftward, and vice versa
        val onRight = bubbleParams.x + bubbleParams.width / 2 > screenW / 2
        val margin = (MARGIN_DP * dp).toInt()
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (if (onRight) bubbleParams.x + bubbleParams.width - width else bubbleParams.x)
            .coerceIn(margin, maxOf(margin, screenW - width - margin))
        params.y = bubbleParams.y + (bubbleParams.height - height) / 2
        levelMeter?.clear()
        bubble.visibility = View.INVISIBLE
        if (!bar.isAttachedToWindow) wm.addView(bar, params)
        bar.pivotX = if (onRight) width.toFloat() else 0f
        bar.pivotY = height / 2f
        bar.scaleX = height.toFloat() / width
        bar.alpha = 0.6f
        bar.animate().scaleX(1f).alpha(1f).setDuration(EXPAND_MS).start()
    }

    private fun hideRecordingBar() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recordingBar?.let { if (it.isAttachedToWindow) wm.removeView(it) }
        overlayView?.visibility = View.VISIBLE
    }

    // --- State machine ---

    private fun onTap() {
        // While recording the bar's buttons take over; while transcribing taps are ignored
        if (state == State.IDLE) startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Whisalt app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        val local = localTranscriber
        // acquire() keeps this model loaded until the recording finishes, even if the user switches models
        val session = if (prefs().getBoolean("use_local", true) && local != null && local.acquire())
            SegmentedTranscription(assets, local, Vocabulary.hotwords(prefs().getString(Vocabulary.HOTWORDS_PREF, "") ?: ""))
        else null
        val pcm = ByteArrayOutputStream()
        pcmStream = pcm
        segmentedTranscription = session
        val active = AtomicBoolean(true)
        audioRecord = record
        recordingActive = active
        record.startRecording()
        state = State.RECORDING
        setBusy(false)
        showRecordingBar()

        recordingThread = thread {
            val buf = ByteArray(bufSize)
            // Its own flag and AudioRecord: a new recording may start before this thread winds down
            while (active.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                pcm.write(buf, 0, n)
                session?.accept(pcm16ToFloat(buf, n))
                val level = LevelMeterView.level(buf, n)
                handler.post { levelMeter?.push(level) }
            }
        }
    }

    /** Stops the microphone and hands back the recording thread, its PCM and its session. */
    private fun detachRecording(): Triple<Thread?, ByteArrayOutputStream?, SegmentedTranscription?> {
        recordingActive?.set(false)
        recordingActive = null
        val record = audioRecord
        audioRecord = null
        val detached = Triple(recordingThread, pcmStream, segmentedTranscription)
        recordingThread = null
        pcmStream = null
        segmentedTranscription = null
        // stop() unblocks the thread's read(); release() waits until the thread is done with it
        record?.stop()
        thread { detached.first?.join(); record?.release() }
        return detached
    }

    /** Drops the recording without transcribing; the bubble is ready for the next one at once. */
    private fun cancelRecording() {
        if (state != State.RECORDING) return
        state = State.IDLE
        hideRecordingBar()
        setAppearance(COLOR_IDLE)
        val (recorder, _, session) = detachRecording()
        // May wait for a segment that is mid-decode, so off the main thread
        thread { recorder?.join(); session?.release() }
    }

    private fun stopAndTranscribe() {
        if (state != State.RECORDING) return
        state = State.TRANSCRIBING
        hideRecordingBar()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        val (recorder, pcmOut, session) = detachRecording()

        val useLocal = prefs().getBoolean("use_local", true)
        thread {
            // The recording thread may still be appending its last buffer
            recorder?.join()
            val pcm = pcmOut?.toByteArray() ?: ByteArray(0)
            when {
                pcm.isEmpty() -> { session?.release(); handler.post { reset("No audio captured") } }
                session != null -> transcribeLocal(pcm, session)
                !useLocal -> transcribeApi(pcm)
                // Never fall back to the cloud when local mode is selected
                else -> handler.post { reset("Local model not ready. Download or select one in Whisalt.") }
            }
        }
    }

    /** Runs on a background thread. */
    private fun transcribeLocal(pcm: ByteArray, session: SegmentedTranscription) {
        try {
            val t0 = System.currentTimeMillis()
            // Most segments were decoded while recording; only the tail remains
            val transcript = session.finish()
            Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms after stop, ${pcm.size / 2 / SAMPLE_RATE}s audio")

            handleTranscriptionResult(transcript.text, transcript.language)
        } catch (e: Exception) {
            Log.e(TAG, "Local transcription failed", e)
            handler.post {
                toast("Local error: ${e.message}")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = ApiKeyStore.get(this)
        if (apiKey.isBlank()) { reset("Set API key in Whisalt app"); return }

        TranscriberClient.transcribe(wav, apiKey) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text, language = null)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    /** Chinese output is shown in Taiwan Traditional; other languages pass through. Call off the main thread. */
    private fun toTaiwanTraditional(text: String, language: String?) =
        if (ChineseConverter.isChinese(text, language)) chineseConverter.toTaiwan(text) else text

    /**
     * Taiwan Traditional first, then the user's `wrong => right` rules, which they write in Traditional.
     * Applied once, to the raw transcript, so LLM cleanup already sees the corrected words.
     */
    private fun finalizeText(text: String, language: String?): String {
        val rules = Vocabulary.replacements(prefs().getString(Vocabulary.REPLACEMENTS_PREF, "") ?: "")
        return Vocabulary.applyReplacements(toTaiwanTraditional(text, language), rules)
    }

    private fun handleTranscriptionResult(rawText: String?, language: String?) {
        if (rawText.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }
        val text = finalizeText(rawText, language)

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = ApiKeyStore.get(this)

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            
            PostProcessor.process(text, prompt, apiKey) { result ->
                val cleaned = result.text?.takeIf { it.isNotBlank() }?.let { toTaiwanTraditional(it, language) }
                handler.post {
                    if (cleaned != null) {
                        injectText(cleaned)
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                    }
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(
        rawText: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val text = TextSanitizer.forTextField(rawText)
        setClipboard(text)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun setClipboard(text: String) {
        val clip = ClipData.newPlainText("whisalt", text).apply {
            // Keep transcripts out of clipboard previews and keyboard clipboard history.
            // Literal key: ClipDescription.EXTRA_IS_SENSITIVE is API 33+, keyboards honor it earlier.
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        // Only target what the user is focused on; never paste into some other field on screen
        val (focused, others) = candidates.partition { it.isFocused || it.isAccessibilityFocused }
        others.forEach { it.recycle() }
        return focused.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, fieldText: String): Boolean {
        logNode("Trying node", node)

        val isTerminal = node.className?.toString()?.contains("TerminalView") == true
        val text = if (isTerminal) TextSanitizer.forTerminal(fieldText) else fieldText
        // Pastes read the clipboard, so a terminal must get the single-line version there too
        if (isTerminal) setClipboard(text)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (!isTerminal && !node.isPassword &&
            (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} actions=[$actions]"
        )
    }

    /** Little-endian 16-bit PCM to floats in [-1, 1). */
    private fun pcm16ToFloat(bytes: ByteArray, length: Int) = FloatArray(length / 2) { i ->
        ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort() / 32768f
    }

    private fun prefs() = getSharedPreferences("whisalt", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
