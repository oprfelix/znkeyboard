package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.ImeDimensions
import dev.zain.znkeyboard.typing.AdaptiveTypingEngine
import dev.zain.znkeyboard.typing.CharLanguageModel
import dev.zain.znkeyboard.typing.TypingLearner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.sin

class ZnKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Callback {
        fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState)
        fun onBackspaceGestureDeleteStarted(): Boolean
        fun onBackspaceGestureDeleteChanged(wordCount: Int)
        fun onBackspaceGestureDeleteFinished()
        fun onBackspaceGestureDeleteCancelled()
        fun onSpaceCursorDragStarted(extendSelection: Boolean): Boolean
        fun onSpaceCursorDragChanged(characterDelta: Int)
        fun onSpaceCursorDragFinished()
        fun onSpaceCursorDragCancelled()
        fun onClipboardPanelRequested()
        fun onPhrasesPanelRequested()
        fun onHelpPanelRequested()
        fun onToolsMenuRequested()
        fun onKeyboardSettingsRequested()
        fun onAgentRewriteRequested()
        fun onPinyinModeToggled()
        /** Long-press on the 中/英 key: the system's list of keyboards. */
        fun onInputMethodPickerRequested() {}
        fun onTranslateLanguageMenuRequested()
        fun onAgentPolishOnlyRequested()
        /** Hold-space voice input; false when it can't start (the service shows why). */
        fun onVoiceInputStarted(): Boolean
        fun onVoiceInputFinished(cancelled: Boolean)
        /** Drops the recording, or the transcription still on its way. */
        fun onVoiceInputCancelled() {}
        /** The first-use hint under the microphone has been seen. */
        fun onVoiceTipShown() {}
    }

    /** What the space key shows for voice input. */
    sealed class VoiceIndicator {
        data class Recording(val level: Float, val elapsedMs: Long) : VoiceIndicator()
        data object Processing : VoiceIndicator()
        data class Message(val text: String) : VoiceIndicator()
    }

    var callback: Callback? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterTypeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
    private val labelTypeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE.text
        textAlign = Paint.Align.CENTER
        typeface = letterTypeface
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val previewBounds = RectF()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val loadingGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val loadingGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(76, 10, 12, 18)
    }
    private val loadingSparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE.text
    }
    private val loadingSparklePath = Path().apply {
        moveTo(0f, -1f)
        lineTo(0.2f, -0.2f)
        lineTo(1f, 0f)
        lineTo(0.2f, 0.2f)
        lineTo(0f, 1f)
        lineTo(-0.2f, 0.2f)
        lineTo(-1f, 0f)
        lineTo(-0.2f, -0.2f)
        close()
    }
    private val loadingGradientMatrix = Matrix()
    private val loadingShaderBounds = RectF()
    private var loadingGradientShader: LinearGradient? = null

    private var layoutMode = LayoutMode.Letters
    private var lastNumericLayoutMode = LayoutMode.Symbols
    private var shiftState = ShiftState.Off
    private var ctrl = false
    private var alt = false
    private var enterLabel = "Enter"
    private var heightScale = 1f
    private var bottomPaddingDp = KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP
    private var functionKeyBackgroundsEnabled = true
    private var upperRowKeyIds = KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS
    private var secondRowButtonIds = KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS
    private var keyboardRowOrder = KeyboardSettings.DEFAULT_KEYBOARD_ROW_ORDER
    private var shortcutRowState = ShortcutRowState(secondRowVisible = true)
    private val clipboardIconStroke = PathParser.createPathFromPathData(ToolbarIconPaths.CLIPBOARD_STROKE)
    private val clipboardIconFill = PathParser.createPathFromPathData(ToolbarIconPaths.CLIPBOARD_FILL)
    private val phrasesIconStroke = PathParser.createPathFromPathData(ToolbarIconPaths.PHRASES_STROKE)
    private val helpIconStroke = PathParser.createPathFromPathData(ToolbarIconPaths.HELP_STROKE)
    private val micIconStroke = PathParser.createPathFromPathData(ToolbarIconPaths.MIC_STROKE)
    private val helpIconFill = PathParser.createPathFromPathData(ToolbarIconPaths.HELP_FILL)
    private val toolsIconStroke = PathParser.createPathFromPathData(ToolbarIconPaths.TOOLS_STROKE)
    private val settingsIconPath = Path().apply {
        repeat(SETTINGS_ICON_TEETH) { tooth ->
            val angle = tooth * 2.0 * Math.PI / SETTINGS_ICON_TEETH
            for ((offset, radius) in SETTINGS_ICON_TOOTH) {
                val x = (12.0 + radius * cos(angle + offset)).toFloat()
                val y = (12.0 + radius * sin(angle + offset)).toFloat()
                if (tooth == 0 && offset == SETTINGS_ICON_TOOTH.first().first) moveTo(x, y) else lineTo(x, y)
            }
        }
        close()
        addCircle(12f, 12f, 3f, Path.Direction.CW)
    }
    private var pinyinModeEnabled = false
    private var chineseKeyboard = dev.zain.znkeyboard.KeyboardSettings.ChineseKeyboard.Qwerty
    /** Off: digits move to the top letter row's corners (long press types them) and letter keys grow. */
    private var numberRowVisible = true
    /** 9-key pinyin spellings to offer beside the keys while composing; null shows punctuation. */
    private var nineKeySpellings: List<String>? = null
    private var voiceIndicator: VoiceIndicator? = null
    private var voiceInputEnabled = true
    private var voiceCancelArmed = false
    /** A tap on the microphone records hands-free, with 取消 / 完成 / 重说 buttons on the panel. */
    private var voicePanel = false
    private var voicePanelPressed = -1
    private val voicePanelButtons = Array(3) { RectF() }
    private var voiceHeardSpeech = false
    private var voiceQuietSinceMs = 0L
    private var voiceTipVisible = false
    private val voiceTipRect = RectF()
    // Reused on every frame and finger move while recording, so drawing allocates nothing.
    private val voiceOverlayRect = RectF()
    private val voiceWavePath = Path()
    private val voiceCancelPoint = PointF()
    private var voiceDisplayLevel = 0f
    private var voiceTimerSeconds = -1L
    private var voiceTimerText = ""
    private var rewriteLabel = "Rewrite"
    // Shown on a round key beside Rewrite while it translates; null when it only polishes.
    private var translateLanguageCode: String? = null
    // Reused by onDraw for round and merged keys, which must not allocate.
    private val chipKeyBounds = RectF()
    // 润色 and 翻译 lead with the icons iOS shows on them.
    private val polishIcon by lazy(LazyThreadSafetyMode.NONE) { context.tintedDrawable(dev.zain.znkeyboard.R.drawable.ic_auto_fix_high_24, Color.WHITE) }
    private val translateIcon by lazy(LazyThreadSafetyMode.NONE) { context.tintedDrawable(dev.zain.znkeyboard.R.drawable.ic_translate_24, Color.WHITE) }
    private val toolbarPillBounds = RectF()
    private val iconLabelBounds = RectF()
    private val chipChevronPath = Path()
    private val mergedKeyBounds = RectF()
    private val mergedLabelBounds = RectF()
    // 翻译 over its language, drawn as two layers (as on iOS).
    private val translateLayers = TranslateLayers(resources.displayMetrics.density)
    private var keyHapticsEnabled = true
    private var keyPreviewEnabled = true
    private var autoShiftActive = false
    private var lastShiftTapAt = 0L

    /** Personal touch decoder + learner; null means plain geometric hit-testing. */
    var typingEngine: AdaptiveTypingEngine? = null

    /**
     * While a letter tapped on the letter keys is being dispatched: how well that tap fitted each
     * key (TouchDecoder.letterCosts), so pinyin can weigh near misses. Null otherwise, and for a
     * tap that slid; valid only during the callback.
     */
    var letterTapCosts: FloatArray? = null
        private set

    /** The touch learner's record of that tap, so pinyin can relabel it when a reading corrects it. */
    var letterTapSample: TypingLearner.Sample? = null
        private set

    private val glyphBounds = Rect()

    // Reused for taps in flight; more than the fingers that can be down at once.
    private val tapCostPool = Array(TAP_COST_POOL) { FloatArray(26) }
    private var tapCostNext = 0

    // Letter geometry of the current layout, in dp, for the adaptive decoder. Fresh arrays per layout
    // so learning samples keep the geometry they were captured with.
    private var letterHits = arrayOfNulls<KeyHit>(26)
    private var letterCentersXDp = FloatArray(26)
    private var letterCentersYDp = FloatArray(26)
    private var letterPitchXDp = 0f
    private var letterPitchYDp = 0f
    private var letterGeometryValid = false
    // The system can draw close-keyboard and IME-switch controls inside the IME window.
    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }
    private val touchSlopPx by lazy(LazyThreadSafetyMode.NONE) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    private val activeTouches = SparseArray<ActiveTouch>()
    private val pointerQueue = mutableListOf<Int>()
    private var hitTargets: List<KeyHit> = emptyList()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pendingLongPressPointerId: Int? = null
    private var pendingLongPressKeyId: String? = null
    private var pendingLongPressRunnable: Runnable? = null
    private val backspaceRepeatRunnable = Runnable { repeatBackspace() }
    private var repeatingBackspacePointerId: Int? = null
    private var repeatingBackspaceKeyId: String? = null
    private var backspaceRepeatCount = 0

    init {
        isHapticFeedbackEnabled = true
        isSoundEffectsEnabled = false
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
            invalidate()
        }
    }

    fun setBottomPaddingDp(paddingDp: Int) {
        val normalizedPaddingDp = KeyboardSettings.normalizeBottomPaddingDp(paddingDp)
        if (bottomPaddingDp != normalizedPaddingDp) {
            bottomPaddingDp = normalizedPaddingDp
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setKeyHapticsEnabled(enabled: Boolean) {
        keyHapticsEnabled = enabled
    }

    fun setKeyPreviewEnabled(enabled: Boolean) {
        keyPreviewEnabled = enabled
    }

    /**
     * Sentence-start capitalization requested by the editor's caps mode. Only drives Shift while the
     * user has not chosen a shift state themselves; caps lock is never overridden.
     */
    fun setAutoShift(enabled: Boolean) {
        if (layoutMode != LayoutMode.Letters) return
        val next = when {
            shiftState == ShiftState.Locked -> return
            enabled && shiftState == ShiftState.Off -> ShiftState.OneShot
            !enabled && shiftState == ShiftState.OneShot && autoShiftActive -> ShiftState.Off
            else -> shiftState
        }
        val nextAuto = enabled && next == ShiftState.OneShot && (autoShiftActive || shiftState == ShiftState.Off)
        if (next != shiftState || nextAuto != autoShiftActive) {
            shiftState = next
            autoShiftActive = nextAuto
            refreshHitTargets()
            invalidate()
        }
    }

    fun setFunctionKeyBackgroundsEnabled(enabled: Boolean) {
        if (functionKeyBackgroundsEnabled != enabled) {
            functionKeyBackgroundsEnabled = enabled
            invalidate()
        }
    }

    fun setUpperRowKeyIds(keyIds: List<String>) {
        val normalizedKeyIds = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
        if (upperRowKeyIds != normalizedKeyIds) {
            upperRowKeyIds = normalizedKeyIds
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setSecondRowButtonIds(buttonIds: List<String>) {
        val normalizedButtonIds = KeyboardSettings.normalizeSecondRowButtonIds(buttonIds)
        if (secondRowButtonIds != normalizedButtonIds) {
            secondRowButtonIds = normalizedButtonIds
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setKeyboardRowOrder(rowIds: List<String>) {
        val normalizedRowIds = KeyboardSettings.normalizeKeyboardRowOrder(rowIds)
        if (keyboardRowOrder != normalizedRowIds) {
            keyboardRowOrder = normalizedRowIds
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun renderShortcutRows(state: ShortcutRowState) {
        if (shortcutRowState != state) {
            shortcutRowState = state
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setEnterLabel(label: String) {
        if (enterLabel != label) {
            enterLabel = label
            refreshHitTargets()
            invalidate()
        }
    }

    /** What the focused field takes, for keys that only make sense there (@ in email, a dial pad). */
    enum class FieldKind { Text, Email, Url, Number, Phone }

    private var fieldKind = FieldKind.Text

    fun setFieldKind(kind: FieldKind) {
        if (fieldKind != kind) {
            fieldKind = kind
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setPinyinMode(enabled: Boolean) {
        if (pinyinModeEnabled != enabled) {
            pinyinModeEnabled = enabled
            // Pinyin is lowercase: a one-shot Shift left from English (often the sentence-start
            // one) would make the first letter a capital that goes straight into the text.
            // Caps lock was chosen and stays.
            if (enabled && shiftState == ShiftState.OneShot) {
                shiftState = ShiftState.Off
                autoShiftActive = false
            }
            refreshHitTargets()
            invalidate()
        }
    }

    fun setNumberRowVisible(visible: Boolean) {
        if (numberRowVisible != visible) {
            numberRowVisible = visible
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setChineseKeyboard(keyboard: dev.zain.znkeyboard.KeyboardSettings.ChineseKeyboard) {
        if (chineseKeyboard != keyboard) {
            chineseKeyboard = keyboard
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setNineKeySpellings(spellings: List<String>?) {
        val next = spellings?.takeIf { it.isNotEmpty() }
        if (nineKeySpellings != next) {
            nineKeySpellings = next
            refreshHitTargets()
            invalidate()
        }
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        if (voiceInputEnabled != enabled) {
            voiceInputEnabled = enabled
            refreshHitTargets()
            invalidate()
        }
    }

    /**
     * The space bar names what typing goes into now (拼音, 笔画 or English), as the language key
     * switches it; a microphone in its corner marks that holding it records speech.
     */
    private fun spaceLabel(): String = when {
        !pinyinModeEnabled -> "English"
        chineseKeyboard == dev.zain.znkeyboard.KeyboardSettings.ChineseKeyboard.NineKeyStroke -> "笔画"
        else -> "拼音"
    }

    fun setVoiceIndicator(indicator: VoiceIndicator?) {
        if (voiceIndicator != indicator) {
            if (indicator !is VoiceIndicator.Recording) voiceCancelArmed = false
            if (indicator == null || indicator is VoiceIndicator.Message) {
                voicePanel = false
                voicePanelPressed = -1
            }
            voiceIndicator = indicator
            invalidate()
        }
        if (indicator is VoiceIndicator.Recording && voicePanel) checkVoiceSilence(indicator.level)
    }

    /** Hands-free, a pause after speaking finishes the recording, as 完成 would. */
    private fun checkVoiceSilence(level: Float) {
        val now = SystemClock.uptimeMillis()
        when {
            level > VOICE_SPEECH_LEVEL -> {
                voiceHeardSpeech = true
                voiceQuietSinceMs = 0L
            }
            !voiceHeardSpeech || level >= VOICE_QUIET_LEVEL -> Unit
            voiceQuietSinceMs == 0L -> voiceQuietSinceMs = now
            now - voiceQuietSinceMs >= VOICE_SILENCE_DONE_MS -> {
                voiceQuietSinceMs = 0L
                voiceHeardSpeech = false
                post { if (voicePanel && voiceIndicator is VoiceIndicator.Recording) callback?.onVoiceInputFinished(cancelled = false) }
            }
        }
    }

    /** The one-time hint under the microphone: tap to record, hold to talk. */
    fun setVoiceTipVisible(visible: Boolean) {
        if (voiceTipVisible != visible) {
            voiceTipVisible = visible
            invalidate()
        }
    }

    /** [languageCode] adds the round target-language key (e.g. "EN") next to Rewrite. */
    fun setRewriteMode(label: String, languageCode: String?) {
        if (rewriteLabel != label || translateLanguageCode != languageCode) {
            rewriteLabel = label
            translateLanguageCode = languageCode
            refreshHitTargets()
            invalidate()
        }
    }

    fun clearLatchedModifiers() {
        if (ctrl || alt) {
            ctrl = false
            alt = false
            refreshHitTargets()
            invalidate()
        }
    }

    fun setPhonePadMode(enabled: Boolean) {
        val targetMode = if (enabled) LayoutMode.PhonePad else LayoutMode.Letters
        lastNumericLayoutMode = if (enabled) LayoutMode.PhonePad else LayoutMode.Symbols
        if (layoutMode != targetMode) {
            layoutMode = targetMode
            shiftState = ShiftState.Off
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun resetToLetters() {
        if (layoutMode != LayoutMode.Letters) {
            layoutMode = LayoutMode.Letters
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Each visible row adds its own height, so showing or hiding a row never resizes the keys.
        // Pages are measured as the letters page: symbols, with a row more, share the same height
        // rather than making the keyboard jump when the page changes.
        val rows = rows(LayoutMode.Letters)
        val rowWeightSum = rows.fold(0f) { total, row -> total + row.heightWeight }
        val desiredHeight = (
            dp(ImeLayout.TOP_PADDING_DP.toFloat()) +
                rowWeightSum * ImeLayout.rowUnitHeightPx(context, heightScale) +
                dp(ImeLayout.ROW_GAP_DP.toFloat()) * (rows.size - 1) +
                dp(bottomPaddingDp.toFloat()) +
                bottomSystemControlGapPx
            ).roundToInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PALETTE.background)
        if (hitTargets.isEmpty()) {
            refreshHitTargets()
        }

        for (index in hitTargets.indices) {
            drawKey(canvas, hitTargets[index], hitTargets.getOrNull(index + 1))
        }
        if (keyPreviewEnabled) {
            drawKeyPreviews(canvas)
        }
        when (val indicator = voiceIndicator) {
            is VoiceIndicator.Recording -> if (voicePanel) drawVoicePanel(canvas, indicator) else drawVoiceOverlay(canvas, indicator)
            VoiceIndicator.Processing -> if (voicePanel) drawVoicePanel(canvas, null)
            else -> if (voiceTipVisible && voiceInputEnabled) drawVoiceTip(canvas)
        }
        if (shortcutRowState.loading || voiceIndicator == VoiceIndicator.Processing) {
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        typingEngine?.setLandscape(
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        )
        refreshHitTargets()
    }

    override fun onDetachedFromWindow() {
        cancelActiveTouches()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (voicePanel && (voiceIndicator is VoiceIndicator.Recording || voiceIndicator == VoiceIndicator.Processing)) {
            handleVoicePanelTouch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && voiceTipVisible) {
            voiceTipVisible = false
            callback?.onVoiceTipShown()
            invalidate()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelActiveTouches()
                handlePointerDown(event, event.actionIndex)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                updateActiveTouches(event)
                handlePointerDown(event, event.actionIndex)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (updateActiveTouches(event)) {
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                updateActiveTouches(event)
                releasePointersThrough(event.getPointerId(event.actionIndex))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                updateActiveTouches(event)
                releasePointersThrough(event.getPointerId(event.actionIndex))
                activeTouches.clear()
                pointerQueue.clear()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveTouches()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val downX = event.getX(pointerIndex)
        val downY = event.getY(pointerIndex)
        val hit = findHit(downX, downY)
        val touch = ActiveTouch(null, downX = downX, downY = downY)
        // Resolve the key at touch-down, where the user aimed, not where the finger rolls to on lift.
        val key = resolveDownKey(hit, downX, downY, touch)
        touch.currentKey = key
        activeTouches.put(pointerId, touch)
        if (key != null && keyHapticsEnabled) {
            // VIRTUAL_KEY, the navigation keys' tick: KEYBOARD_TAP is too faint on many phones.
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        pointerQueue.remove(pointerId)
        pointerQueue.add(pointerId)
        if (key?.mergesWithNext == true) {
            // Fallback for a missed badge tap: long-press the translate key to pick the language.
            scheduleLongPress(pointerId, key.id) { callback?.onTranslateLanguageMenuRequested() }
        }
        when (key?.id) {
            "apostrophe" -> scheduleLongPress(pointerId, "apostrophe") {
                handleKey(KeySpec("backtick_long_press", "`", KeyIntent.Dispatch(KeyboardAction.Text("`"))))
            }
            "phone_star" -> scheduleLongPress(pointerId, "phone_star") {
                handleKey(KeySpec("phone_slash_long_press", "/", KeyIntent.Dispatch(KeyboardAction.Text("/"))))
            }
            // Held, space moves the cursor: the slide that follows needs no distance to start.
            "space" -> scheduleLongPress(pointerId, "space") {
                startSpaceCursorHold(pointerId)
            }
            PINYIN_MODE_KEY_ID -> scheduleLongPress(pointerId, PINYIN_MODE_KEY_ID) {
                callback?.onInputMethodPickerRequested()
            }
        }
        // Without the number row, a long press on the top row types its corner digit.
        val digit = key?.takeIf { it.id.startsWith("char_") }?.longPressHint?.singleOrNull()?.takeIf { it.isDigit() }
        if (key != null && digit != null) {
            scheduleLongPress(pointerId, key.id) {
                handleKey(KeySpec("long_press_digit_$digit", "$digit", KeyIntent.Dispatch(KeyboardAction.Text("$digit"))))
            }
        }
        // The toolbar's microphone: held, it records; up, it inserts; slid up, it cancels.
        if (key?.intent == KeyIntent.VoiceInput) startVoiceTouch(pointerId)
        if (key?.id == "space") {
            touch.spaceCursorDrag = SpaceCursorDragTouch(
                key = key,
                startX = event.getX(pointerIndex),
                startY = event.getY(pointerIndex),
                stepPx = max(dp(SPACE_CURSOR_DRAG_STEP_DP), (hit?.visualBounds?.width() ?: 0f) / SPACE_CURSOR_DRAG_VISIBLE_STEPS),
            )
        }
        key?.takeIf(::isBackspaceKey)?.let { key ->
            touch.backspaceGesture = BackspaceGestureTouch(
                key = key,
                startX = event.getX(pointerIndex),
                startY = event.getY(pointerIndex),
                wordStepPx = max(dp(GESTURE_DELETE_WORD_STEP_DP), (hit?.visualBounds?.width() ?: 0f) * 0.72f),
            )
            scheduleBackspaceRepeat(pointerId, key)
        }
    }

    private fun updateActiveTouches(event: MotionEvent): Boolean {
        var changed = false
        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(pointerIndex)
            val touch = activeTouches.get(pointerId) ?: continue
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            touch.voice?.let { voice ->
                // Sliding into the overlay's cancel zone arms cancel; back down re-arms sending.
                val armed = y < (voiceCancelBoundaryY() ?: (voice.startY - dp(VOICE_CANCEL_SLIDE_DP))) ||
                    voiceCancelTarget()?.let { hypot(x - it.x, y - it.y) < dp(VOICE_CANCEL_TARGET_DP + 14f) } == true
                if (armed != voiceCancelArmed) {
                    voiceCancelArmed = armed
                    if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    changed = true
                }
                continue
            }
            if (updateBackspaceGesture(pointerId, touch, x, y)) {
                changed = true
                continue
            }
            if (updateSpaceCursorDrag(pointerId, touch, x, y)) {
                changed = true
                continue
            }

            // 9-key: a flick up on a digit key types the digit instead of its letters.
            val digitKey = touch.currentKey?.takeIf { (it.intent as? KeyIntent.Dispatch)?.action is KeyboardAction.NineKeyDigit }
            if (digitKey != null && !touch.slid) {
                if (touch.downY - y >= dp(NINE_KEY_SWIPE_UP_DP)) touch.swipedUp = true
                continue
            }
            if (!touch.slid) {
                // Contact centroids drift while a finger rolls through a tap. Keep the touch-down key
                // until the finger has clearly travelled, then allow deliberate slide-to-correct.
                if (hypot(x - touch.downX, y - touch.downY) < slideHysteresisPx()) continue
                touch.slid = true
            }
            val key = findHit(x, y)?.key?.takeIf { it.enabled }
            if (touch.currentKey?.id != key?.id) {
                touch.currentKey = key
                if (pendingLongPressPointerId == pointerId && key?.id != pendingLongPressKeyId) {
                    cancelPendingLongPress()
                }
                when {
                    repeatingBackspacePointerId == pointerId && key?.id != repeatingBackspaceKeyId -> {
                        cancelBackspaceRepeat()
                    }
                    repeatingBackspacePointerId != pointerId && key?.let(::isBackspaceKey) == true -> {
                        scheduleBackspaceRepeat(pointerId, key)
                    }
                }
                changed = true
            }
        }
        return changed
    }

    private fun updateBackspaceGesture(
        pointerId: Int,
        touch: ActiveTouch,
        x: Float,
        y: Float,
    ): Boolean {
        val gesture = touch.backspaceGesture ?: return false
        if (gesture.consumedWithoutGesture) {
            return true
        }
        if (touch.repeatConsumed && !gesture.active) {
            touch.backspaceGesture = null
            return false
        }

        val dx = x - gesture.startX
        val dy = y - gesture.startY
        val activationDistance = max(touchSlopPx, dp(GESTURE_DELETE_ACTIVATION_DP))
        if (!gesture.active) {
            val leftDistance = -dx
            if (leftDistance < activationDistance || leftDistance < abs(dy) * 1.2f) {
                return false
            }

            cancelBackspaceRepeatFor(pointerId)
            touch.repeatConsumed = true
            touch.currentKey = gesture.key
            if (callback?.onBackspaceGestureDeleteStarted() == true) {
                gesture.active = true
                typingEngine?.learner?.discardPending()
            } else {
                gesture.consumedWithoutGesture = true
                touch.currentKey = null
                return true
            }
        }

        val wordCount = backspaceGestureWordCount(gesture, activationDistance, x)
        if (wordCount != gesture.lastWordCount) {
            gesture.lastWordCount = wordCount
            if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            callback?.onBackspaceGestureDeleteChanged(wordCount)
        }
        return true
    }

    private fun updateSpaceCursorDrag(
        pointerId: Int,
        touch: ActiveTouch,
        x: Float,
        y: Float,
    ): Boolean {
        val gesture = touch.spaceCursorDrag ?: return false
        if (gesture.consumedWithoutGesture) {
            return true
        }

        val dx = x - gesture.startX
        val dy = y - gesture.startY
        val activationDistance = max(touchSlopPx, dp(SPACE_CURSOR_DRAG_ACTIVATION_DP))
        if (!gesture.active) {
            if (!gesture.held && (abs(dx) < activationDistance || abs(dx) < abs(dy) * 1.2f)) {
                return false
            }

            cancelLongPressFor(pointerId)
            touch.cursorDragConsumed = true
            touch.currentKey = gesture.key
            val shiftTouch = heldShiftTouch(pointerId)
            if (callback?.onSpaceCursorDragStarted(extendSelection = shiftTouch != null) == true) {
                gesture.active = true
                shiftTouch?.heldAsModifier = true
                typingEngine?.learner?.onContextChanged(null)
                clearLatchedModifiers()
            } else {
                gesture.consumedWithoutGesture = true
                touch.currentKey = null
                return true
            }
        }

        val characterDelta = spaceCursorDragCharacterDelta(gesture, x)
        if (characterDelta != gesture.lastCharacterDelta) {
            gesture.lastCharacterDelta = characterDelta
            if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            callback?.onSpaceCursorDragChanged(characterDelta)
        }
        return true
    }

    private fun spaceCursorDragCharacterDelta(gesture: SpaceCursorDragTouch, x: Float): Int {
        val rawDelta = (x - gesture.startX) / gesture.stepPx
        return rawDelta.roundToInt().coerceIn(-SPACE_CURSOR_DRAG_MAX_CHARS, SPACE_CURSOR_DRAG_MAX_CHARS)
    }

    private fun backspaceGestureWordCount(
        gesture: BackspaceGestureTouch,
        activationDistance: Float,
        x: Float,
    ): Int {
        val leftDistance = (gesture.startX - x).coerceAtLeast(0f)
        if (leftDistance < activationDistance) return 0
        return 1 + ((leftDistance - activationDistance) / gesture.wordStepPx).toInt()
    }

    private fun releasePointersThrough(pointerId: Int) {
        val shiftHeld = activeTouches.get(pointerId)?.let { claimHeldShift(pointerId, it) } == true
        var queueIndex = 0
        while (queueIndex < pointerQueue.size) {
            val nextPointerId = pointerQueue[queueIndex]
            val touch = activeTouches.get(nextPointerId)
            // A Shift held for selecting stays down until its own finger lifts.
            if (nextPointerId != pointerId && touch?.heldAsModifier == true) {
                queueIndex++
                continue
            }
            pointerQueue.removeAt(queueIndex)
            activeTouches.remove(nextPointerId)
            finishBackspaceGesture(touch)
            finishSpaceCursorDrag(touch)
            // The microphone lifted: send, cancel, or (a quick tap) keep recording hands-free.
            finishVoiceTouch(touch, cancelled = false)
            cancelLongPressFor(nextPointerId)
            cancelBackspaceRepeatFor(nextPointerId)
            if (
                touch?.longPressConsumed != true &&
                touch?.repeatConsumed != true &&
                touch?.cursorDragConsumed != true &&
                touch?.heldAsModifier != true
            ) {
                touch?.currentKey?.let { key ->
                    val digit = ((key.intent as? KeyIntent.Dispatch)?.action as? KeyboardAction.NineKeyDigit)?.digit
                    if (touch.swipedUp && digit != null) {
                        handleKey(KeySpec("nine_digit_$digit", "$digit", KeyIntent.Dispatch(KeyboardAction.Text("$digit"))))
                    } else {
                        val sample = touch.learningSample(key)
                        letterTapCosts = touch.tapCosts?.takeIf { !touch.slid }
                        letterTapSample = sample
                        try {
                            handleKey(key, sample, shiftHeld = shiftHeld && nextPointerId == pointerId)
                        } finally {
                            letterTapCosts = null
                            letterTapSample = null
                        }
                    }
                }
            }
            if (nextPointerId == pointerId) {
                return
            }
        }
        finishVoiceTouch(activeTouches.get(pointerId), cancelled = false)
        activeTouches.remove(pointerId)
        cancelLongPressFor(pointerId)
        cancelBackspaceRepeatFor(pointerId)
    }

    /** Space held still: the cursor follows the finger from here, as on iOS. */
    private fun startSpaceCursorHold(pointerId: Int) {
        val touch = activeTouches.get(pointerId) ?: return
        val gesture = touch.spaceCursorDrag ?: return
        if (gesture.active) return
        gesture.held = true
        touch.longPressConsumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateSpaceCursorDrag(pointerId, touch, gesture.startX, gesture.startY)
    }

    private fun startVoiceTouch(pointerId: Int) {
        val touch = activeTouches.get(pointerId) ?: return
        if (callback?.onVoiceInputStarted() != true) return
        // Once recording, the finger only arms cancel; it no longer moves the cursor.
        touch.spaceCursorDrag = null
        touch.voice = VoiceTouch(startY = touch.downY, startedAt = SystemClock.uptimeMillis())
        voiceTipVisible = false
        voiceCancelArmed = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun finishVoiceTouch(touch: ActiveTouch?, cancelled: Boolean) {
        val voice = touch?.voice ?: return
        touch.voice = null
        // A quick tap keeps recording, hands-free, until 完成 / 取消 on the panel.
        if (!cancelled && !voiceCancelArmed && SystemClock.uptimeMillis() - voice.startedAt < VOICE_TAP_MS) {
            openVoicePanel()
            return
        }
        val cancel = cancelled || voiceCancelArmed
        voiceCancelArmed = false
        callback?.onVoiceInputFinished(cancelled = cancel)
    }

    /** Key-code keys (arrows, Home, Tab…) tapped while Shift is physically held get Shift applied. */
    private fun claimHeldShift(pointerId: Int, touch: ActiveTouch): Boolean {
        val intent = touch.currentKey?.intent as? KeyIntent.Dispatch ?: return false
        if (intent.action !is KeyboardAction.KeyCode) return false
        if (touch.longPressConsumed || touch.repeatConsumed || touch.cursorDragConsumed) return false
        val shiftTouch = heldShiftTouch(pointerId) ?: return false
        shiftTouch.heldAsModifier = true
        return true
    }

    private fun heldShiftTouch(excludingPointerId: Int): ActiveTouch? {
        for (index in 0 until activeTouches.size()) {
            if (activeTouches.keyAt(index) == excludingPointerId) continue
            val touch = activeTouches.valueAt(index)
            if (touch.currentKey?.intent == KeyIntent.Shift) return touch
        }
        return null
    }

    private fun cancelActiveTouches() {
        for (index in 0 until activeTouches.size()) {
            finishVoiceTouch(activeTouches.valueAt(index), cancelled = true)
        }
        cancelPendingLongPress()
        cancelBackspaceRepeat()
        cancelBackspaceGestures()
        cancelSpaceCursorDrags()
        activeTouches.clear()
        pointerQueue.clear()
    }

    private fun finishBackspaceGesture(touch: ActiveTouch?) {
        val gesture = touch?.backspaceGesture ?: return
        if (gesture.active) {
            callback?.onBackspaceGestureDeleteFinished()
        }
        touch.backspaceGesture = null
    }

    private fun finishSpaceCursorDrag(touch: ActiveTouch?) {
        val gesture = touch?.spaceCursorDrag ?: return
        if (gesture.active) {
            callback?.onSpaceCursorDragFinished()
        }
        touch.spaceCursorDrag = null
    }

    private fun cancelBackspaceGestures() {
        for (index in 0 until activeTouches.size()) {
            val gesture = activeTouches.valueAt(index).backspaceGesture
            if (gesture?.active == true) {
                callback?.onBackspaceGestureDeleteCancelled()
            }
        }
    }

    private fun cancelSpaceCursorDrags() {
        for (index in 0 until activeTouches.size()) {
            val gesture = activeTouches.valueAt(index).spaceCursorDrag
            if (gesture?.active == true) {
                callback?.onSpaceCursorDragCancelled()
            }
        }
    }

    private fun scheduleLongPress(
        pointerId: Int,
        keyId: String,
        onLongPress: () -> Unit,
    ) {
        cancelPendingLongPress()
        pendingLongPressPointerId = pointerId
        pendingLongPressKeyId = keyId
        pendingLongPressRunnable = Runnable {
            val touch = activeTouches.get(pointerId) ?: return@Runnable
            if (touch.currentKey?.id != keyId) return@Runnable

            touch.longPressConsumed = true
            touch.currentKey = null
            pointerQueue.remove(pointerId)
            pendingLongPressPointerId = null
            pendingLongPressKeyId = null
            pendingLongPressRunnable = null
            onLongPress()
            invalidate()
        }.also { runnable ->
            longPressHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun cancelLongPressFor(pointerId: Int) {
        if (pendingLongPressPointerId == pointerId) {
            cancelPendingLongPress()
        }
    }

    private fun cancelPendingLongPress() {
        pendingLongPressRunnable?.let(longPressHandler::removeCallbacks)
        pendingLongPressRunnable = null
        pendingLongPressPointerId = null
        pendingLongPressKeyId = null
    }

    private fun scheduleBackspaceRepeat(pointerId: Int, key: KeySpec) {
        cancelBackspaceRepeat()
        repeatingBackspacePointerId = pointerId
        repeatingBackspaceKeyId = key.id
        backspaceRepeatCount = 0
        longPressHandler.postDelayed(
            backspaceRepeatRunnable,
            BackspaceRepeatTiming.startDelayMillis(),
        )
    }

    private fun repeatBackspace() {
        val pointerId = repeatingBackspacePointerId ?: return
        val keyId = repeatingBackspaceKeyId ?: return
        val touch = activeTouches.get(pointerId) ?: run {
            cancelBackspaceRepeat()
            return
        }
        val key = touch.currentKey
        if (key?.id != keyId || !isBackspaceKey(key)) {
            cancelBackspaceRepeat()
            return
        }

        touch.repeatConsumed = true
        touch.backspaceGesture = null
        backspaceRepeatCount += 1
        handleKey(key)
        invalidate()
        longPressHandler.postDelayed(
            backspaceRepeatRunnable,
            BackspaceRepeatTiming.repeatDelayMillis(backspaceRepeatCount),
        )
    }

    private fun cancelBackspaceRepeatFor(pointerId: Int) {
        if (repeatingBackspacePointerId == pointerId) {
            cancelBackspaceRepeat()
        }
    }

    private fun cancelBackspaceRepeat() {
        longPressHandler.removeCallbacks(backspaceRepeatRunnable)
        repeatingBackspacePointerId = null
        repeatingBackspaceKeyId = null
        backspaceRepeatCount = 0
    }

    private fun isBackspaceKey(key: KeySpec): Boolean {
        return (key.intent as? KeyIntent.Dispatch)?.action == KeyboardAction.Backspace
    }

    /** [next] is the key drawn after this one; a [KeySpec.mergesWithNext] key stretches its pill over it. */
    private fun drawKey(canvas: Canvas, hit: KeyHit, next: KeyHit? = null) {
        val key = hit.key
        val toolbar = hit.rowStyle == RowStyle.Toolbar
        // A toolbar label (润色, 翻译 and its chip) sits on a pill lower than the row, as in the candidate bar.
        val keyBounds = if (toolbar && key.icon == null) toolbarPill(hit.visualBounds) else hit.visualBounds
        val bounds = if (key.chip) chipBounds(keyBounds) else keyBounds
        val backgroundBounds = if (key.mergesWithNext && next != null) {
            mergedKeyBounds.set(bounds.left, bounds.top, next.visualBounds.right, bounds.bottom)
            mergedKeyBounds
        } else {
            bounds
        }
        val rewriteLoading = key.intent == KeyIntent.AgentRewrite && shortcutRowState.loading
        val pressed = isKeyPressed(key.id)
        val selected = key.active && !rewriteLoading
        val keyAlpha = if (key.enabled || key.emphasizedWhenDisabled) 255 else DISABLED_KEY_ALPHA
        val radius = if (key.chip) bounds.height() / 2f else keyRadius(bounds, toolbar)
        val isActionKey = key.role == KeyRole.Action && key.id == "enter"
        // Space is typed as often as letters; it shares their surface rather than looking like a modifier.
        val isSpace = key.id == "space" || key.id == "phone_space"
        val layered = toolbar && key.mergesWithNext && next?.key?.chip == true
        val drawBackground = when {
            // The language is the lower layer of 翻译's capsule, drawn with it; pressed, it shades.
            key.chip && toolbar -> pressed
            layered -> false
            pressed || selected || rewriteLoading || isSpace || key.chip -> true
            // Shortcut rows read as a quiet toolbar: content only, with a pill on press / latch.
            toolbar -> key.intent == KeyIntent.AgentRewrite || key.intent == KeyIntent.AgentPolishOnly
            key.role == KeyRole.Function -> functionKeyBackgroundsEnabled
            else -> true
        }
        val containerColor = when {
            key.chip && toolbar -> ColorUtils.blendARGB(ImeColors.BACKGROUND, translateBlue, 0.22f)
            key.chip -> ImeColors.TRANSLATE_BADGE
            rewriteLoading -> PALETTE.aiLoadingSurface
            selected -> PALETTE.selected
            toolbar -> PALETTE.function
            isActionKey -> PALETTE.action
            isSpace -> PALETTE.key
            key.role == KeyRole.Function || key.role == KeyRole.Action -> PALETTE.function
            hit.rowStyle == RowStyle.Numbers -> PALETTE.keyQuiet
            else -> PALETTE.key
        }
        val contentColor = when {
            key.chip && toolbar -> translateInk()
            layered -> translateInk()
            key.chip -> ImeColors.ON_TRANSLATE_BADGE
            rewriteLoading -> PALETTE.text
            selected -> PALETTE.onSelected
            isActionKey -> PALETTE.onAction
            key.role == KeyRole.Character -> PALETTE.text
            else -> PALETTE.mutedText
        }

        keyPaint.style = Paint.Style.FILL
        keyPaint.color = if (pressed) {
            ImePressFeedback.contentStateLayer(containerColor, contentColor, PRESSED_LAYER_OPACITY)
        } else {
            containerColor
        }
        keyPaint.alpha = keyAlpha

        if (key.id == "space") {
            voiceIndicator?.let { indicator ->
                drawVoiceSpace(canvas, indicator, bounds, radius)
                return
            }
        }

        // 翻译 greys out over an empty field, as on iOS; its language stays as it is.
        val dimmed = layered && !rewriteLoading && !shortcutRowState.hasText
        if (layered && next != null) {
            translateLayers.draw(canvas, backgroundBounds, next.visualBounds.left, pressed, keyAlpha, dimmed)
        } else if (drawBackground) {
            canvas.drawRoundRect(backgroundBounds, radius, radius, keyPaint)
        }

        if (rewriteLoading && !layered) {
            drawRewriteLoadingWash(canvas, backgroundBounds, radius)
        }

        val contentAlpha = when {
            dimmed -> DIMMED_TRANSLATE_ALPHA
            key.enabled || key.emphasizedWhenDisabled -> 255
            else -> DISABLED_CONTENT_ALPHA
        }
        // A pill that runs on under a round badge centers its label in the space left of the badge.
        val labelBounds = if (key.mergesWithNext && next?.key?.chip == true) {
            val chipLeft = if (layered) next.visualBounds.left - dp(TranslateLayers.SLANT_DP) else next.visualBounds.left + dp(ImeDimensions.TRANSLATE_CHIP_INSET_DP)
            mergedLabelBounds.set(bounds.left, bounds.top, chipLeft, bounds.bottom)
            mergedLabelBounds
        } else {
            bounds
        }

        if (rewriteLoading && layered) {
            drawTranslateFlow(canvas, labelBounds, contentColor)
        } else if (rewriteLoading) {
            drawRewriteLoadingContent(canvas, key.label, labelBounds, contentColor, contentAlpha)
        } else if (key.chip) {
            drawLanguageChipContent(canvas, key.label, bounds, contentColor, contentAlpha)
        } else if (key.id == PINYIN_MODE_KEY_ID) {
            drawLanguageToggle(canvas, bounds, contentColor, contentAlpha)
        } else key.icon?.let {
            drawIcon(canvas, it, bounds, contentColor, contentAlpha, toolbar)
        } ?: run {
            if (key.subLabel != null) {
                when (key.subLabelStyle) {
                    SubLabelStyle.Small -> drawStackedLabel(canvas, key.label, key.subLabel, bounds, contentColor, contentAlpha)
                    SubLabelStyle.Equal -> drawEqualStackedLabel(canvas, key.label, key.subLabel, bounds, contentColor, contentAlpha)
                }
            } else {
                val role = if (toolbar && key.role == KeyRole.Character && key.label.length > 1) KeyRole.Function else key.role
                val labelIcon = when {
                    !toolbar -> null
                    key.intent == KeyIntent.AgentPolishOnly -> polishIcon
                    key.intent == KeyIntent.AgentRewrite -> translateIcon
                    else -> null
                }
                if (labelIcon != null) {
                    drawIconLabel(canvas, labelIcon, key.label, labelBounds, contentColor, contentAlpha, hit.rowStyle)
                    return@run
                }
                val textSize = fitTextSize(key.label, labelBounds, role, hit.rowStyle)
                textPaint.typeface = if (role == KeyRole.Character) letterTypeface else labelTypeface
                textPaint.textSize = textSize
                textPaint.color = contentColor
                textPaint.alpha = contentAlpha
                if (key.label in FULL_WIDTH_MARKS) {
                    // A full-width mark is drawn in a corner of its em box (，sits low and left), so
                    // center its ink rather than its box.
                    textPaint.getTextBounds(key.label, 0, key.label.length, glyphBounds)
                    textPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(key.label, labelBounds.centerX() - glyphBounds.exactCenterX(), bounds.centerY() - glyphBounds.exactCenterY(), textPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                } else {
                    val metrics = textPaint.fontMetrics
                    // Letters sit a little above center, as on Gboard, where the eye expects them.
                    val lift = if (role == KeyRole.Character && hit.rowStyle == RowStyle.Standard) bounds.height() * LETTER_LIFT_FRACTION else 0f
                    val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f - lift
                    canvas.drawText(key.label, labelBounds.centerX(), baseline, textPaint)
                }
                textPaint.typeface = letterTypeface
            }
        }
        drawLongPressHint(canvas, key.longPressHint, bounds, contentColor, contentAlpha)

        if (key.id == "shift" && shiftState == ShiftState.Locked) {
            drawShiftLockIndicator(canvas, bounds, contentColor)
        }
    }

    /** The space key during voice input: red while recording, then a status line. */
    private fun drawVoiceSpace(canvas: Canvas, indicator: VoiceIndicator, bounds: RectF, radius: Float) {
        val chinese = pinyinModeEnabled
        val recording = indicator as? VoiceIndicator.Recording
        val surface = when {
            recording != null && voiceCancelArmed -> PALETTE.function
            recording != null -> ImeColors.VOICE_RECORDING
            indicator == VoiceIndicator.Processing -> PALETTE.aiLoadingSurface
            else -> PALETTE.key
        }
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = surface
        keyPaint.alpha = 255
        canvas.drawRoundRect(bounds, radius, radius, keyPaint)
        if (indicator == VoiceIndicator.Processing) {
            drawRewriteLoadingWash(canvas, bounds, radius)
        }

        val label = when (indicator) {
            is VoiceIndicator.Recording -> when {
                voiceCancelArmed -> if (chinese) "松开取消" else "Release to cancel"
                else -> if (chinese) "录音中" else "Recording"
            }
            VoiceIndicator.Processing -> if (chinese) "识别中…" else "Transcribing…"
            is VoiceIndicator.Message -> indicator.text
        }
        val contentColor = if (recording != null && !voiceCancelArmed) ImeColors.ON_VOICE_RECORDING else PALETTE.text
        textPaint.typeface = labelTypeface
        textPaint.textSize = fitTextSize(label, bounds, KeyRole.Function)
        textPaint.color = contentColor
        textPaint.alpha = 255
        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, textPaint)
        textPaint.typeface = letterTypeface
    }

    /**
     * Recording panel over the keys above the space row: the finger covers the space key, so the
     * hints live up here. Its top part is the cancel zone the finger slides into.
     */
    /** Everything under the toolbar, where the microphone that started the recording sits. */
    private fun voiceOverlayBounds(): RectF? {
        val mic = hitTargets.firstOrNull { it.key.intent == KeyIntent.VoiceInput } ?: return null
        val inset = dp(VOICE_OVERLAY_INSET_DP)
        val top = mic.visualBounds.bottom + inset
        if (height - inset - top < dp(VOICE_OVERLAY_MIN_HEIGHT_DP)) return null
        // Down to the last key row, clear of the bottom padding and the system's IME buttons.
        var keysBottom = 0f
        for (target in hitTargets) keysBottom = max(keysBottom, target.visualBounds.bottom)
        val bottom = if (keysBottom > top) keysBottom else height - inset
        voiceOverlayRect.set(inset, top, width - inset, bottom)
        return voiceOverlayRect
    }

    /** Sliding up off the keyboard, past the microphone, cancels. */
    private fun voiceCancelBoundaryY(): Float? {
        val mic = hitTargets.firstOrNull { it.key.intent == KeyIntent.VoiceInput } ?: return null
        return mic.visualBounds.top - dp(VOICE_CANCEL_SLIDE_DP)
    }

    /**
     * The recording over the whole keyboard: rings spreading from a red microphone and three
     * waves across, their height following the voice, moving every frame; the timer and how to
     * send or cancel below. Allocation-free: the waves reuse [voiceWavePath].
     */
    private fun drawVoiceOverlay(canvas: Canvas, recording: VoiceIndicator.Recording) {
        val bounds = voiceOverlayBounds() ?: return
        val chinese = pinyinModeEnabled
        val armed = voiceCancelArmed
        val radius = dp(VOICE_OVERLAY_RADIUS_DP)
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = if (armed) ImeColors.VOICE_RECORDING else PALETTE.background
        keyPaint.alpha = 250
        canvas.drawRoundRect(bounds, radius, radius, keyPaint)
        val textColor = if (armed) ImeColors.ON_VOICE_RECORDING else PALETTE.text
        textPaint.typeface = labelTypeface
        textPaint.alpha = 255
        if (armed) {
            val centerY = bounds.centerY()
            iconPaint.style = Paint.Style.STROKE
            iconPaint.color = textColor
            iconPaint.alpha = 255
            iconPaint.strokeWidth = dp(3f)
            val half = dp(14f)
            val crossY = centerY - dp(18f)
            canvas.drawLine(bounds.centerX() - half, crossY - half, bounds.centerX() + half, crossY + half, iconPaint)
            canvas.drawLine(bounds.centerX() + half, crossY - half, bounds.centerX() - half, crossY + half, iconPaint)
            drawOverlayText(canvas, if (chinese) "松开取消" else "Release to cancel", bounds.centerX(), centerY + dp(28f), dp(20f), textColor)
            textPaint.typeface = letterTypeface
            return
        }

        // The voice's level eases towards the newest reading, so the waves swell and settle.
        voiceDisplayLevel += (recording.level - voiceDisplayLevel) * VOICE_LEVEL_EASING
        val time = SystemClock.uptimeMillis() / 1000f
        val centerX = bounds.centerX()
        val centerY = bounds.top + bounds.height() * 0.42f

        // Rings: each spreads from the microphone and fades, a new one every third of a period.
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = ImeColors.VOICE_RECORDING
        iconPaint.strokeWidth = dp(2f)
        val micRadius = dp(26f)
        val reach = min(bounds.width(), bounds.height()) * 0.55f
        for (ring in 0 until VOICE_RINGS) {
            val phase = ((time / VOICE_RING_PERIOD_S) + ring / VOICE_RINGS.toFloat()) % 1f
            val r = micRadius + (reach - micRadius) * phase * (0.6f + 0.4f * voiceDisplayLevel)
            iconPaint.alpha = ((1f - phase) * 150).toInt()
            canvas.drawCircle(centerX, centerY, r, iconPaint)
        }

        // Waves across the keyboard, the front one strongest.
        val amplitude = dp(6f) + bounds.height() * 0.22f * voiceDisplayLevel
        for (layer in 0 until VOICE_WAVES) {
            voiceWavePath.rewind()
            val strength = 1f - layer * 0.3f
            val speed = 2.2f + layer * 0.9f
            val cycles = 1.6f + layer * 0.5f
            var x = bounds.left
            val step = dp(4f)
            while (x <= bounds.right) {
                val u = (x - bounds.left) / bounds.width()
                // Tapered at both ends, so the waves rise out of the edges.
                val envelope = kotlin.math.sin(Math.PI.toFloat() * u)
                val y = centerY + amplitude * strength * envelope *
                    kotlin.math.sin(2f * Math.PI.toFloat() * cycles * u + time * speed + layer * 1.7f)
                if (x == bounds.left) voiceWavePath.moveTo(x, y) else voiceWavePath.lineTo(x, y)
                x += step
            }
            iconPaint.strokeWidth = dp(if (layer == 0) 3f else 2f)
            iconPaint.color = if (layer == 0) ImeColors.VOICE_RECORDING else PALETTE.mutedText
            iconPaint.alpha = if (layer == 0) 230 else 90
            canvas.drawPath(voiceWavePath, iconPaint)
        }

        // The microphone itself, on a red disc over the waves.
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = ImeColors.VOICE_RECORDING
        iconPaint.alpha = 255
        canvas.drawCircle(centerX, centerY, micRadius * (1f + 0.12f * voiceDisplayLevel), iconPaint)
        canvas.save()
        val glyph = dp(26f)
        canvas.translate(centerX - glyph / 2f, centerY - glyph / 2f)
        canvas.scale(glyph / ImeLayout.ICON_VIEWPORT, glyph / ImeLayout.ICON_VIEWPORT)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = ImeColors.ON_VOICE_RECORDING
        iconPaint.strokeWidth = TOOLBAR_ICON_STROKE
        canvas.drawPath(micIconStroke, iconPaint)
        canvas.restore()

        drawOverlayText(canvas, formatVoiceTime(recording.elapsedMs), centerX, bounds.top + bounds.height() * 0.74f, dp(18f), textColor)
        drawOverlayText(
            canvas,
            if (chinese) "松开发送 · 滑到 ✕ 取消" else "Release to send · slide to ✕ to cancel",
            centerX,
            bounds.top + bounds.height() * 0.88f,
            dp(15f),
            PALETTE.mutedText,
        )
        voiceCancelTarget()?.let { target ->
            val r = dp(VOICE_CANCEL_TARGET_DP)
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = PALETTE.function
            iconPaint.alpha = 255
            canvas.drawCircle(target.x, target.y, r, iconPaint)
            drawCross(canvas, target.x, target.y, r * 0.36f, PALETTE.text)
        }
        textPaint.typeface = letterTypeface
        // Keep moving while recording.
        postInvalidateOnAnimation()
    }

    /** Hold-to-talk: a round ✕ just under the microphone; sliding the finger onto it cancels. */
    private fun voiceCancelTarget(): PointF? {
        val bounds = voiceOverlayBounds() ?: return null
        val mic = hitTargets.firstOrNull { it.key.intent == KeyIntent.VoiceInput } ?: return null
        val r = dp(VOICE_CANCEL_TARGET_DP)
        val x = mic.visualBounds.centerX().coerceIn(bounds.left + r + dp(8f), bounds.right - r - dp(8f))
        voiceCancelPoint.set(x, bounds.top + r + dp(14f))
        return voiceCancelPoint
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = color
        iconPaint.alpha = 255
        iconPaint.strokeWidth = dp(2.5f)
        val cap = iconPaint.strokeCap
        iconPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, iconPaint)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, iconPaint)
        iconPaint.strokeCap = cap
    }

    private fun openVoicePanel() {
        voicePanel = true
        voicePanelPressed = -1
        voiceHeardSpeech = false
        voiceQuietSinceMs = 0L
        if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** The panel takes every touch: its three buttons, and the microphone again as 完成. */
    private fun handleVoicePanelTouch(event: MotionEvent) {
        val x = event.getX(event.actionIndex)
        val y = event.getY(event.actionIndex)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                voicePanelPressed = voicePanelButtonAt(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (voicePanelPressed >= 0 && voicePanelButtonAt(x, y) != voicePanelPressed) {
                voicePanelPressed = -1
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val button = voicePanelPressed.takeIf { it >= 0 && voicePanelButtonAt(x, y) == it }
                voicePanelPressed = -1
                invalidate()
                if (button != null) pressVoicePanelButton(button)
            }
            MotionEvent.ACTION_CANCEL -> {
                voicePanelPressed = -1
                invalidate()
            }
        }
    }

    private fun voicePanelButtonAt(x: Float, y: Float): Int {
        val processing = voiceIndicator == VoiceIndicator.Processing
        val index = voicePanelButtons.indexOfFirst { it.contains(x, y) }
        if (index >= 0) return if (processing && index != VOICE_BUTTON_CANCEL) -1 else index
        val mic = hitTargets.firstOrNull { it.key.intent == KeyIntent.VoiceInput }
        return if (!processing && mic?.visualBounds?.contains(x, y) == true) VOICE_BUTTON_DONE else -1
    }

    private fun pressVoicePanelButton(button: Int) {
        if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (button) {
            VOICE_BUTTON_CANCEL -> callback?.onVoiceInputCancelled()
            VOICE_BUTTON_DONE -> callback?.onVoiceInputFinished(cancelled = false)
            VOICE_BUTTON_RETRY -> {
                callback?.onVoiceInputCancelled()
                if (callback?.onVoiceInputStarted() == true) openVoicePanel()
            }
        }
    }

    /**
     * Hands-free recording over the keys: what is happening and the time on top, the microphone
     * and its ripple in the middle, then 取消 / 完成 / 重说. [recording] null is transcribing,
     * where only 取消 still works.
     */
    private fun drawVoicePanel(canvas: Canvas, recording: VoiceIndicator.Recording?) {
        val bounds = voiceOverlayBounds() ?: return
        val chinese = pinyinModeEnabled
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = PALETTE.background
        keyPaint.alpha = 255
        // Square and edge to edge, so no key corner shows around it.
        canvas.drawRect(0f, bounds.top - dp(VOICE_OVERLAY_INSET_DP), width.toFloat(), bounds.bottom + dp(VOICE_OVERLAY_INSET_DP), keyPaint)
        textPaint.typeface = labelTypeface
        val side = dp(16f)

        // Header: a red dot and 正在聆听, or 识别中; the time on the right.
        val headerY = bounds.top + dp(20f)
        val title = when {
            recording == null -> if (chinese) "已停止录音" else "Stopped"
            else -> if (chinese) "正在聆听" else "Listening"
        }
        var titleX = bounds.left + side
        if (recording != null) {
            iconPaint.style = Paint.Style.FILL
            iconPaint.color = ImeColors.VOICE_RECORDING
            iconPaint.alpha = 255
            canvas.drawCircle(titleX + dp(4f), headerY, dp(4f), iconPaint)
            titleX += dp(16f)
        }
        textPaint.textAlign = Paint.Align.LEFT
        drawOverlayText(canvas, title, titleX, headerY, dp(15f), if (recording != null) PALETTE.text else PALETTE.mutedText)
        recording?.let {
            textPaint.textAlign = Paint.Align.RIGHT
            drawOverlayText(canvas, formatVoiceTime(it.elapsedMs), bounds.right - side, headerY, dp(15f), PALETTE.mutedText)
        }
        textPaint.textAlign = Paint.Align.CENTER

        // Buttons along the bottom: 取消 | 完成 (wider, accent) | 重说.
        val buttonHeight = dp(48f)
        val gap = dp(10f)
        val buttonsTop = bounds.bottom - dp(12f) - buttonHeight
        val unit = (bounds.width() - 2 * side - 2 * gap) / 3.5f
        var left = bounds.left + side
        for (i in 0 until 3) {
            val w = if (i == VOICE_BUTTON_DONE) unit * 1.5f else unit
            voicePanelButtons[i].set(left, buttonsTop, left + w, buttonsTop + buttonHeight)
            left += w + gap
        }
        for (i in 0 until 3) {
            val rect = voicePanelButtons[i]
            val enabled = recording != null || i == VOICE_BUTTON_CANCEL
            val main = i == VOICE_BUTTON_DONE
            keyPaint.color = when {
                main && recording == null -> ColorUtils.blendARGB(ImeColors.ACCENT, PALETTE.background, 0.55f)
                main -> ImeColors.ACCENT
                else -> PALETTE.function
            }
            keyPaint.alpha = if (enabled || main) 255 else 110
            if (i == voicePanelPressed) keyPaint.color = ColorUtils.blendARGB(keyPaint.color, PALETTE.text, 0.18f)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), keyPaint)
            val label = when (i) {
                VOICE_BUTTON_CANCEL -> if (chinese) "✕  取消" else "✕  Cancel"
                VOICE_BUTTON_DONE -> if (recording == null) (if (chinese) "识别中…" else "Transcribing…") else (if (chinese) "✓  完成" else "✓  Done")
                else -> if (chinese) "↺  重说" else "↺  Redo"
            }
            val color = if (main) ImeColors.ON_ACTION else PALETTE.text
            drawOverlayText(canvas, label, rect.centerX(), rect.centerY(), dp(15.5f), color)
            if (!enabled && !main) {
                // Dim the label with the button.
                keyPaint.color = PALETTE.background
                keyPaint.alpha = 130
                canvas.drawRoundRect(rect, dp(14f), dp(14f), keyPaint)
            }
        }

        // The microphone and ripple between header and buttons; a hint just above the buttons.
        val hintY = buttonsTop - dp(16f)
        val centerX = bounds.centerX()
        val centerY = (headerY + dp(14f) + hintY - dp(10f)) / 2f
        val micRadius = dp(24f)
        if (recording != null) {
            voiceDisplayLevel += (recording.level - voiceDisplayLevel) * VOICE_LEVEL_EASING
            val time = SystemClock.uptimeMillis() / 1000f
            val reach = min(bounds.width() / 2f, (hintY - headerY) * 0.6f)
            iconPaint.style = Paint.Style.STROKE
            iconPaint.color = ImeColors.VOICE_RECORDING
            iconPaint.strokeWidth = dp(2f)
            for (ring in 0 until VOICE_RINGS) {
                val phase = ((time / VOICE_RING_PERIOD_S) + ring / VOICE_RINGS.toFloat()) % 1f
                val r = micRadius + (reach - micRadius) * phase * (0.6f + 0.4f * voiceDisplayLevel)
                iconPaint.alpha = ((1f - phase) * 150).toInt()
                canvas.drawCircle(centerX, centerY, r, iconPaint)
            }
        }
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = if (recording != null) ImeColors.VOICE_RECORDING else PALETTE.function
        iconPaint.alpha = 255
        canvas.drawCircle(centerX, centerY, micRadius * (1f + 0.12f * (if (recording != null) voiceDisplayLevel else 0f)), iconPaint)
        canvas.save()
        val glyph = dp(24f)
        canvas.translate(centerX - glyph / 2f, centerY - glyph / 2f)
        canvas.scale(glyph / ImeLayout.ICON_VIEWPORT, glyph / ImeLayout.ICON_VIEWPORT)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = if (recording != null) ImeColors.ON_VOICE_RECORDING else PALETTE.mutedText
        iconPaint.strokeWidth = TOOLBAR_ICON_STROKE
        canvas.drawPath(micIconStroke, iconPaint)
        canvas.restore()
        val hint = if (recording != null) {
            if (chinese) "说完停顿 3 秒自动完成 · 返回键取消" else "Pause 3 s to finish · Back to cancel"
        } else {
            if (chinese) "识别完成后自动上屏" else "The text goes in when it's ready"
        }
        drawOverlayText(canvas, hint, centerX, hintY, dp(12.5f), PALETTE.mutedText)
        textPaint.typeface = letterTypeface
        postInvalidateOnAnimation()
    }

    /** A bubble under the microphone, once: how tap and hold differ. */
    private fun drawVoiceTip(canvas: Canvas) {
        val mic = hitTargets.firstOrNull { it.key.intent == KeyIntent.VoiceInput } ?: return
        val text = if (pinyinModeEnabled) "轻点开始录音 · 按住说话松开发送" else "Tap to record · hold to talk"
        textPaint.typeface = labelTypeface
        textPaint.textSize = dp(13f)
        val padX = dp(12f)
        val w = textPaint.measureText(text) + 2 * padX
        val h = dp(34f)
        val arrow = dp(6f)
        val top = mic.visualBounds.bottom + arrow + dp(2f)
        val left = (mic.visualBounds.centerX() - w / 2f).coerceIn(dp(6f), width - dp(6f) - w)
        voiceTipRect.set(left, top, left + w, top + h)
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = ImeColors.ACCENT
        keyPaint.alpha = 255
        canvas.drawRoundRect(voiceTipRect, dp(10f), dp(10f), keyPaint)
        voiceWavePath.rewind()
        val ax = mic.visualBounds.centerX()
        voiceWavePath.moveTo(ax - arrow, top)
        voiceWavePath.lineTo(ax, top - arrow)
        voiceWavePath.lineTo(ax + arrow, top)
        voiceWavePath.close()
        canvas.drawPath(voiceWavePath, keyPaint)
        drawOverlayText(canvas, text, voiceTipRect.centerX(), voiceTipRect.centerY(), dp(13f), ImeColors.ON_ACTION)
        textPaint.typeface = letterTypeface
    }

    /** Draws [text] centered on ([centerX], [centerY]). */
    private fun drawOverlayText(canvas: Canvas, text: String, centerX: Float, centerY: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = 255
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, centerX, centerY - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    /** m:ss, rebuilt only when the second changes. */
    private fun formatVoiceTime(elapsedMs: Long): String {
        val seconds = elapsedMs / 1000
        if (seconds != voiceTimerSeconds) {
            voiceTimerSeconds = seconds
            voiceTimerText = "%d:%02d".format(seconds / 60, seconds % 60)
        }
        return voiceTimerText
    }

    /** Capsule inset in the chip key's slot; a toolbar's half-height radius rounds its ends. */
    /** An icon, then the label, centered together; the label gives way first if the key is narrow. */
    private fun drawIconLabel(canvas: Canvas, icon: Drawable, label: String, bounds: RectF, color: Int, alpha: Int, rowStyle: RowStyle) {
        val iconSize = dp(ImeDimensions.TOOLBAR_LABEL_ICON_DP)
        val gap = dp(ImeDimensions.TOOLBAR_LABEL_ICON_GAP_DP)
        iconLabelBounds.set(bounds.left + iconSize + gap, bounds.top, bounds.right, bounds.bottom)
        textPaint.typeface = labelTypeface
        textPaint.textSize = fitTextSize(label, iconLabelBounds, KeyRole.Function, rowStyle)
        textPaint.color = color
        textPaint.alpha = alpha
        val textWidth = textPaint.measureText(label)
        val left = bounds.centerX() - (iconSize + gap + textWidth) / 2f
        val iconTop = bounds.centerY() - iconSize / 2f
        icon.setTint(color)
        icon.alpha = alpha
        icon.setBounds(left.roundToInt(), iconTop.roundToInt(), (left + iconSize).roundToInt(), (iconTop + iconSize).roundToInt())
        icon.draw(canvas)
        val metrics = textPaint.fontMetrics
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left + iconSize + gap, bounds.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = letterTypeface
    }

    private fun translateInk(): Int = TranslateLayers.ink()

    private val translateBlue: Int get() = TranslateLayers.blue()

    /**
     * While translating, 翻译's place shows three dots lighting up left to right and then an
     * arrowhead pointing at the language: on its way there. Redrawn every frame while loading.
     */
    private fun drawTranslateFlow(canvas: Canvas, bounds: RectF, color: Int) {
        val dot = dp(3f)
        val gap = dp(5f)
        val head = dp(5f)
        val width = 3 * dot * 2 + 3 * gap + head
        var x = bounds.centerX() - width / 2f + dot
        val y = bounds.centerY()
        val phase = (SystemClock.uptimeMillis() % TRANSLATE_FLOW_CYCLE_MS) / TRANSLATE_FLOW_CYCLE_MS.toFloat()
        fun lit(index: Int): Int {
            val start = index * 0.15f
            val t = phase - start
            val glow = if (t in 0f..0.35f) 1f - abs(t - 0.15f) / 0.2f else 0f
            return (255 * (0.25f + 0.75f * glow.coerceIn(0f, 1f))).toInt()
        }
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = color
        for (index in 0 until 3) {
            keyPaint.alpha = lit(index)
            canvas.drawCircle(x, y, dot, keyPaint)
            x += dot * 2 + gap
        }
        val left = x - dot
        chipChevronPath.reset()
        chipChevronPath.moveTo(left, y - head)
        chipChevronPath.lineTo(left + head, y)
        chipChevronPath.lineTo(left, y + head)
        keyPaint.style = Paint.Style.STROKE
        keyPaint.strokeWidth = dp(2f)
        keyPaint.strokeCap = Paint.Cap.ROUND
        keyPaint.strokeJoin = Paint.Join.ROUND
        keyPaint.alpha = lit(3)
        canvas.drawPath(chipChevronPath, keyPaint)
        keyPaint.style = Paint.Style.FILL
    }

    private fun toolbarPill(bounds: RectF): RectF {
        val height = min(bounds.height(), dp(ImeDimensions.TOOLBAR_PILL_HEIGHT_DP))
        val top = bounds.centerY() - height / 2f
        toolbarPillBounds.set(bounds.left, top, bounds.right, top + height)
        return toolbarPillBounds
    }

    private fun chipBounds(bounds: RectF): RectF {
        val inset = dp(ImeDimensions.TRANSLATE_CHIP_INSET_DP)
        val height = bounds.height() * ImeDimensions.TRANSLATE_CHIP_HEIGHT_FRACTION
        val top = bounds.centerY() - height / 2f
        chipKeyBounds.set(bounds.left + inset, top, bounds.right - inset, top + height)
        return chipKeyBounds
    }

    /** "EN" plus a small down chevron, centered together: the chip opens the language list. */
    private fun drawLanguageChipContent(canvas: Canvas, label: String, bounds: RectF, color: Int, alpha: Int) {
        textPaint.typeface = labelTypeface
        textPaint.textSize = bounds.height() * ImeDimensions.TRANSLATE_CHIP_TEXT_FRACTION
        textPaint.color = color
        textPaint.alpha = alpha
        val textWidth = textPaint.measureText(label)
        val chevronWidth = bounds.height() * 0.22f
        val gap = bounds.height() * 0.12f
        val left = bounds.centerX() - (textWidth + gap + chevronWidth) / 2f
        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, left + textWidth / 2f, baseline, textPaint)
        val chevronLeft = left + textWidth + gap
        val chevronTop = bounds.centerY() - chevronWidth * 0.25f
        chipChevronPath.reset()
        chipChevronPath.moveTo(chevronLeft, chevronTop)
        chipChevronPath.lineTo(chevronLeft + chevronWidth, chevronTop)
        chipChevronPath.lineTo(chevronLeft + chevronWidth / 2f, chevronTop + chevronWidth * 0.55f)
        chipChevronPath.close()
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = color
        keyPaint.alpha = alpha
        canvas.drawPath(chipChevronPath, keyPaint)
        textPaint.typeface = letterTypeface
    }

    private fun keyRadius(bounds: RectF, toolbar: Boolean): Float {
        val preferred = dp(ImeLayout.KEY_RADIUS_DP.toFloat())
        val cap = bounds.height() * if (toolbar) 0.5f else 0.3f
        return min(preferred, cap)
    }

    /**
     * Character preview above the finger. The finger occludes the very key it is pressing, so a
     * visible, offset confirmation is what lets users trust a tap without looking for the result.
     */
    private fun drawKeyPreviews(canvas: Canvas) {
        for (index in 0 until activeTouches.size()) {
            val touch = activeTouches.valueAt(index)
            val key = touch.currentKey ?: continue
            if (key.role != KeyRole.Character || key.icon != null || key.label.length > 2) continue
            val hit = hitTargets.firstOrNull { it.key.id == key.id } ?: continue
            if (hit.rowStyle == RowStyle.Toolbar) continue
            val bounds = hit.visualBounds
            val bubbleWidth = max(bounds.width() * PREVIEW_WIDTH_FACTOR, dp(PREVIEW_MIN_WIDTH_DP))
            val bubbleHeight = bounds.height() * PREVIEW_HEIGHT_FACTOR
            var left = bounds.centerX() - bubbleWidth / 2f
            left = left.coerceIn(dp(2f), width - bubbleWidth - dp(2f))
            var top = bounds.top - bubbleHeight - dp(PREVIEW_GAP_DP)
            if (top < 0f) top = 0f
            previewBounds.set(left, top, left + bubbleWidth, top + bubbleHeight)
            val radius = min(dp(ImeLayout.KEY_RADIUS_DP.toFloat() + 4f), bubbleHeight * 0.3f)
            previewPaint.color = PALETTE.preview
            previewPaint.setShadowLayer(dp(6f), 0f, dp(2f), PREVIEW_SHADOW_COLOR)
            canvas.drawRoundRect(previewBounds, radius, radius, previewPaint)
            previewPaint.clearShadowLayer()

            textPaint.typeface = letterTypeface
            textPaint.color = PALETTE.text
            textPaint.alpha = 255
            textPaint.textSize = min(sp(PREVIEW_TEXT_SIZE_SP), bubbleHeight * 0.62f)
            val metrics = textPaint.fontMetrics
            val baseline = previewBounds.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(key.label, previewBounds.centerX(), baseline, textPaint)
        }
    }

    private fun drawStackedLabel(
        canvas: Canvas,
        label: String,
        subLabel: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)
        val primarySize = shrinkTextSizeToFit(
            label = label,
            initialSize = sp(ImeDimensions.KEY_LABEL_CHARACTER_TEXT_SIZE_SP)
                .coerceAtMost(bounds.height() * PHONE_PAD_PRIMARY_MAX_HEIGHT_FRACTION),
            maxWidth = maxWidth,
        )
        textPaint.textSize = primarySize
        val primaryMetrics = textPaint.fontMetrics
        val primaryHeight = primaryMetrics.descent - primaryMetrics.ascent

        val secondarySize = shrinkTextSizeToFit(
            label = subLabel,
            initialSize = sp(PHONE_PAD_SUB_LABEL_TEXT_SIZE_SP)
                .coerceAtMost(bounds.height() * PHONE_PAD_SUB_LABEL_MAX_HEIGHT_FRACTION),
            maxWidth = maxWidth,
        )
        textPaint.textSize = secondarySize
        val secondaryMetrics = textPaint.fontMetrics
        val secondaryHeight = secondaryMetrics.descent - secondaryMetrics.ascent
        val gap = dp(PHONE_PAD_LABEL_GAP_DP)
        val top = bounds.centerY() - (primaryHeight + gap + secondaryHeight) / 2f

        textPaint.color = color
        textPaint.alpha = alpha
        textPaint.textSize = primarySize
        canvas.drawText(label, bounds.centerX(), top - primaryMetrics.ascent, textPaint)

        textPaint.textSize = secondarySize
        textPaint.alpha = (alpha * PHONE_PAD_SUB_LABEL_ALPHA_FRACTION).roundToInt().coerceIn(0, alpha)
        canvas.drawText(subLabel, bounds.centerX(), top + primaryHeight + gap - secondaryMetrics.ascent, textPaint)
    }

    private fun drawEqualStackedLabel(
        canvas: Canvas,
        label: String,
        subLabel: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)
        val initialSize = sp(NUMERIC_TOGGLE_STACKED_TEXT_SIZE_SP)
            .coerceAtMost(bounds.height() * NUMERIC_TOGGLE_STACKED_MAX_HEIGHT_FRACTION)
        val textSize = min(
            shrinkTextSizeToFit(label, initialSize, maxWidth),
            shrinkTextSizeToFit(subLabel, initialSize, maxWidth),
        )
        textPaint.textSize = textSize
        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val gap = dp(NUMERIC_TOGGLE_STACKED_GAP_DP)
        val top = bounds.centerY() - (lineHeight * 2f + gap) / 2f

        textPaint.color = color
        textPaint.alpha = alpha
        canvas.drawText(label, bounds.centerX(), top - metrics.ascent, textPaint)
        canvas.drawText(subLabel, bounds.centerX(), top + lineHeight + gap - metrics.ascent, textPaint)
    }

    private fun drawLongPressHint(
        canvas: Canvas,
        hint: String?,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        if (hint == null) return

        textPaint.textSize = sp(ImeDimensions.LONG_PRESS_HINT_TEXT_SIZE_SP)
            .coerceAtMost(bounds.height() * ImeDimensions.LONG_PRESS_HINT_MAX_HEIGHT_FRACTION)
        textPaint.color = color
        textPaint.alpha = min(alpha, LONG_PRESS_HINT_ALPHA)
        textPaint.textAlign = Paint.Align.RIGHT
        val metrics = textPaint.fontMetrics
        val baseline = bounds.top + dp(5f) - metrics.ascent
        canvas.drawText(hint, bounds.right - dp(6f), baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** 中/英 on the language key: the language typing now at full strength, the other faded. */
    private fun drawLanguageToggle(canvas: Canvas, bounds: RectF, color: Int, alpha: Int) {
        val chinese = pinyinModeEnabled
        val big = sp(ImeDimensions.KEY_LABEL_FUNCTION_TEXT_SIZE_SP * LANGUAGE_KEY_ACTIVE_SCALE)
            .coerceAtMost(bounds.height() * ImeDimensions.KEY_LABEL_MAX_HEIGHT_FRACTION)
        val small = big * LANGUAGE_KEY_INACTIVE_SCALE
        val parts = listOf("中" to chinese, "/" to false, "英" to !chinese)
        textPaint.typeface = labelTypeface
        textPaint.color = color
        val widths = parts.map { (text, active) ->
            textPaint.textSize = if (active) big else small
            textPaint.measureText(text)
        }
        textPaint.textSize = big
        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        var left = bounds.centerX() - widths.sum() / 2f
        parts.forEachIndexed { index, (text, active) ->
            textPaint.textSize = if (active) big else small
            textPaint.alpha = if (active) alpha else (alpha * LANGUAGE_KEY_INACTIVE_ALPHA).toInt()
            canvas.drawText(text, left + widths[index] / 2f, baseline, textPaint)
            left += widths[index]
        }
        textPaint.typeface = letterTypeface
    }

    private fun drawRewriteLoadingWash(canvas: Canvas, bounds: RectF, radius: Float) {
        val shader = loadingGradientShader(bounds)
        val progress = loadingProgress()
        val offsetX = sin(TWO_PI * progress) * bounds.width() * 0.72f
        loadingGradientMatrix.setTranslate(offsetX, 0f)
        shader.setLocalMatrix(loadingGradientMatrix)

        loadingGradientPaint.shader = shader
        loadingGradientPaint.alpha = 255
        canvas.drawRoundRect(bounds, radius, radius, loadingGradientPaint)
        loadingGradientPaint.shader = null

        // A translucent surface layer keeps the moving gradient from competing with the label.
        canvas.drawRoundRect(bounds, radius, radius, loadingGlassPaint)
    }

    private fun drawRewriteLoadingContent(
        canvas: Canvas,
        label: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val textSize = fitTextSize(label, bounds, KeyRole.Function)
        textPaint.textSize = textSize
        textPaint.color = color
        textPaint.alpha = alpha

        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        val labelWidth = textPaint.measureText(label)
        val sparkleRadius = min(bounds.height() * 0.16f, dp(6.5f))
        val labelGap = dp(6f)
        val sparkleWidth = sparkleRadius * 2f
        val totalWidth = labelWidth + labelGap + sparkleWidth

        if (totalWidth > bounds.width() - dp(6f)) {
            canvas.drawText(label, bounds.centerX(), baseline, textPaint)
            return
        }

        val labelLeft = bounds.centerX() - totalWidth / 2f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, labelLeft, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        val progress = loadingProgress()
        val pulse = ((sin(TWO_PI * (progress + 0.12f)) + 1f) / 2f)
        val scale = 0.78f + pulse * 0.28f
        val rotation = progress * 360f
        val sparkleCenterX = labelLeft + labelWidth + labelGap + sparkleRadius
        val sparkleCenterY = bounds.centerY()
        loadingSparklePaint.color = color
        loadingSparklePaint.alpha = (150 + pulse * 105).roundToInt().coerceAtMost(alpha)

        canvas.save()
        canvas.translate(sparkleCenterX, sparkleCenterY)
        canvas.rotate(rotation)
        canvas.scale(sparkleRadius * scale, sparkleRadius * scale)
        canvas.drawPath(loadingSparklePath, loadingSparklePaint)
        canvas.restore()

        val miniPulse = ((sin(TWO_PI * (progress + 0.58f)) + 1f) / 2f)
        loadingSparklePaint.alpha = (72 + miniPulse * 80).roundToInt().coerceAtMost(alpha)
        canvas.save()
        canvas.translate(
            sparkleCenterX + cos(TWO_PI * progress) * sparkleRadius * 1.1f,
            sparkleCenterY + sin(TWO_PI * progress) * sparkleRadius * 0.72f,
        )
        canvas.rotate(-rotation * 2f + 35f)
        canvas.scale(sparkleRadius * 0.36f, sparkleRadius * 0.36f)
        canvas.drawPath(loadingSparklePath, loadingSparklePaint)
        canvas.restore()
    }

    private fun loadingGradientShader(bounds: RectF): LinearGradient {
        val existingShader = loadingGradientShader
        if (
            existingShader != null &&
            loadingShaderBounds.left == bounds.left &&
            loadingShaderBounds.top == bounds.top &&
            loadingShaderBounds.right == bounds.right &&
            loadingShaderBounds.bottom == bounds.bottom
        ) {
            return existingShader
        }
        loadingShaderBounds.set(bounds)
        val gradientWidth = bounds.width() * 1.45f
        return LinearGradient(
            bounds.left - gradientWidth,
            bounds.top,
            bounds.right + gradientWidth,
            bounds.bottom,
            AI_LOADING_COLORS,
            AI_LOADING_POSITIONS,
            Shader.TileMode.CLAMP,
        ).also { shader ->
            loadingGradientShader = shader
        }
    }

    private fun loadingProgress(): Float {
        return (SystemClock.uptimeMillis() % AI_LOADING_CYCLE_MS) / AI_LOADING_CYCLE_MS.toFloat()
    }

    private fun drawIcon(canvas: Canvas, icon: KeyIcon, bounds: RectF, color: Int, alpha: Int, toolbar: Boolean = false) {
        val iconSize = if (toolbar) {
            min(dp(ImeDimensions.TOOLBAR_ICON_DP), min(bounds.width(), bounds.height()) * 0.7f)
        } else {
            min(bounds.width(), bounds.height()) * 0.5f
        }
        val left = bounds.centerX() - iconSize / 2f
        val top = bounds.centerY() - iconSize / 2f

        iconPaint.color = color
        iconPaint.alpha = alpha
        iconPaint.strokeWidth = 2.2f
        iconPaint.style = Paint.Style.STROKE

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(iconSize / ImeLayout.ICON_VIEWPORT, iconSize / ImeLayout.ICON_VIEWPORT)
        when (icon) {
            KeyIcon.ArrowLeft -> drawArrowIcon(canvas, ArrowDirection.Left)
            KeyIcon.ArrowUp -> drawArrowIcon(canvas, ArrowDirection.Up)
            KeyIcon.ArrowDown -> drawArrowIcon(canvas, ArrowDirection.Down)
            KeyIcon.ArrowRight -> drawArrowIcon(canvas, ArrowDirection.Right)
            KeyIcon.Shift -> drawShiftIcon(canvas)
            KeyIcon.Delete -> drawDeleteIcon(canvas)
            KeyIcon.Search -> drawSearchIcon(canvas)
            KeyIcon.Enter -> drawEnterIcon(canvas)
            KeyIcon.Space -> drawSpaceIcon(canvas)
            KeyIcon.Clipboard -> drawToolbarIcon(canvas, clipboardIconStroke, clipboardIconFill)
            KeyIcon.Phrases -> drawToolbarIcon(canvas, phrasesIconStroke, null)
            KeyIcon.Help -> drawToolbarIcon(canvas, helpIconStroke, helpIconFill)
            KeyIcon.Mic -> drawToolbarIcon(canvas, micIconStroke, null)
            KeyIcon.Tools -> drawToolbarIcon(canvas, toolsIconStroke, null)
            KeyIcon.Settings -> canvas.drawPath(settingsIconPath, iconPaint)
        }
        canvas.restore()
    }

    private fun drawArrowIcon(canvas: Canvas, direction: ArrowDirection) {
        val path = Path()
        when (direction) {
            ArrowDirection.Left -> {
                path.moveTo(19f, 12f)
                path.lineTo(5f, 12f)
                path.moveTo(11f, 6f)
                path.lineTo(5f, 12f)
                path.lineTo(11f, 18f)
            }
            ArrowDirection.Up -> {
                path.moveTo(12f, 19f)
                path.lineTo(12f, 5f)
                path.moveTo(6f, 11f)
                path.lineTo(12f, 5f)
                path.lineTo(18f, 11f)
            }
            ArrowDirection.Down -> {
                path.moveTo(12f, 5f)
                path.lineTo(12f, 19f)
                path.moveTo(6f, 13f)
                path.lineTo(12f, 19f)
                path.lineTo(18f, 13f)
            }
            ArrowDirection.Right -> {
                path.moveTo(5f, 12f)
                path.lineTo(19f, 12f)
                path.moveTo(13f, 6f)
                path.lineTo(19f, 12f)
                path.lineTo(13f, 18f)
            }
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawShiftIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(12f, 4f)
            lineTo(5f, 11f)
            lineTo(9f, 11f)
            lineTo(9f, 20f)
            lineTo(15f, 20f)
            lineTo(15f, 11f)
            lineTo(19f, 11f)
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawShiftLockIndicator(canvas: Canvas, bounds: RectF, color: Int) {
        val dashWidth = min(bounds.width() * 0.32f, dp(18f))
        val centerX = bounds.centerX()
        val y = bounds.bottom - dp(7f)
        iconPaint.color = color
        iconPaint.strokeWidth = dp(2f)
        iconPaint.style = Paint.Style.STROKE
        canvas.drawLine(centerX - dashWidth / 2f, y, centerX + dashWidth / 2f, y, iconPaint)
    }

    private fun drawDeleteIcon(canvas: Canvas) {
        val outline = Path().apply {
            moveTo(21f, 6f)
            lineTo(8.5f, 6f)
            lineTo(3f, 12f)
            lineTo(8.5f, 18f)
            lineTo(21f, 18f)
            close()
        }
        val cross = Path().apply {
            moveTo(10f, 9f)
            lineTo(16f, 15f)
            moveTo(16f, 9f)
            lineTo(10f, 15f)
        }
        canvas.drawPath(outline, iconPaint)
        canvas.drawPath(cross, iconPaint)
    }

    /** The iOS-matching toolbar icons: an outline, and parts drawn solid (the sheet behind, the dot of ?). */
    private fun drawToolbarIcon(canvas: Canvas, stroke: Path, fill: Path?) {
        iconPaint.strokeWidth = TOOLBAR_ICON_STROKE
        canvas.drawPath(stroke, iconPaint)
        if (fill != null) {
            iconPaint.style = Paint.Style.FILL
            canvas.drawPath(fill, iconPaint)
            iconPaint.style = Paint.Style.STROKE
        }
    }

    private fun drawSearchIcon(canvas: Canvas) {
        canvas.drawCircle(10.5f, 10.5f, 5.5f, iconPaint)
        val handle = Path().apply {
            moveTo(15f, 15f)
            lineTo(20f, 20f)
        }
        canvas.drawPath(handle, iconPaint)
    }

    private fun drawEnterIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(19f, 5f)
            lineTo(19f, 12f)
            lineTo(6f, 12f)
            moveTo(12f, 6f)
            lineTo(6f, 12f)
            lineTo(12f, 18f)
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawSpaceIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(6f, 10f)
            lineTo(6f, 15f)
            lineTo(18f, 15f)
            lineTo(18f, 10f)
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun Path.addRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        dir: Path.Direction,
    ) {
        addRoundRect(RectF(left, top, right, bottom), rx, ry, dir)
    }

    private fun isKeyPressed(keyId: String): Boolean {
        for (index in 0 until activeTouches.size()) {
            if (activeTouches.valueAt(index).currentKey?.id == keyId) {
                return true
            }
        }
        return false
    }

    private fun findHit(x: Float, y: Float): KeyHit? {
        if (hitTargets.isEmpty()) {
            refreshHitTargets()
        }
        return hitTargets.firstOrNull { it.hitBounds.contains(x, y) }
    }

    private fun fitTextSize(label: String, bounds: RectF, role: KeyRole, rowStyle: RowStyle = RowStyle.Standard): Float {
        val base = when {
            role == KeyRole.Character && rowStyle == RowStyle.Numbers -> sp(ImeDimensions.KEY_LABEL_NUMBER_TEXT_SIZE_SP)
            role == KeyRole.Character && rowStyle == RowStyle.Toolbar -> sp(ImeDimensions.KEY_LABEL_TOOLBAR_SYMBOL_TEXT_SIZE_SP)
            role == KeyRole.Character -> sp(ImeDimensions.KEY_LABEL_CHARACTER_TEXT_SIZE_SP)
            role == KeyRole.Function && rowStyle == RowStyle.Toolbar -> sp(ImeDimensions.TOOLBAR_LABEL_TEXT_SIZE_SP)
            role == KeyRole.Function -> sp(ImeDimensions.KEY_LABEL_FUNCTION_TEXT_SIZE_SP)
            else -> sp(ImeDimensions.KEY_LABEL_ACTION_TEXT_SIZE_SP)
        }
        var size = base.coerceAtMost(bounds.height() * ImeDimensions.KEY_LABEL_MAX_HEIGHT_FRACTION)
        val min = sp(ImeDimensions.KEY_LABEL_MIN_TEXT_SIZE_SP)
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)

        textPaint.textSize = size
        while (textPaint.measureText(label) > maxWidth && size > min) {
            size -= sp(ImeDimensions.KEY_LABEL_SHRINK_STEP_SP)
            textPaint.textSize = size
        }
        return size
    }

    private fun handleKey(key: KeySpec, sample: TypingLearner.Sample? = null, shiftHeld: Boolean = false) {
        when (val intent = key.intent) {
            KeyIntent.Shift -> {
                // Tap toggles one-shot Shift; a quick double tap locks caps (Gboard / iOS convention).
                // The old Off → OneShot → Locked cycle trapped users in caps lock when cancelling Shift.
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - lastShiftTapAt <= ViewConfiguration.getDoubleTapTimeout()
                shiftState = when {
                    shiftState == ShiftState.Locked -> ShiftState.Off
                    doubleTap && shiftState == ShiftState.Off && !autoShiftActive -> ShiftState.Locked
                    doubleTap && shiftState == ShiftState.OneShot && !autoShiftActive -> ShiftState.Locked
                    shiftState == ShiftState.OneShot && autoShiftActive && doubleTap -> ShiftState.Locked
                    shiftState == ShiftState.OneShot -> ShiftState.Off
                    else -> ShiftState.OneShot
                }
                autoShiftActive = false
                lastShiftTapAt = if (shiftState == ShiftState.Locked) 0L else now
            }
            KeyIntent.SwitchMode -> {
                layoutMode = when (layoutMode) {
                    LayoutMode.Letters -> lastNumericLayoutMode
                    LayoutMode.Symbols,
                    LayoutMode.MoreSymbols,
                    LayoutMode.PhonePad -> LayoutMode.Letters
                }
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleMoreSymbols -> {
                layoutMode = if (layoutMode == LayoutMode.MoreSymbols) LayoutMode.Symbols else LayoutMode.MoreSymbols
                lastNumericLayoutMode = LayoutMode.Symbols
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleNumericLayout -> {
                layoutMode = if (layoutMode == LayoutMode.PhonePad) LayoutMode.Symbols else LayoutMode.PhonePad
                lastNumericLayoutMode = layoutMode
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleAlt -> alt = !alt
            KeyIntent.ToggleCtrl -> ctrl = !ctrl
            KeyIntent.TogglePinyinMode -> callback?.onPinyinModeToggled()
            KeyIntent.ChooseTranslateLanguage -> callback?.onTranslateLanguageMenuRequested()
            KeyIntent.AgentPolishOnly -> callback?.onAgentPolishOnlyRequested()
            KeyIntent.OpenClipboard -> callback?.onClipboardPanelRequested()
            KeyIntent.OpenPhrases -> callback?.onPhrasesPanelRequested()
            KeyIntent.OpenHelp -> callback?.onHelpPanelRequested()
            // Recording starts and ends with the touch itself (see startVoiceTouch).
            KeyIntent.VoiceInput -> Unit
            KeyIntent.OpenTools -> callback?.onToolsMenuRequested()
            KeyIntent.None -> Unit
            KeyIntent.OpenSettings -> callback?.onKeyboardSettingsRequested()
            KeyIntent.AgentRewrite -> callback?.onAgentRewriteRequested()
            is KeyIntent.Dispatch -> {
                notifyLearner(intent.action, sample)
                if (shiftState == ShiftState.OneShot && key.consumesOneShotShift) {
                    shiftState = ShiftState.Off
                    autoShiftActive = false
                }
                callback?.onKeyboardAction(intent.action, ModifierState(ctrl = ctrl, alt = alt, shift = shiftHeld))
                if (ctrl || alt) {
                    ctrl = false
                    alt = false
                }
            }
        }
        refreshHitTargets()
    }

    private fun notifyLearner(action: KeyboardAction, sample: TypingLearner.Sample?) {
        val learner = typingEngine?.learner ?: return
        if (ctrl || alt) {
            learner.onContextChanged(null)
            return
        }
        when (action) {
            is KeyboardAction.Text -> if (action.value.length == 1) {
                learner.onCommitted(action.value[0], sample)
            } else {
                action.value.forEach { learner.onCommitted(it, null) }
            }
            KeyboardAction.Backspace -> learner.onBackspace()
            KeyboardAction.Enter -> learner.onCommitted('\n', null)
            is KeyboardAction.KeyCode -> learner.onContextChanged(null)
            // 9-key Chinese input teaches the pinyin learner, not the touch model.
            else -> Unit
        }
    }

    private fun resolveDownKey(hit: KeyHit?, x: Float, y: Float, touch: ActiveTouch): KeySpec? {
        val key = hit?.key?.takeIf { it.enabled } ?: return null
        val engine = typingEngine ?: return key
        val geometricLetter = letterIndexOf(key)
        if (geometricLetter < 0 || !letterGeometryValid || layoutMode != LayoutMode.Letters) return key
        val density = resources.displayMetrics.density
        val xDp = x / density
        val yDp = y / density
        touch.tapCosts = tapCostPool[tapCostNext].also {
            engine.decoder.letterCosts(xDp, yDp, letterCentersXDp, letterCentersYDp, letterPitchXDp, letterPitchYDp, it)
        }
        tapCostNext = (tapCostNext + 1) % TAP_COST_POOL
        if (!engine.adaptiveEnabled) return key
        // On the key itself, even at its edge, it is that key, as on the system keyboards; only a
        // touch in the gap between keys is weighed against its neighbours.
        if (hit?.visualBounds?.contains(x, y) == true) {
            touch.sample = TypingLearner.Sample(
                letter = geometricLetter,
                xDp = xDp,
                yDp = yDp,
                centersXDp = letterCentersXDp,
                centersYDp = letterCentersYDp,
                pitchXDp = letterPitchXDp,
                pitchYDp = letterPitchYDp,
            )
            return key
        }
        var previous2 = engine.learner.previous2()
        var previous1 = engine.learner.previous1()
        // Rollover typing: letters still held by other fingers precede this one.
        for (pointerId in pointerQueue) {
            val pendingChar = (activeTouches.get(pointerId)?.currentKey?.intent as? KeyIntent.Dispatch)
                ?.let { (it.action as? KeyboardAction.Text)?.value?.singleOrNull() } ?: continue
            previous2 = previous1
            previous1 = CharLanguageModel.normalize(pendingChar)
        }
        val letter = engine.decoder.decode(
            xDp = xDp,
            yDp = yDp,
            geometricLetter = geometricLetter,
            centersXDp = letterCentersXDp,
            centersYDp = letterCentersYDp,
            pitchXDp = letterPitchXDp,
            pitchYDp = letterPitchYDp,
            previous2 = previous2,
            previous1 = previous1,
            useLanguageModel = engine.personalizationAllowed,
        )
        touch.sample = TypingLearner.Sample(
            letter = letter,
            xDp = xDp,
            yDp = yDp,
            centersXDp = letterCentersXDp,
            centersYDp = letterCentersYDp,
            pitchXDp = letterPitchXDp,
            pitchYDp = letterPitchYDp,
        )
        return letterHits[letter]?.key ?: key
    }

    private fun ActiveTouch.learningSample(key: KeySpec): TypingLearner.Sample? {
        val captured = sample ?: return null
        return captured.takeIf { !slid && it.letter == letterIndexOf(key) }
    }

    private fun letterIndexOf(key: KeySpec): Int {
        val id = key.id
        if (id.length != 6 || !id.startsWith("char_")) return -1
        val char = id[5]
        return if (char in 'a'..'z') char - 'a' else -1
    }

    private fun slideHysteresisPx(): Float {
        val pitchPx = letterPitchXDp * resources.displayMetrics.density
        return max(touchSlopPx, pitchPx * SLIDE_HYSTERESIS_PITCH_FRACTION)
    }

    private fun updateLetterGeometry(hits: List<KeyHit>) {
        val nextHits = arrayOfNulls<KeyHit>(26)
        val centersX = FloatArray(26)
        val centersY = FloatArray(26)
        val density = resources.displayMetrics.density
        var found = 0
        hits.forEach { hit ->
            val letter = letterIndexOf(hit.key)
            if (letter >= 0 && hit.key.enabled) {
                nextHits[letter] = hit
                centersX[letter] = hit.visualBounds.centerX() / density
                centersY[letter] = hit.visualBounds.centerY() / density
                found++
            }
        }
        letterHits = nextHits
        letterCentersXDp = centersX
        letterCentersYDp = centersY
        letterGeometryValid = found == 26
        if (letterGeometryValid) {
            letterPitchXDp = centersX['w' - 'a'] - centersX['q' - 'a']
            letterPitchYDp = centersY['a' - 'a'] - centersY['q' - 'a']
            letterGeometryValid = letterPitchXDp > 0f && letterPitchYDp > 0f
        }
    }

    private fun refreshHitTargets() {
        if (width > 0 && height > 0) {
            hitTargets = layoutKeys(width.toFloat(), height.toFloat())
            updateLetterGeometry(hitTargets)
            reportCandidateStrip()
        }
    }

    /**
     * Called with the band (top and bottom, in this view's pixels) the pinyin candidates cover
     * while composing: the first toolbar row, or the first row when there is none.
     */
    var onCandidateStripChanged: ((top: Int, bottom: Int) -> Unit)? = null

    /** Whether the band the candidates cover holds 润色 | 翻译, so the bar must offer them itself. */
    var candidateStripHidesRewrite = false
        private set

    /** Whether 润色 and 翻译 can be used right now (rewrite set up and nothing in flight). */
    val rewriteAvailable: Boolean get() = shortcutRowState.rewriteEnabled && !shortcutRowState.loading

    private fun reportCandidateStrip() {
        val first = hitTargets.firstOrNull { it.rowStyle == RowStyle.Toolbar } ?: hitTargets.firstOrNull() ?: return
        candidateStripHidesRewrite = hitTargets.any {
            it.key.intent == KeyIntent.AgentRewrite && it.hitBounds.top == first.hitBounds.top
        }
        onCandidateStripChanged?.invoke(first.hitBounds.top.toInt(), first.hitBounds.bottom.toInt())
    }

    private fun layoutRows(): List<RowSpec> = rows()

    private fun layoutKeys(totalWidth: Float, totalHeight: Float): List<KeyHit> {
        val rows = layoutRows()
        val horizontalPadding = dp(ImeLayout.HORIZONTAL_PADDING_DP.toFloat())
        val topPadding = dp(ImeLayout.TOP_PADDING_DP.toFloat())
        val bottomPadding = dp(bottomPaddingDp.toFloat()) + bottomSystemControlGapPx
        val keyGap = dp(ImeLayout.KEY_GAP_DP.toFloat())
        val rowGap = dp(ImeLayout.ROW_GAP_DP.toFloat())
        val usableHeight = totalHeight - topPadding - bottomPadding - rowGap * (rows.size - 1)
        // Toolbar rows keep their size; key rows share the rest, so a page with more rows than the
        // letters (symbols) fits the same height with slightly shorter keys.
        val toolbarUnit = ImeLayout.rowUnitHeightPx(context, heightScale)
        val toolbarHeight = rows.filter { it.style == RowStyle.Toolbar }.fold(0f) { total, row -> total + row.heightWeight * toolbarUnit }
        val keyRowWeightSum = rows.filter { it.style != RowStyle.Toolbar }.fold(0f) { total, row -> total + row.heightWeight }
        val unitHeight = if (keyRowWeightSum > 0f) (usableHeight - toolbarHeight) / keyRowWeightSum else toolbarUnit

        val visualRows = mutableListOf<List<VisualKey>>()
        var top = topPadding
        rows.forEach { row ->
            val rowHeight = row.heightWeight * if (row.style == RowStyle.Toolbar) toolbarUnit else unitHeight
            val keys = row.keys
            val keyWeightSum = keys.fold(0f) { total, key -> total + key.weight }
            val layoutKeyCount = row.layoutKeyCount ?: keys.size
            val layoutWeightSum = row.layoutKeyCount?.toFloat() ?: keyWeightSum
            val usableWidth = totalWidth - horizontalPadding * 2f - keyGap * (layoutKeyCount - 1)
            val rowWidth = usableWidth * (keyWeightSum / layoutWeightSum) + keyGap * (keys.size - 1)
            var left = horizontalPadding + (totalWidth - horizontalPadding * 2f - rowWidth) / 2f

            val visualKeys = mutableListOf<VisualKey>()
            keys.forEach { key ->
                val keyWidth = usableWidth * (key.weight / layoutWeightSum)
                val bounds = RectF(left, top, left + keyWidth, top + rowHeight)
                visualKeys += VisualKey(key, bounds, row.style)
                left += keyWidth + keyGap
            }
            visualRows += visualKeys

            top += rowHeight + rowGap
        }

        val keyboardBottom = (totalHeight - bottomSystemControlGapPx).coerceAtLeast(0f)
        return visualRows.flatMapIndexed { rowIndex, row ->
            val rowTop = if (rowIndex == 0) {
                0f
            } else {
                val previousRow = visualRows[rowIndex - 1]
                midpoint(previousRow.first().visualBounds.bottom, row.first().visualBounds.top)
            }
            val rowBottom = if (rowIndex == visualRows.lastIndex) {
                keyboardBottom
            } else {
                val nextRow = visualRows[rowIndex + 1]
                midpoint(row.first().visualBounds.bottom, nextRow.first().visualBounds.top)
            }

            row.mapIndexed { keyIndex, visualKey ->
                val hitLeft = if (keyIndex == 0) {
                    0f
                } else {
                    midpoint(row[keyIndex - 1].visualBounds.right, visualKey.visualBounds.left)
                }
                val hitRight = if (keyIndex == row.lastIndex) {
                    totalWidth
                } else {
                    midpoint(visualKey.visualBounds.right, row[keyIndex + 1].visualBounds.left)
                }

                KeyHit(
                    key = visualKey.key,
                    hitBounds = RectF(hitLeft, rowTop, hitRight, rowBottom),
                    visualBounds = visualKey.visualBounds,
                    rowStyle = visualKey.rowStyle,
                )
            }
        }
    }

    private fun midpoint(start: Float, end: Float): Float = start + (end - start) / 2f

    /** The rows of page [mode]: the toolbar rows, then its keys. */
    private fun rows(mode: LayoutMode = layoutMode): List<RowSpec> {
        val buttonRowSpecs = mapOf(
            KeyboardSettings.KeyboardRow.Upper.id to terminalRow(),
            KeyboardSettings.KeyboardRow.Second.id to secondRow(),
        )
        val buttonRows = keyboardRowOrder.mapNotNull { rowId -> buttonRowSpecs[rowId] }
        val mainRows = when {
            mode == LayoutMode.Letters && pinyinModeEnabled && chineseKeyboard.isNineKey -> nineKeyRows()
            else -> lettersOrOtherRows(mode)
        }
        return buttonRows + mainRows
    }

    private fun lettersOrOtherRows(mode: LayoutMode): List<RowSpec> {
        return when (mode) {
            LayoutMode.Letters -> if (numberRowVisible) {
                listOf(
                    numberRow(),
                    RowSpec(chars("qwertyuiop"), ImeLayout.STANDARD_ROW_WEIGHT),
                    RowSpec(chars("asdfghjkl"), ImeLayout.STANDARD_ROW_WEIGHT, layoutKeyCount = 10),
                    letterBottomRow(),
                    bottomRow(),
                )
            } else {
                val weight = ImeLayout.STANDARD_ROW_WEIGHT + NO_NUMBER_ROW_EXTRA_WEIGHT
                listOf(
                    RowSpec(chars("qwertyuiop", digitHints = true), weight),
                    RowSpec(chars("asdfghjkl"), weight, layoutKeyCount = 10),
                    letterBottomRow().let { it.copy(heightWeight = it.heightWeight + NO_NUMBER_ROW_EXTRA_WEIGHT) },
                    bottomRow(),
                )
            }
            LayoutMode.Symbols -> listOf(
                numberRow(),
                RowSpec(symbols("@#\$%&-+()/", rowPrefix = "symbol_top"), ImeLayout.STANDARD_ROW_WEIGHT),
                // ! and ? are on the row below, nearer the thumbs; these two slots hold marks the page
                // otherwise lacks: 、 and …… for Chinese, _ and ^ for English.
                RowSpec(symbolMiddleRow(), ImeLayout.STANDARD_ROW_WEIGHT),
                symbolBottomRow(),
                bottomRow(),
            )
            LayoutMode.MoreSymbols -> listOf(
                numberRow(),
                RowSpec(symbols("[]{}#%^*+=", rowPrefix = "more_symbol_top"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(symbols("_\\|~<>€£¥•", rowPrefix = "more_symbol_middle"), ImeLayout.STANDARD_ROW_WEIGHT),
                moreSymbolBottomRow(),
                bottomRow(),
            )
            LayoutMode.PhonePad -> phonePadRows()
        }
    }

    /**
     * 九宫格: a list beside the keys (spellings while composing pinyin, punctuation otherwise),
     * three rows of 3 keys, delete / 重输 / the pinyin–stroke switch, then the usual bottom row.
     * Rows are taller, as there is no number row; swipe up on a digit key types the digit.
     */
    private fun nineKeyRows(): List<RowSpec> {
        val stroke = chineseKeyboard == dev.zain.znkeyboard.KeyboardSettings.ChineseKeyboard.NineKeyStroke
        val spellings = if (stroke) null else nineKeySpellings
        val side = (0 until NINE_KEY_SIDE_SLOTS).map { slot ->
            val spelling = spellings?.getOrNull(slot)
            when {
                spelling != null -> KeySpec("nine_side_$slot", spelling, KeyIntent.Dispatch(KeyboardAction.NineKeySpelling(slot)), NINE_KEY_SIDE_WEIGHT, KeyRole.Function)
                spellings != null -> KeySpec("nine_side_$slot", "", KeyIntent.Dispatch(KeyboardAction.Text("")), NINE_KEY_SIDE_WEIGHT, KeyRole.Function, enabled = false)
                else -> KeySpec("nine_side_$slot", NINE_KEY_SIDE_MARKS[slot], KeyIntent.Dispatch(KeyboardAction.Text(NINE_KEY_SIDE_MARKS[slot])), NINE_KEY_SIDE_WEIGHT, KeyRole.Function)
            }
        }
        val backspace = KeySpec("nine_backspace", "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), NINE_KEY_SIDE_WEIGHT, KeyRole.Function, icon = KeyIcon.Delete)
        val clear = KeySpec("nine_clear", "重输", KeyIntent.Dispatch(KeyboardAction.ClearComposition), NINE_KEY_SIDE_WEIGHT, KeyRole.Function)
        val toggle = KeySpec("nine_toggle", if (stroke) "拼音" else "笔画", KeyIntent.Dispatch(KeyboardAction.ToggleNineKeyInput), NINE_KEY_SIDE_WEIGHT, KeyRole.Function)
        val grid: List<List<KeySpec>> = if (stroke) {
            listOf(
                listOf(strokeKey('h', "一"), strokeKey('s', "丨"), strokeKey('p', "丿")),
                listOf(strokeKey('n', "丶"), strokeKey('z', "乛"), strokeKey('*', "＊", "通配")),
                listOf(markKey("："), markKey("；"), markKey("…")),
            )
        } else {
            listOf(
                listOf(nineDigitKey('1', "分词"), nineDigitKey('2', "ABC"), nineDigitKey('3', "DEF")),
                listOf(nineDigitKey('4', "GHI"), nineDigitKey('5', "JKL"), nineDigitKey('6', "MNO")),
                listOf(nineDigitKey('7', "PQRS"), nineDigitKey('8', "TUV"), nineDigitKey('9', "WXYZ")),
            )
        }
        val rowWeight = ImeLayout.STANDARD_ROW_WEIGHT * NINE_KEY_ROW_SCALE
        return listOf(
            RowSpec(listOf(side[0]) + grid[0] + backspace, rowWeight),
            RowSpec(listOf(side[1]) + grid[1] + clear, rowWeight),
            RowSpec(listOf(side[2]) + grid[2] + toggle, rowWeight),
            bottomRow(),
        )
    }

    /** Letters big, the digit small in the corner (swipe up types it). */
    private fun nineDigitKey(digit: Char, letters: String): KeySpec = KeySpec(
        id = "nine_$digit",
        label = letters,
        intent = KeyIntent.Dispatch(KeyboardAction.NineKeyDigit(digit)),
        weight = NINE_KEY_MAIN_WEIGHT,
        role = KeyRole.Character,
        longPressHint = digit.toString(),
    )

    private fun strokeKey(stroke: Char, mark: String, hint: String? = null): KeySpec = KeySpec(
        id = "stroke_$stroke",
        label = mark,
        intent = KeyIntent.Dispatch(KeyboardAction.Stroke(stroke)),
        weight = NINE_KEY_MAIN_WEIGHT,
        role = KeyRole.Character,
        longPressHint = hint,
    )

    private fun markKey(mark: String): KeySpec = KeySpec(
        id = "stroke_mark_$mark",
        label = mark,
        intent = KeyIntent.Dispatch(KeyboardAction.Text(mark)),
        weight = NINE_KEY_MAIN_WEIGHT,
        role = KeyRole.Character,
    )

    private fun numberRow(): RowSpec {
        return RowSpec(chars("1234567890"), ImeLayout.STANDARD_ROW_WEIGHT, style = RowStyle.Numbers)
    }

    private fun terminalRow(): RowSpec? {
        val keys = upperRowKeyIds.mapIndexedNotNull { index, keyId ->
            upperRowKeySpec(
                keyId = keyId,
                index = index,
                weight = upperRowKeyWeight(index, upperRowKeyIds.lastIndex),
                rowPrefix = "upper",
                enabled = true,
            )
        }
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT, style = RowStyle.Toolbar) }
    }

    private fun secondRow(): RowSpec? {
        if (!hasVisibleSecondRow()) return null
        val keys = secondRowButtonIds.mapIndexedNotNull { index, buttonId ->
            upperRowKeySpec(
                keyId = buttonId,
                index = index,
                weight = ImeDimensions.DEFAULT_KEY_WEIGHT,
                rowPrefix = "second",
                enabled = true,
            )
        }.toMutableList()
        if (!shortcutRowState.aiVisible) keys.removeAll { it.intent == KeyIntent.AgentRewrite }
        val languageCode = translateLanguageCode
        val rewriteIndex = keys.indexOfFirst { it.intent == KeyIntent.AgentRewrite }
        if (languageCode != null && rewriteIndex >= 0) {
            // 翻译 (EN): translating is the AI key's one job now; the language badge keeps its own
            // tap target but sits inside the translate key.
            keys.add(
                rewriteIndex + 1,
                KeySpec(
                    id = "second_translate_language",
                    label = languageCode,
                    intent = KeyIntent.ChooseTranslateLanguage,
                    weight = ImeDimensions.TOOLBAR_LANGUAGE_CHIP_DP,
                    role = KeyRole.Function,
                    chip = true,
                ),
            )
        }
        return RowSpec(fixedToolbarKeys(keys), heightWeight = ImeLayout.COMPACT_ROW_WEIGHT, style = RowStyle.Toolbar)
    }

    /**
     * Tools and 翻译 start the row and the other keys end it, each at a fixed width, with the
     * space left over between them. Weights are in dp so that the row adds up to the screen.
     */
    private fun fixedToolbarKeys(keys: MutableList<KeySpec>): List<KeySpec> {
        val latin = !pinyinModeEnabled
        val fixed = keys.mapTo(mutableListOf()) { key ->
            key.copy(
                weight = when {
                    key.intent == KeyIntent.AgentPolishOnly -> ImeDimensions.TOOLBAR_POLISH_KEY_DP
                    key.intent == KeyIntent.AgentRewrite && latin -> ImeDimensions.TOOLBAR_TRANSLATE_LABEL_EN_DP
                    key.intent == KeyIntent.AgentRewrite && shortcutRowState.selection -> ImeDimensions.TOOLBAR_TRANSLATE_SELECTION_LABEL_DP
                    key.intent == KeyIntent.AgentRewrite -> ImeDimensions.TOOLBAR_TRANSLATE_LABEL_DP
                    key.intent == KeyIntent.ChooseTranslateLanguage && latin -> ImeDimensions.TOOLBAR_LANGUAGE_CHIP_EN_DP
                    key.intent == KeyIntent.ChooseTranslateLanguage -> ImeDimensions.TOOLBAR_LANGUAGE_CHIP_DP
                    else -> ImeDimensions.TOOLBAR_ICON_KEY_DP
                },
            )
        }
        if (shortcutRowState.aiVisible) {
            fixed.add(0, KeySpec("second_tools", "Tools", KeyIntent.OpenTools, ImeDimensions.TOOLBAR_ICON_KEY_DP, KeyRole.Function, icon = KeyIcon.Tools))
        }
        // Clipboard, phrases and help end the row, as on iOS; settings moved into the tools menu.
        fixed.removeAll { it.intent == KeyIntent.OpenClipboard }
        fixed.add(KeySpec("second_clipboard", "Clipboard", KeyIntent.OpenClipboard, ImeDimensions.TOOLBAR_ICON_KEY_DP, KeyRole.Function, icon = KeyIcon.Clipboard))
        fixed.add(KeySpec("second_phrases", "Phrases", KeyIntent.OpenPhrases, ImeDimensions.TOOLBAR_ICON_KEY_DP, KeyRole.Function, icon = KeyIcon.Phrases))
        // Voice input, shown only while it can work (a Gemini key is set): hold to talk.
        if (voiceInputEnabled) {
            fixed.add(KeySpec("second_voice", "Voice", KeyIntent.VoiceInput, ImeDimensions.TOOLBAR_ICON_KEY_DP, KeyRole.Function, icon = KeyIcon.Mic))
        }
        fixed.add(KeySpec("second_help", "Help", KeyIntent.OpenHelp, ImeDimensions.TOOLBAR_ICON_KEY_DP, KeyRole.Function, icon = KeyIcon.Help))
        val metrics = resources.displayMetrics
        val spare = metrics.widthPixels / metrics.density - fixed.sumOf { it.weight.toDouble() }.toFloat()
        if (spare > 0f) {
            val leftEnd = fixed.indexOfLast {
                it.intent == KeyIntent.OpenTools || it.intent == KeyIntent.AgentPolishOnly ||
                    it.intent == KeyIntent.AgentRewrite || it.intent == KeyIntent.ChooseTranslateLanguage
            }
            fixed.add(leftEnd + 1, KeySpec("second_space", "", KeyIntent.None, spare, KeyRole.Function, enabled = false))
        }
        return fixed
    }

    private fun hasVisibleSecondRow(): Boolean {
        return shortcutRowState.secondRowVisible && secondRowButtonIds.isNotEmpty()
    }

    private fun upperRowKeyWeight(index: Int, lastIndex: Int): Float {
        return if (index == 0 || index == lastIndex) {
            ImeDimensions.EDGE_UPPER_ROW_KEY_WEIGHT
        } else {
            ImeDimensions.DEFAULT_KEY_WEIGHT
        }
    }

    private fun upperRowKeySpec(
        keyId: String,
        index: Int,
        weight: Float,
        rowPrefix: String,
        enabled: Boolean,
    ): KeySpec? {
        val id = "${rowPrefix}_${index}_$keyId"
        return when (keyId) {
            "rewrite" -> KeySpec(
                id = id,
                label = when {
                    !shortcutRowState.loading && shortcutRowState.selection && pinyinModeEnabled && translateLanguageCode != null ->
                        AgentDefaults.TRANSLATE_SELECTION_LABEL
                    !shortcutRowState.loading -> rewriteLabel
                    translateLanguageCode != null && pinyinModeEnabled -> AgentDefaults.TRANSLATE_REWRITE_LOADING_LABEL
                    translateLanguageCode != null -> AgentDefaults.TRANSLATE_REWRITE_LOADING_LABEL_EN
                    else -> "Rewriting"
                },
                intent = KeyIntent.AgentRewrite,
                weight = if (translateLanguageCode != null) maxOf(weight, ImeDimensions.REWRITE_TRANSLATE_KEY_WEIGHT) else weight,
                mergesWithNext = translateLanguageCode != null,
                role = KeyRole.Function,
                active = shortcutRowState.loading,
                enabled = enabled && shortcutRowState.rewriteEnabled && !shortcutRowState.loading,
                emphasizedWhenDisabled = shortcutRowState.loading,
            )
            "clipboard" -> KeySpec(id, "Clipboard", KeyIntent.OpenClipboard, weight, KeyRole.Function, icon = KeyIcon.Clipboard, enabled = enabled)
            "ctrl" -> KeySpec(id, "Ctrl", KeyIntent.ToggleCtrl, weight, KeyRole.Function, ctrl, enabled = enabled)
            "alt" -> KeySpec(id, "Alt", KeyIntent.ToggleAlt, weight, KeyRole.Function, alt, enabled = enabled)
            "tab" -> KeySpec(id, "Tab", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_TAB)), weight, KeyRole.Function, enabled = enabled)
            "esc" -> KeySpec(id, "Esc", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_ESCAPE)), weight, KeyRole.Action, enabled = enabled)
            "left" -> KeySpec(id, "Left", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_LEFT)), weight, KeyRole.Function, icon = KeyIcon.ArrowLeft, enabled = enabled)
            "up" -> KeySpec(id, "Up", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP)), weight, KeyRole.Function, icon = KeyIcon.ArrowUp, enabled = enabled)
            "down" -> KeySpec(id, "Down", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN)), weight, KeyRole.Function, icon = KeyIcon.ArrowDown, enabled = enabled)
            "right" -> KeySpec(id, "Right", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)), weight, KeyRole.Function, icon = KeyIcon.ArrowRight, enabled = enabled)
            "home" -> KeySpec(id, "Home", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_HOME)), weight, KeyRole.Function, enabled = enabled)
            "end" -> KeySpec(id, "End", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_END)), weight, KeyRole.Function, enabled = enabled)
            "page_up" -> KeySpec(id, "PgUp", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_UP)), weight, KeyRole.Function, enabled = enabled)
            "page_down" -> KeySpec(id, "PgDn", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_DOWN)), weight, KeyRole.Function, enabled = enabled)
            "backspace" -> KeySpec(id, "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), weight, KeyRole.Function, icon = KeyIcon.Delete, enabled = enabled)
            else -> KeyboardSettings.symbolForShortcutRowKey(keyId)?.let { textUpperRowKey(id, it, weight, enabled) }
        }
    }

    private fun textUpperRowKey(id: String, value: String, weight: Float, enabled: Boolean): KeySpec {
        return KeySpec(
            id = id,
            label = value,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(value)),
            weight = weight,
            role = KeyRole.Character,
            enabled = enabled,
        )
    }

    private fun letterBottomRow(): RowSpec {
        val keys = mutableListOf<KeySpec>()
        keys += KeySpec(
            "shift",
            "Shift",
            KeyIntent.Shift,
            ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
            KeyRole.Function,
            shiftState != ShiftState.Off,
            KeyIcon.Shift,
        )
        keys += chars("zxcvbnm")
        keys += KeySpec(
            "backspace",
            "Del",
            KeyIntent.Dispatch(KeyboardAction.Backspace),
            ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
            KeyRole.Function,
            icon = KeyIcon.Delete,
        )
        // 7 letters + two 1.5-unit side keys = the same 10-unit grid as the top row, so z..m keep the
        // exact width (and touch statistics) of every other letter key.
        return RowSpec(keys, ImeLayout.STANDARD_ROW_WEIGHT, layoutKeyCount = 10)
    }

    private fun symbolBottomRow(): RowSpec {
        return RowSpec(
            listOf(
                KeySpec(
                    "more_symbols",
                    "#+=",
                    KeyIntent.ToggleMoreSymbols,
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                ),
                KeySpec("period", ".", KeyIntent.Dispatch(KeyboardAction.Text(".")), role = KeyRole.Character),
                KeySpec("comma", ",", KeyIntent.Dispatch(KeyboardAction.Text(",")), role = KeyRole.Character),
                KeySpec("question", "?", KeyIntent.Dispatch(KeyboardAction.Text("?")), role = KeyRole.Character),
                KeySpec("bang", "!", KeyIntent.Dispatch(KeyboardAction.Text("!")), role = KeyRole.Character),
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character),
                KeySpec("backtick", "`", KeyIntent.Dispatch(KeyboardAction.Text("`")), role = KeyRole.Character),
                KeySpec(
                    "backspace",
                    "Del",
                    KeyIntent.Dispatch(KeyboardAction.Backspace),
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                    icon = KeyIcon.Delete,
                ),
            ),
            heightWeight = ImeLayout.STANDARD_ROW_WEIGHT,
        )
    }

    private fun moreSymbolBottomRow(): RowSpec {
        return RowSpec(
            listOf(
                KeySpec(
                    "more_symbols",
                    "123",
                    KeyIntent.ToggleMoreSymbols,
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                ),
                KeySpec("period", ".", KeyIntent.Dispatch(KeyboardAction.Text(".")), role = KeyRole.Character),
                KeySpec("comma", ",", KeyIntent.Dispatch(KeyboardAction.Text(",")), role = KeyRole.Character),
                KeySpec("question", "?", KeyIntent.Dispatch(KeyboardAction.Text("?")), role = KeyRole.Character),
                KeySpec("bang", "!", KeyIntent.Dispatch(KeyboardAction.Text("!")), role = KeyRole.Character),
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character),
                KeySpec("backtick", "`", KeyIntent.Dispatch(KeyboardAction.Text("`")), role = KeyRole.Character),
                KeySpec(
                    "backspace",
                    "Del",
                    KeyIntent.Dispatch(KeyboardAction.Backspace),
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                    icon = KeyIcon.Delete,
                ),
            ),
            heightWeight = ImeLayout.STANDARD_ROW_WEIGHT,
        )
    }

    private fun phonePadRows(): List<RowSpec> {
        if (fieldKind == FieldKind.Phone) return dialPadRows()
        return listOf(
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "plus", label = "+"),
                    phonePadTextKey(idSuffix = "1", label = "1"),
                    phonePadTextKey(idSuffix = "2", label = "2"),
                    phonePadTextKey(idSuffix = "3", label = "3"),
                    phonePadOperatorKey(idSuffix = "percent", label = "%"),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "minus", label = "-"),
                    phonePadTextKey(idSuffix = "4", label = "4"),
                    phonePadTextKey(idSuffix = "5", label = "5"),
                    phonePadTextKey(idSuffix = "6", label = "6"),
                    KeySpec(
                        "phone_space",
                        "space",
                        KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                        role = KeyRole.Function,
                        icon = KeyIcon.Space,
                    ),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "star", label = "*", subLabel = "/"),
                    phonePadTextKey(idSuffix = "7", label = "7"),
                    phonePadTextKey(idSuffix = "8", label = "8"),
                    phonePadTextKey(idSuffix = "9", label = "9"),
                    KeySpec(
                        "phone_backspace",
                        "Del",
                        KeyIntent.Dispatch(KeyboardAction.Backspace),
                        role = KeyRole.Function,
                        icon = KeyIcon.Delete,
                    ),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            numericBottomRow(toggleLabel = "!?#", phonePad = true),
        )
    }

    /** A phone number field: the dial pad (* 0 #), with + - ( ) for written numbers. */
    private fun dialPadRows(): List<RowSpec> {
        fun row(vararg keys: KeySpec) = RowSpec(keys.toList(), ImeLayout.STANDARD_ROW_WEIGHT)
        return listOf(
            row(phonePadOperatorKey("plus", "+"), phonePadTextKey("1", "1"), phonePadTextKey("2", "2"), phonePadTextKey("3", "3"), phonePadOperatorKey("minus", "-")),
            row(
                phonePadOperatorKey("open", "("),
                phonePadTextKey("4", "4"),
                phonePadTextKey("5", "5"),
                phonePadTextKey("6", "6"),
                KeySpec("phone_space", "space", KeyIntent.Dispatch(KeyboardAction.Text(" ")), role = KeyRole.Function, icon = KeyIcon.Space),
            ),
            row(
                phonePadOperatorKey("close", ")"),
                phonePadTextKey("7", "7"),
                phonePadTextKey("8", "8"),
                phonePadTextKey("9", "9"),
                KeySpec("phone_backspace", "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), role = KeyRole.Function, icon = KeyIcon.Delete),
            ),
            RowSpec(
                listOf(
                    KeySpec("switch", "ABC", KeyIntent.SwitchMode, role = KeyRole.Function),
                    phonePadTextKey("star", "*"),
                    phonePadTextKey("0", "0"),
                    phonePadTextKey("hash", "#"),
                    KeySpec(
                        "enter",
                        enterLabel,
                        KeyIntent.Dispatch(KeyboardAction.Enter),
                        role = roleForEnterLabel(enterLabel),
                        icon = iconForEnterLabel(enterLabel),
                    ),
                ),
                heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
            ),
        )
    }

    private fun phonePadTextKey(
        idSuffix: String,
        label: String,
        subLabel: String? = null,
    ): KeySpec {
        return KeySpec(
            id = "phone_$idSuffix",
            label = label,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
            role = KeyRole.Character,
            subLabel = subLabel,
        )
    }

    private fun phonePadOperatorKey(
        idSuffix: String,
        label: String,
        subLabel: String? = null,
    ): KeySpec {
        return KeySpec(
            id = "phone_$idSuffix",
            label = label,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
            role = KeyRole.Function,
            subLabel = subLabel,
        )
    }

    private fun numericBottomRow(toggleLabel: String, phonePad: Boolean): RowSpec {
        val keys = mutableListOf<KeySpec>()
        keys += KeySpec(
            "switch",
            "ABC",
            KeyIntent.SwitchMode,
            ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
            KeyRole.Function,
        )
        keys += KeySpec(
            "comma",
            ",",
            KeyIntent.Dispatch(KeyboardAction.Text(",")),
            0.82f,
            KeyRole.Character,
        )
        keys += KeySpec(
            "numeric_layout_toggle",
            if (toggleLabel == "1234") "12" else toggleLabel,
            KeyIntent.ToggleNumericLayout,
            1.16f,
            KeyRole.Function,
            subLabel = if (toggleLabel == "1234") "34" else null,
            subLabelStyle = SubLabelStyle.Equal,
        )
        if (phonePad) {
            keys += KeySpec(
                "phone_0",
                "0",
                KeyIntent.Dispatch(KeyboardAction.Text("0")),
                1.9f,
                KeyRole.Character,
            )
            keys += KeySpec(
                "phone_equals",
                "=",
                KeyIntent.Dispatch(KeyboardAction.Text("=")),
                ImeDimensions.DEFAULT_KEY_WEIGHT,
                KeyRole.Character,
            )
        } else {
            keys += KeySpec(
                "space",
                "English",
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                4.7f,
                KeyRole.Function,
            )
        }
        keys += KeySpec(
            "period",
            ".",
            KeyIntent.Dispatch(KeyboardAction.Text(".")),
            0.82f,
            KeyRole.Character,
        )
        keys += KeySpec(
            "enter",
            enterLabel,
            KeyIntent.Dispatch(KeyboardAction.Enter),
            ImeDimensions.ENTER_KEY_WEIGHT,
            roleForEnterLabel(enterLabel),
            icon = iconForEnterLabel(enterLabel),
        )
        return RowSpec(
            keys,
            heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
        )
    }

    private fun bottomRow(): RowSpec {
        val keys = mutableListOf(
            KeySpec(
                "switch",
                // Digits already sit in the number row above, so this key leads with symbols.
                if (layoutMode == LayoutMode.Letters) "!?#" else "ABC",
                KeyIntent.SwitchMode,
                ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
                KeyRole.Function,
            ),
            pinyinModeKeySpec(),
        )
        if (layoutMode != LayoutMode.Letters) {
            keys += KeySpec(
                "space",
                spaceLabel(),
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                ImeDimensions.SPACE_KEY_WEIGHT,
                KeyRole.Function,
            )
            keys += KeySpec(
                "numeric_layout_toggle",
                "12",
                KeyIntent.ToggleNumericLayout,
                1.18f,
                KeyRole.Function,
                subLabel = "34",
                subLabelStyle = SubLabelStyle.Equal,
            )
        } else {
            // Comma and period flank space. In 中 mode they show full-width marks; the service turns
            // the ASCII mark it receives into the Chinese one, so both modes dispatch the same text.
            // Email and web addresses never want a comma there: @ and / instead.
            val (commaLabel, commaText) = when (fieldKind) {
                FieldKind.Email -> "@" to "@"
                FieldKind.Url -> "/" to "/"
                else -> (if (pinyinModeEnabled) "，" else ",") to ","
            }
            keys += KeySpec(
                "bottom_comma",
                commaLabel,
                KeyIntent.Dispatch(KeyboardAction.Text(commaText)),
                role = KeyRole.Character,
            )
            keys += KeySpec(
                "space",
                spaceLabel(),
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                ImeDimensions.SPACE_KEY_WEIGHT,
                KeyRole.Function,
            )
            keys += KeySpec(
                "bottom_period",
                if (pinyinModeEnabled) "。" else ".",
                KeyIntent.Dispatch(KeyboardAction.Text(".")),
                role = KeyRole.Character,
            )
        }
        keys += KeySpec(
            "enter",
            enterLabel,
            KeyIntent.Dispatch(KeyboardAction.Enter),
            ImeDimensions.ENTER_KEY_WEIGHT,
            roleForEnterLabel(enterLabel),
            icon = iconForEnterLabel(enterLabel),
        )
        return RowSpec(
            keys,
            heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
        )
    }

    /**
     * 中/英 switches between Chinese and English, the one on drawn bright ([drawLanguageToggle]).
     * Not a globe: the system already shows one beside the keyboard, for switching keyboards.
     */
    private fun pinyinModeKeySpec(): KeySpec {
        return KeySpec(
            id = PINYIN_MODE_KEY_ID,
            label = "中/英",
            intent = KeyIntent.TogglePinyinMode,
            weight = ImeDimensions.PINYIN_MODE_KEY_WEIGHT,
            role = KeyRole.Function,
        )
    }

    /** [digitHints]: 1–0 in the corners of the top row, typed by a long press (no number row). */
    private fun chars(source: String, digitHints: Boolean = false): List<KeySpec> {
        return source.mapIndexed { index, char ->
            val label = if (shiftState != ShiftState.Off) char.uppercaseChar().toString() else char.toString()
            KeySpec(
                id = "char_$char",
                label = label,
                intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
                role = KeyRole.Character,
                consumesOneShotShift = true,
                longPressHint = if (digitHints) TOP_ROW_DIGITS[index].toString() else null,
            )
        }
    }

    private fun symbolMiddleRow(): List<KeySpec> {
        val keys = symbols("*\":;!?\\|=~", rowPrefix = "symbol_middle").toMutableList()
        val extras = if (pinyinModeEnabled) listOf("、" to "、", "…" to "……") else listOf("_" to "_", "^" to "^")
        extras.forEachIndexed { i, (label, text) ->
            keys[SYMBOL_MIDDLE_FREE_SLOT + i] = KeySpec(
                "symbol_middle_extra_$i",
                label,
                KeyIntent.Dispatch(KeyboardAction.Text(text)),
                role = KeyRole.Character,
            )
        }
        return keys
    }

    private fun symbols(source: String, rowPrefix: String = "symbol"): List<KeySpec> {
        return source.mapIndexed { index, char ->
            KeySpec(
                id = "${rowPrefix}_$index",
                label = char.toString(),
                intent = KeyIntent.Dispatch(KeyboardAction.Text(char.toString())),
                role = KeyRole.Character,
            )
        }
    }

    private fun shrinkTextSizeToFit(label: String, initialSize: Float, maxWidth: Float): Float {
        var size = initialSize
        val min = sp(ImeDimensions.KEY_LABEL_MIN_TEXT_SIZE_SP)

        textPaint.textSize = size
        while (textPaint.measureText(label) > maxWidth && size > min) {
            size -= sp(ImeDimensions.KEY_LABEL_SHRINK_STEP_SP)
            textPaint.textSize = size
        }
        return size
    }

    private fun iconForEnterLabel(label: String): KeyIcon? {
        return when (label) {
            "Enter" -> KeyIcon.Enter
            "Search" -> KeyIcon.Search
            else -> null
        }
    }

    private fun roleForEnterLabel(label: String): KeyRole {
        return if (label == "Search") KeyRole.Function else KeyRole.Action
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private enum class LayoutMode {
        Letters,
        Symbols,
        MoreSymbols,
        PhonePad,
    }

    private enum class ShiftState {
        Off,
        OneShot,
        Locked,
    }

    private enum class KeyRole {
        Character,
        Function,
        Action,
    }

    private enum class SubLabelStyle {
        Small,
        Equal,
    }

    private enum class KeyIcon {
        ArrowLeft,
        ArrowUp,
        ArrowDown,
        ArrowRight,
        Shift,
        Delete,
        Search,
        Enter,
        Space,
        Clipboard,
        Phrases,
        Help,
        Mic,
        Tools,
        Settings,
    }

    private enum class ArrowDirection {
        Left,
        Up,
        Down,
        Right,
    }

    private enum class RowStyle {
        Standard,
        Numbers,
        Toolbar,
    }

    private data class RowSpec(
        val keys: List<KeySpec>,
        val heightWeight: Float,
        val layoutKeyCount: Int? = null,
        val style: RowStyle = RowStyle.Standard,
    )

    private data class KeySpec(
        val id: String,
        val label: String,
        val intent: KeyIntent,
        val weight: Float = ImeDimensions.DEFAULT_KEY_WEIGHT,
        val role: KeyRole = KeyRole.Character,
        val active: Boolean = false,
        val icon: KeyIcon? = null,
        val consumesOneShotShift: Boolean = false,
        val enabled: Boolean = true,
        val emphasizedWhenDisabled: Boolean = false,
        val longPressHint: String? = null,
        val subLabel: String? = null,
        val subLabelStyle: SubLabelStyle = SubLabelStyle.Small,
        /** Drawn as a capsule chip ("EN ▾") inset in its slot, always with a background. */
        val chip: Boolean = false,
        /** The pill also covers the next key's slot, so that key reads as a badge inside it. */
        val mergesWithNext: Boolean = false,
    )

    private data class KeyHit(
        val key: KeySpec,
        val hitBounds: RectF,
        val visualBounds: RectF,
        val rowStyle: RowStyle,
    )

    private data class VisualKey(
        val key: KeySpec,
        val visualBounds: RectF,
        val rowStyle: RowStyle,
    )

    private class ActiveTouch(
        var currentKey: KeySpec?,
        val downX: Float = 0f,
        val downY: Float = 0f,
        var slid: Boolean = false,
        var sample: TypingLearner.Sample? = null,
        /** How well the touch-down fitted each letter key, from [tapCostPool]. */
        var tapCosts: FloatArray? = null,
        var longPressConsumed: Boolean = false,
        var repeatConsumed: Boolean = false,
        var cursorDragConsumed: Boolean = false,
        var heldAsModifier: Boolean = false,
        var backspaceGesture: BackspaceGestureTouch? = null,
        var spaceCursorDrag: SpaceCursorDragTouch? = null,
        var voice: VoiceTouch? = null,
        /** 9-key: flicked up on a digit key, which then types the digit. */
        var swipedUp: Boolean = false,
    )

    private class VoiceTouch(val startY: Float, val startedAt: Long)

    private data class BackspaceGestureTouch(
        val key: KeySpec,
        val startX: Float,
        val startY: Float,
        val wordStepPx: Float,
        var active: Boolean = false,
        /** Held still long enough: the cursor follows from here without a distance to cover. */
        var held: Boolean = false,
        var consumedWithoutGesture: Boolean = false,
        var lastWordCount: Int = 0,
    )

    private data class SpaceCursorDragTouch(
        val key: KeySpec,
        val startX: Float,
        val startY: Float,
        val stepPx: Float,
        var active: Boolean = false,
        /** Held still long enough: the cursor follows from here without a distance to cover. */
        var held: Boolean = false,
        var consumedWithoutGesture: Boolean = false,
        var lastCharacterDelta: Int = 0,
    )

    private sealed class KeyIntent {
        data object Shift : KeyIntent()
        data object SwitchMode : KeyIntent()
        data object ToggleMoreSymbols : KeyIntent()
        data object ToggleNumericLayout : KeyIntent()
        data object ToggleAlt : KeyIntent()
        data object ToggleCtrl : KeyIntent()
        data object TogglePinyinMode : KeyIntent()
        data object ChooseTranslateLanguage : KeyIntent()
        data object AgentPolishOnly : KeyIntent()
        data object AgentRewrite : KeyIntent()
        data object OpenClipboard : KeyIntent()
        data object OpenPhrases : KeyIntent()
        data object OpenHelp : KeyIntent()
        data object VoiceInput : KeyIntent()
        data object OpenTools : KeyIntent()
        data object None : KeyIntent()
        data object OpenSettings : KeyIntent()
        data class Dispatch(val action: KeyboardAction) : KeyIntent()
    }

    private object PALETTE {
        val background get() = ImeColors.BACKGROUND
        val key get() = ImeColors.KEY
        val keyQuiet get() = ImeColors.KEY_DARK
        val function get() = ImeColors.FUNCTION
        val action get() = ImeColors.ACTION
        val onAction get() = ImeColors.ON_ACTION
        val selected get() = ImeColors.SELECTED
        val onSelected get() = ImeColors.ON_SELECTED
        val preview get() = ImeColors.PREVIEW
        val aiLoadingSurface get() = ImeColors.AI_LOADING_SURFACE
        val text get() = ImeColors.TEXT
        val mutedText get() = ImeColors.MUTED_TEXT
    }

    /** [aiVisible]: false where AI rewriting never applies (passwords), so its keys are left out. */
    data class ShortcutRowState(
        val secondRowVisible: Boolean,
        val loading: Boolean = false,
        val rewriteEnabled: Boolean = false,
        val aiVisible: Boolean = true,
        /** Something to translate is in the field; without it 翻译 greys out (a tap still says why). */
        val hasText: Boolean = true,
        /** Text is selected: only that is translated, and the key says 翻译选中. */
        val selection: Boolean = false,
    )

    private companion object {
        const val SETTINGS_ICON_TEETH = 8
        /** Stroke of the clipboard, phrases and help icons, on the 24-unit icon grid. */
        const val TOOLBAR_ICON_STROKE = 1.9f
        // One tooth of the gear, as (angle from its middle, distance from the center).
        val SETTINGS_ICON_TOOTH = listOf(-0.3 to 7.0, -0.17 to 9.5, 0.17 to 9.5, 0.3 to 7.0)

        const val TAP_COST_POOL = 12
        const val LETTER_LIFT_FRACTION = 0.04f
        const val PINYIN_MODE_KEY_ID = "pinyin_mode"
        val FULL_WIDTH_MARKS = setOf("，", "。", "、", "；", "：", "？", "！")
        /** Where ! and ? sit in the symbols page's middle row; the bottom row has them too. */
        const val SYMBOL_MIDDLE_FREE_SLOT = 4
        const val LANGUAGE_KEY_ACTIVE_SCALE = 1.25f
        const val LANGUAGE_KEY_INACTIVE_SCALE = 0.72f
        const val LANGUAGE_KEY_INACTIVE_ALPHA = 0.45f
        const val MIC_HINT_SCALE = 1.5f
        const val TOP_ROW_DIGITS = "1234567890"
        /** Without the number row, each letter row takes this much more height (the keyboard still ends up shorter). */
        const val NO_NUMBER_ROW_EXTRA_WEIGHT = 0.2f
        const val NINE_KEY_SIDE_SLOTS = 3
        val NINE_KEY_SIDE_MARKS = listOf("？", "！", "、")
        const val NINE_KEY_SIDE_WEIGHT = 1f
        const val NINE_KEY_MAIN_WEIGHT = 1.35f
        /** Four rows fill the height five rows take on the 26-key keyboard. */
        const val NINE_KEY_ROW_SCALE = 1.33f
        const val NINE_KEY_SWIPE_UP_DP = 22f
        const val DISABLED_KEY_ALPHA = 118
        const val DISABLED_CONTENT_ALPHA = 130
        const val LONG_PRESS_HINT_ALPHA = 170
        const val GESTURE_DELETE_ACTIVATION_DP = 10f
        const val GESTURE_DELETE_WORD_STEP_DP = 42f
        const val SPACE_CURSOR_DRAG_ACTIVATION_DP = 8f
        const val SPACE_CURSOR_DRAG_STEP_DP = 14f
        const val SPACE_CURSOR_DRAG_VISIBLE_STEPS = 28f
        const val SPACE_CURSOR_DRAG_MAX_CHARS = 120
        const val AI_LOADING_CYCLE_MS = 2200L
        const val VOICE_CANCEL_SLIDE_DP = 56f
        const val VOICE_CANCEL_TARGET_DP = 26f
        /** Up sooner than this, the microphone was tapped: record hands-free. */
        const val VOICE_TAP_MS = 350L
        const val VOICE_SPEECH_LEVEL = 0.08f
        const val VOICE_QUIET_LEVEL = 0.03f
        const val VOICE_SILENCE_DONE_MS = 3_000L
        const val VOICE_BUTTON_CANCEL = 0
        const val VOICE_BUTTON_DONE = 1
        const val VOICE_BUTTON_RETRY = 2
        const val VOICE_OVERLAY_INSET_DP = 6f
        const val VOICE_OVERLAY_MIN_HEIGHT_DP = 120f
        const val VOICE_OVERLAY_RADIUS_DP = 20f
        /** Share of the gap to the newest level closed each frame. */
        const val VOICE_LEVEL_EASING = 0.25f
        const val VOICE_RINGS = 3
        const val VOICE_RING_PERIOD_S = 1.8f
        const val VOICE_WAVES = 3
        const val TWO_PI = 6.2831855f
        const val PHONE_PAD_PRIMARY_MAX_HEIGHT_FRACTION = 0.44f
        const val PHONE_PAD_SUB_LABEL_TEXT_SIZE_SP = 8f
        const val PHONE_PAD_SUB_LABEL_MAX_HEIGHT_FRACTION = 0.18f
        const val PHONE_PAD_LABEL_GAP_DP = 1f
        const val PHONE_PAD_SUB_LABEL_ALPHA_FRACTION = 0.78f
        const val SLIDE_HYSTERESIS_PITCH_FRACTION = 0.55f
        const val PRESSED_LAYER_OPACITY = 0.2f
        /** 翻译 over an empty field, as on iOS. */
        const val DIMMED_TRANSLATE_ALPHA = 102
        const val TRANSLATE_FLOW_CYCLE_MS = 1200L
        const val PREVIEW_WIDTH_FACTOR = 1.28f
        const val PREVIEW_HEIGHT_FACTOR = 1.18f
        const val PREVIEW_MIN_WIDTH_DP = 44f
        const val PREVIEW_GAP_DP = 6f
        const val PREVIEW_TEXT_SIZE_SP = 30f
        val PREVIEW_SHADOW_COLOR = Color.argb(70, 0, 0, 0)
        const val NUMERIC_TOGGLE_STACKED_TEXT_SIZE_SP = 12f
        const val NUMERIC_TOGGLE_STACKED_MAX_HEIGHT_FRACTION = 0.28f
        const val NUMERIC_TOGGLE_STACKED_GAP_DP = 1f
        val AI_LOADING_COLORS = intArrayOf(
            Color.argb(0, 73, 216, 255),
            Color.argb(88, 73, 216, 255),
            Color.argb(128, 140, 108, 255),
            Color.argb(118, 255, 95, 189),
            Color.argb(104, 255, 204, 92),
            Color.argb(112, 88, 230, 161),
            Color.argb(0, 73, 216, 255),
        )
        val AI_LOADING_POSITIONS = floatArrayOf(0f, 0.18f, 0.34f, 0.5f, 0.66f, 0.82f, 1f)
    }
}

data class ModifierState(
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean = false,
)

sealed class KeyboardAction {
    data class Text(val value: String) : KeyboardAction()
    data class KeyCode(val keyCode: Int) : KeyboardAction()
    data object Backspace : KeyboardAction()
    data object Enter : KeyboardAction()
    /** 9-key pinyin: a digit key (2–9), or 1 for a syllable separator. */
    data class NineKeyDigit(val digit: Char) : KeyboardAction()
    /** 9-key pinyin: the spelling picked from the list beside the keys. */
    data class NineKeySpelling(val index: Int) : KeyboardAction()
    /** 9-key strokes: h s p n z (横 竖 撇 点 折) or * for any. */
    data class Stroke(val stroke: Char) : KeyboardAction()
    /** 重输: drops what is being composed. */
    data object ClearComposition : KeyboardAction()
    /** 9-key: switches between pinyin and strokes. */
    data object ToggleNineKeyInput : KeyboardAction()
}
