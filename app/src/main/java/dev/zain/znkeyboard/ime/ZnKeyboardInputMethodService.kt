package dev.zain.znkeyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.typing.AdaptiveTypingEngine
import dev.zain.znkeyboard.typing.TypingLearner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import dev.zain.znkeyboard.pinyin.NineKeyEngine
import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import dev.zain.znkeyboard.pinyin.PinyinLearner
import dev.zain.znkeyboard.pinyin.PinyinTapBuffer
import dev.zain.znkeyboard.pinyin.PinyinUserData
import dev.zain.znkeyboard.pinyin.StrokeIndex
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.util.Locale

class ZnKeyboardInputMethodService : InputMethodService(),
    ZnKeyboardView.Callback,
    ClipboardPanelView.Callback,
    ClipboardSearchView.Callback,
    AgentReviewView.Callback,
    PinyinCandidateBar.Callback {
    private var keyboardView: ZnKeyboardView? = null
    private var pinyinBar: PinyinCandidateBar? = null
    private var pinyinModeEnabled = false
    /** 中/英 tapped in a field that types English by default (email, web address, password); null otherwise. */
    private var fieldPinyinOverride: Boolean? = null
    // Loaded off the main thread; null until ready, and pinyin input passes through until then.
    @Volatile private var pinyinDictionary: PinyinDictionary? = null
    private var pinyinDictionaryLoading = false
    /**
     * What is being composed: letters on the 26-key keyboard, locked syllables and digits on the
     * 9-key pinyin grid ("ni'4426"), stroke letters on the stroke grid ("szh").
     */
    private var pinyinComposing = ""
    /** How each composing letter's tap fitted the keys, so the decoder can weigh near misses. */
    private val pinyinTaps = PinyinTapBuffer()
    private var chineseKeyboard = KeyboardSettings.ChineseKeyboard.Qwerty
    /** The spellings shown beside the 9-key grid, in the order NineKeySpelling indexes them. */
    private var nineKeySpellingOptions: List<String> = emptyList()
    @Volatile private var strokeIndex: StrokeIndex? = null
    private var strokeIndexLoading = false
    private var pinyinCandidates: List<PinyinEngine.Candidate> = emptyList()
    // Shown in the candidate bar after a commit, while nothing is composing.
    private var pinyinPredictions: List<PinyinLearner.Prediction> = emptyList()
    private var pinyinPredictionsEnabled = true
    private val pinyinLearner = PinyinLearner(PinyinUserData())
    private lateinit var pinyinLearningStore: PinyinLearningStore
    // Generation of the learned data in memory; -1 until the first load.
    private var pinyinLearningGeneration = -1
    private var pinyinSavedClock = 0L
    // Whether the rewrite in flight should also translate (翻译) or only polish (润色).
    private var rewriteTranslates = true
    private var inputRoot: LinearLayout? = null
    private var keyboardContainer: FrameLayout? = null
    private var clipboardPanelView: ClipboardPanelView? = null
    private var clipboardSearchView: ClipboardSearchView? = null
    private var agentReviewView: AgentReviewView? = null
    private var currentEditorInfo: EditorInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var voiceRecorder: VoiceRecorder? = null
    private var voiceProcessing = false
    private var voiceGeneration = 0
    private var voiceHintRunnable: Runnable? = null
    private lateinit var typingEngine: AdaptiveTypingEngine
    private var appliedThemeSignature: String? = null
    private var autoCapitalizeEnabled = true
    private var doubleSpacePeriodEnabled = true
    private var lastSpaceCommitAt = 0L
    // Edits we issued whose onUpdateSelection callbacks have not arrived yet. Any other selection
    // update means the caret moved underneath us and typing context must be resynced.
    private var pendingOwnSelectionUpdates = 0
    private var lastOwnEditAt = 0L
    private var activeSurface = KeyboardSurface.Keyboard
    private var activeRequestTarget: AgentEditTarget? = null
    private var activeReview: AgentReview? = null
    private var agentError: String? = null
    private var agentLoading = false
    /** After a capital typed in pinyin: letters go in as English until a space or a mark. */
    private var englishRun = false
    private var subtypeSynced = false
    private var requestGeneration = 0
    private var backspaceGestureDeleteState: BackspaceGestureDeleteState? = null
    private var spaceCursorDragState: SpaceCursorDragState? = null
    private var lastCapturedClipboardSnapshot: ClipboardSnapshot? = null
    private val clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
        capturePrimaryClipboardText(inferSourceApp = true)
    }
    private var clipboardListenerRegistered = false
    private val clipboardManager: ClipboardManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onCreate() {
        super.onCreate()
        typingEngine = AdaptiveTypingEngine(this).also { engine ->
            engine.load(KeyboardSettings.readTypingModelGeneration(this))
        }
        pinyinLearningStore = PinyinLearningStore(this)
        ClipboardHistoryStore.deleteLegacyRewriteHistory(this)
        KeyboardSettings.deleteLegacySnippetData(this)
        KeyboardSettings.deleteLegacyMacroData(this)
        KeyboardSettings.migrateAgentProviders(this)
        KeyboardSettings.migrateSecondRowClipboard(this)
        ClipboardLinkPreviewRepository.loadPersistedPreviews(this)
        registerClipboardListener()
        capturePrimaryClipboardText()
        // Only schedules the settings app's daily check and usage ping; the keyboard itself never goes online for them.
        dev.zain.znkeyboard.UpdateCheckJob.sync(this)
    }

    override fun onCreateInputView(): View {
        appliedThemeSignature = ImeColors.apply(this, KeyboardSettings.readKeyboardTheme(this))
        // Cached panels hold colors from the previous theme; rebuild them lazily.
        clipboardPanelView = null
        agentReviewView = null
        activeSurface = KeyboardSurface.Keyboard
        val keyboard = ZnKeyboardView(this).also { view ->
            keyboardView = view
            view.callback = this
            view.typingEngine = typingEngine
            view.setPhonePadMode(shouldUsePhonePadMode(currentEditorInfo))
            view.setFieldKind(fieldKindFor(currentEditorInfo))
            view.setPinyinMode(pinyinWanted())
        }
        val clipboardSearch = ClipboardSearchView(this).also { view ->
            clipboardSearchView = view
            view.callback = this
        }
        val candidateBar = PinyinCandidateBar(this).also { bar ->
            pinyinBar = bar
            bar.callback = this
            renderPinyinBar()
        }
        val keyboardSlot = FrameLayout(this).also { container ->
            keyboardContainer = container
            container.addView(
                keyboard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // Candidates cover the toolbar row instead of adding a row, so typing never resizes the keyboard.
        val keyboardStack = FrameLayout(this).apply {
            addView(
                keyboardSlot,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(candidateBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        }
        keyboard.onCandidateStripChanged = { top, bottom ->
            val params = candidateBar.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != top || params.height != bottom - top) {
                params.topMargin = top
                params.height = bottom - top
                // Reported from the keyboard's own layout pass; apply on the next frame.
                candidateBar.post { candidateBar.layoutParams = params }
            }
        }
        return LinearLayout(this).apply {
            inputRoot = this
            orientation = LinearLayout.VERTICAL
            addView(
                clipboardSearch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                keyboardStack,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            applyKeyboardSettings()
            keyboard.setEnterLabel(resolveEnterLabel(currentEditorInfo))
            renderSecondRow()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // A new field: whatever was composing belonged to the old one.
        clearPinyinComposition()
        englishRun = false
        currentEditorInfo = attribute
        keyboardView?.setPhonePadMode(shouldUsePhonePadMode(attribute))
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        // Local only, at most one write a day; the daily job sends the anonymous ping.
        dev.zain.znkeyboard.UsageStats.markUsed(this)
        val themeSignature = ImeColors.apply(this, KeyboardSettings.readKeyboardTheme(this))
        if (appliedThemeSignature != null && themeSignature != appliedThemeSignature) {
            setInputView(onCreateInputView())
        }
        typingEngine.resetIfGenerationChanged(KeyboardSettings.readTypingModelGeneration(this))
        typingEngine.personalizationAllowed = isPersonalizationAllowed(info)
        pendingOwnSelectionUpdates = 0
        typingEngine.learner.onContextChanged(currentInputConnection?.getTextBeforeCursor(TYPING_CONTEXT_CHARS, 0))
        pinyinLearner.learningEnabled = KeyboardSettings.readPinyinLearningEnabled(this) && isPersonalizationAllowed(info)
        reloadPinyinLearningIfReset()
        keyboardView?.setPhonePadMode(shouldUsePhonePadMode(info))
        keyboardView?.setFieldKind(fieldKindFor(info))
        fieldPinyinOverride = null
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
        keyboardView?.setEnterLabel(resolveEnterLabel(info))
        if (!subtypeSynced) {
            // Versions before 1.7.2 had only an English subtype; line the system up with the saved mode once.
            subtypeSynced = true
            syncSystemSubtype()
        }
        renderSecondRow()
        capturePrimaryClipboardText()
        updateAutoShift()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelVoiceInput()
        commitPinyinRaw()
        dismissPinyinPredictions()
        typingEngine.save()
        savePinyinLearning()
        keyboardView?.clearLatchedModifiers()
        keyboardView?.resetToLetters()
        resetAgentState(returnToKeyboard = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelVoiceInput()
        requestGeneration++
        unregisterClipboardListener()
        typingEngine.shutdown()
        savePinyinLearning()
        pinyinLearningStore.shutdown()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (SystemClock.uptimeMillis() - lastOwnEditAt > OWN_EDIT_SETTLE_MS) pendingOwnSelectionUpdates = 0
        if (pendingOwnSelectionUpdates > 0) {
            // Our own edit; a late report from an earlier commit must not drop a newer composition.
            pendingOwnSelectionUpdates--
            return
        }
        if (pinyinComposing.isNotEmpty() && candidatesStart < 0) {
            // The editor dropped our composing span (tap elsewhere, app cleared the field).
            clearPinyinComposition()
        }
        if (!isRawKeyEventEditor(currentEditorInfo) && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) {
            // The user tapped elsewhere in the text, or the app edited it: accept and resync.
            dismissPinyinPredictions()
            englishRun = false
            typingEngine.learner.onContextChanged(
                if (newSelStart == newSelEnd) currentInputConnection?.getTextBeforeCursor(TYPING_CONTEXT_CHARS, 0) else null,
            )
            updateAutoShift()
        }
    }

    override fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState) {
        if (activeSurface == KeyboardSurface.ClipboardSearch && handleClipboardSearchKeyboardAction(action, modifiers)) {
            return
        }
        // The field is selected while a rewrite runs; a key (pinyin too) must not replace all of it.
        if (agentLoading) cancelActiveAgentForEditorChange()
        // Predictions follow the last commit; any key but a letter starting the next word ends that.
        if (pinyinComposing.isEmpty() && !isLetterAction(action)) dismissPinyinPredictions()
        if (activeSurface == KeyboardSurface.Keyboard && handlePinyinAction(action, modifiers)) {
            return
        }
        when (action) {
            KeyboardAction.Backspace -> handleBackspace(modifiers)
            KeyboardAction.Enter -> handleEnter(modifiers)
            is KeyboardAction.KeyCode -> {
                cancelActiveAgentForEditorChange()
                if (modifiers.shift) {
                    sendShiftedKeys(action.keyCode, 1, modifiers)
                } else {
                    sendKey(action.keyCode, modifiers)
                }
            }
            is KeyboardAction.Text -> handleText(action.value, modifiers)
            // 9-key keys outside pinyin (a password field, say): nothing to compose.
            else -> Unit
        }
    }

    override fun onBackspaceGestureDeleteStarted(): Boolean {
        return startBackspaceGestureDelete()
    }

    override fun onBackspaceGestureDeleteChanged(wordCount: Int) {
        updateBackspaceGestureDeleteSelection(wordCount)
    }

    override fun onBackspaceGestureDeleteFinished() {
        finishBackspaceGestureDelete()
    }

    override fun onBackspaceGestureDeleteCancelled() {
        cancelBackspaceGestureDelete()
    }

    override fun onSpaceCursorDragStarted(extendSelection: Boolean): Boolean {
        return startSpaceCursorDrag(extendSelection)
    }

    override fun onSpaceCursorDragChanged(characterDelta: Int) {
        updateSpaceCursorDrag(characterDelta)
    }

    override fun onSpaceCursorDragFinished() {
        finishSpaceCursorDrag()
    }

    override fun onSpaceCursorDragCancelled() {
        finishSpaceCursorDrag()
    }

    override fun onClipboardPanelRequested() {
        showClipboardPanel()
    }

    override fun onClipboardPanelClosed() {
        showKeyboardPanel()
    }

    override fun onClipboardSearchRequested() {
        showClipboardSearchPanel()
    }

    override fun onClipboardSearchClosed() {
        showClipboardPanel()
    }

    override fun onClipboardSearchItemSelected(text: String) {
        pasteClipboardHistoryText(text)
    }

    override fun onClipboardSearchItemDeleted(text: String) {
        deleteClipboardHistoryText(text)
    }

    override fun onAgentRewriteRequested() {
        rewriteTranslates = true
        // Rewrite reads the field, so turn pending pinyin into characters first.
        commitPinyinWholeTop()
        startRewriteFromCurrentEditor()
    }

    override fun onAgentPolishOnlyRequested() {
        rewriteTranslates = false
        commitPinyinWholeTop()
        startRewriteFromCurrentEditor()
    }

    override fun onVoiceInputStarted(): Boolean {
        if (!KeyboardSettings.readVoiceInputEnabled(this) || voiceProcessing) return false
        voiceInputProblem()?.let { problem ->
            showVoiceHint(problem)
            return false
        }
        val recorder = VoiceRecorder(
            maxDurationMs = AgentDefaults.MAX_VOICE_CLIP_MS,
            onLevel = { level, elapsedMs ->
                if (voiceRecorder != null) {
                    keyboardView?.setVoiceIndicator(ZnKeyboardView.VoiceIndicator.Recording(level, elapsedMs))
                }
            },
            onLimitReached = { finishVoiceInput(cancelled = false) },
        )
        if (!recorder.start()) {
            showVoiceHint(if (pinyinModeEnabled) "麦克风正被占用。" else "The microphone is busy.")
            return false
        }
        // The spoken text lands at the cursor, so settle any pending pinyin first.
        commitPinyinWholeTop()
        voiceRecorder = recorder
        keyboardView?.setVoiceIndicator(ZnKeyboardView.VoiceIndicator.Recording(0f, 0L))
        return true
    }

    override fun onVoiceInputFinished(cancelled: Boolean) {
        finishVoiceInput(cancelled)
    }

    override fun onAgentReviewApply() {
        applyAgentReview()
    }

    override fun onAgentErrorCopy() {
        copyAgentError()
    }

    override fun onAgentReviewCancel() {
        cancelAgentReview()
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        pasteClipboardHistoryText(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        deleteClipboardHistoryText(text)
    }

    private fun applyKeyboardSettings() {
        pinyinModeEnabled = KeyboardSettings.readPinyinModeEnabled(this)
        keyboardView?.setPinyinMode(pinyinWanted())
        if (activeSurface != KeyboardSurface.ClipboardSearch) keyboardView?.setEnterLabel(resolveEnterLabel(currentEditorInfo))
        refreshRewriteLabel()
        setChineseKeyboard(KeyboardSettings.readChineseKeyboard(this), save = false)
        if (pinyinWanted()) ensurePinyinDictionary()
        pinyinPredictionsEnabled = KeyboardSettings.readPinyinPredictionsEnabled(this)
        val heightScale = KeyboardSettings.readHeightScale(this)
        typingEngine.adaptiveEnabled = KeyboardSettings.readAdaptiveTouchEnabled(this)
        autoCapitalizeEnabled = KeyboardSettings.readAutoCapitalizeEnabled(this)
        doubleSpacePeriodEnabled = KeyboardSettings.readDoubleSpacePeriodEnabled(this)
        val bottomPaddingDp = KeyboardSettings.readBottomPaddingDp(this)
        keyboardView?.let { view ->
            view.setHeightScale(heightScale)
            view.setBottomPaddingDp(bottomPaddingDp)
            view.setFunctionKeyBackgroundsEnabled(KeyboardSettings.readFunctionKeyBackgroundsEnabled(this))
            view.setKeyHapticsEnabled(KeyboardSettings.readKeyHapticsEnabled(this))
            view.setKeyPreviewEnabled(KeyboardSettings.readKeyPreviewEnabled(this))
            view.setNumberRowVisible(KeyboardSettings.readNumberRowEnabled(this))
            view.setVoiceInputEnabled(KeyboardSettings.readVoiceInputEnabled(this))
            view.setUpperRowKeyIds(KeyboardSettings.readUpperRowKeyIds(this))
            view.setSecondRowButtonIds(KeyboardSettings.readSecondRowButtonIds(this))
            view.setKeyboardRowOrder(KeyboardSettings.readKeyboardRowOrder(this))
        }
        clipboardPanelView?.let { view ->
            view.setHeightScale(heightScale)
            view.submitClipboardHistory(ClipboardHistoryStore.read(this))
        }
        clipboardSearchView?.let { view ->
            view.setClipboardEntries(ClipboardHistoryStore.read(this))
        }
        agentReviewView?.setHeightScale(heightScale)
        renderSecondRow()
    }

    private fun showClipboardPanel() {
        hideClipboardSearchView()
        capturePrimaryClipboardText()
        val panel = clipboardPanelView ?: ClipboardPanelView(this).also { view ->
            clipboardPanelView = view
            view.callback = this
        }
        panel.setHeightScale(KeyboardSettings.readHeightScale(this))
        panel.submitClipboardHistory(ClipboardHistoryStore.read(this))
        activeSurface = KeyboardSurface.Clipboard
        swapKeyboardSurface(panel)
        renderSecondRow()
    }

    private fun showClipboardSearchPanel() {
        val keyboard = keyboardView ?: return
        val search = clipboardSearchView ?: return
        capturePrimaryClipboardText()
        search.setClipboardEntries(ClipboardHistoryStore.read(this))
        search.clearSearch()
        search.visibility = View.VISIBLE
        keyboard.setEnterLabel("Search")
        activeSurface = KeyboardSurface.ClipboardSearch
        swapKeyboardSurface(keyboard)
        renderSecondRow()
    }

    private fun showKeyboardPanel() {
        hideClipboardSearchView()
        activeSurface = KeyboardSurface.Keyboard
        val keyboard = keyboardView ?: run {
            renderSecondRow()
            return
        }
        keyboard.setEnterLabel(resolveEnterLabel(currentEditorInfo))
        swapKeyboardSurface(keyboard)
        renderSecondRow()
        typingEngine.learner.onContextChanged(currentInputConnection?.getTextBeforeCursor(TYPING_CONTEXT_CHARS, 0))
        updateAutoShift()
    }

    private fun showAgentReviewPanel(review: AgentReview) {
        hideClipboardSearchView()
        val reviewView = agentReviewView ?: AgentReviewView(this).also { view ->
            agentReviewView = view
            view.callback = this
        }
        reviewView.setHeightScale(KeyboardSettings.readHeightScale(this))
        reviewView.renderReview(review.replacementText, chinese = pinyinModeEnabled)
        activeSurface = KeyboardSurface.Review
        swapKeyboardSurface(reviewView)
        renderSecondRow()
    }

    private fun showAgentErrorPanel(message: String) {
        hideClipboardSearchView()
        val reviewView = agentReviewView ?: AgentReviewView(this).also { view ->
            agentReviewView = view
            view.callback = this
        }
        reviewView.setHeightScale(KeyboardSettings.readHeightScale(this))
        reviewView.renderError(
            if (pinyinModeEnabled) AgentDefaults.CHINESE_ERROR_MESSAGES[message] ?: message else message,
            chinese = pinyinModeEnabled,
        )
        activeSurface = KeyboardSurface.Review
        swapKeyboardSurface(reviewView)
        renderSecondRow()
    }

    private fun swapKeyboardSurface(surface: View) {
        val container = keyboardContainer ?: return
        if (surface !== keyboardView) commitPinyinRaw()
        if (surface === keyboardView) renderPinyinBar() else pinyinBar?.visibility = View.GONE
        (surface.parent as? ViewGroup)?.removeView(surface)
        container.removeAllViews()
        container.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun attachAboveKeyboard(surface: View) {
        val root = inputRoot ?: return
        val container = keyboardContainer ?: return
        (surface.parent as? ViewGroup)?.removeView(surface)
        val keyboardIndex = root.indexOfChild(container).takeIf { it >= 0 } ?: root.childCount
        root.addView(
            surface,
            keyboardIndex,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun hideClipboardSearchView() {
        clipboardSearchView?.let { view ->
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
            view.clearSearch()
        }
    }

    private fun handleClipboardSearchKeyboardAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        if (modifiers.hasHardwareMeta) {
            return true
        }
        val searchView = clipboardSearchView ?: return true
        when (action) {
            KeyboardAction.Backspace -> searchView.deleteQueryCharacter()
            KeyboardAction.Enter -> Unit
            is KeyboardAction.KeyCode -> {
                if (action.keyCode == KeyEvent.KEYCODE_DEL) {
                    searchView.deleteQueryCharacter()
                }
            }
            is KeyboardAction.Text -> searchView.appendQueryText(action.value)
            else -> Unit
        }
        return true
    }

    private fun handleText(value: String, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        if (modifiers.hasHardwareMeta && value.length == 1) {
            val keyCode = keyCodeFor(value[0])
            if (keyCode != null) {
                sendKey(keyCode, modifiers)
                return
            }
        }
        noteOwnEdit()
        if (wrapSelectedText(inputConnection, value)) {
            updateAutoShift()
            return
        }
        if (value == " " && applyDoubleSpacePeriod(inputConnection)) {
            updateAutoShift()
            return
        }
        if (inputConnection.commitText(value, 1)) {
            lastSpaceCommitAt = if (value == " ") SystemClock.uptimeMillis() else 0L
            updateAutoShift()
        }
    }

    /** "word␣" + quick second space → "word.␣", like Gboard and iOS. */
    private fun applyDoubleSpacePeriod(inputConnection: InputConnection): Boolean {
        if (!doubleSpacePeriodEnabled || isRawKeyEventEditor(currentEditorInfo)) return false
        if (SystemClock.uptimeMillis() - lastSpaceCommitAt > DOUBLE_SPACE_PERIOD_WINDOW_MS) return false
        val before = inputConnection.getTextBeforeCursor(3, 0) ?: return false
        if (before.length < 2 || before[before.length - 1] != ' ' || !before[before.length - 2].isLetterOrDigit()) {
            return false
        }
        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) return false
        inputConnection.beginBatchEdit()
        inputConnection.deleteSurroundingText(1, 0)
        inputConnection.commitText(". ", 1)
        inputConnection.endBatchEdit()
        lastSpaceCommitAt = 0L
        typingEngine.learner.onContextChanged(inputConnection.getTextBeforeCursor(TYPING_CONTEXT_CHARS, 0))
        return true
    }

    override fun onPinyinModeToggled() {
        if (isLatinField(currentEditorInfo)) {
            // These fields type English unless asked: switch this field only, not the saved mode.
            switchFieldLanguage(!pinyinWanted())
            return
        }
        setPinyinMode(!pinyinModeEnabled)
        syncSystemSubtype()
    }

    private fun switchFieldLanguage(pinyin: Boolean) {
        englishRun = false
        commitPinyinRaw()
        dismissPinyinPredictions()
        fieldPinyinOverride = pinyin
        keyboardView?.setPinyinMode(pinyin)
        refreshRewriteLabel()
        if (pinyin) ensurePinyinDictionary()
        updateAutoShift()
    }

    /** Whether this field types pinyin: the saved 中/英 mode, but English in email, web and password fields. */
    private fun pinyinWanted(): Boolean = fieldPinyinOverride ?: (pinyinModeEnabled && !isLatinField(currentEditorInfo))

    private fun isLatinField(info: EditorInfo?): Boolean {
        val kind = fieldKindFor(info)
        return kind == ZnKeyboardView.FieldKind.Email || kind == ZnKeyboardView.FieldKind.Url || isSensitiveEditor(info)
    }

    private fun fieldKindFor(info: EditorInfo?): ZnKeyboardView.FieldKind {
        val inputType = info?.inputType ?: return ZnKeyboardView.FieldKind.Text
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> ZnKeyboardView.FieldKind.Phone
            InputType.TYPE_CLASS_NUMBER -> ZnKeyboardView.FieldKind.Number
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> ZnKeyboardView.FieldKind.Email
                InputType.TYPE_TEXT_VARIATION_URI -> ZnKeyboardView.FieldKind.Url
                else -> ZnKeyboardView.FieldKind.Text
            }
            else -> ZnKeyboardView.FieldKind.Text
        }
    }

    override fun onInputMethodPickerRequested() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    /** The system's language switcher picked one of our subtypes: follow it. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val pinyin = newSubtype?.isPinyin() ?: return
        if (pinyin != pinyinModeEnabled) setPinyinMode(pinyin)
    }

    private fun InputMethodSubtype.isPinyin(): Boolean = languageTag.startsWith("zh") || locale.startsWith("zh")

    /** Makes the system's current subtype (shown in its language switcher) match the 中 / EN mode. */
    private fun syncSystemSubtype() {
        runCatching {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            val ours = manager.inputMethodList.firstOrNull { it.packageName == packageName } ?: return
            val wanted = (0 until ours.subtypeCount).map(ours::getSubtypeAt).firstOrNull { it.isPinyin() == pinyinModeEnabled } ?: return
            val current = manager.currentInputMethodSubtype
            if (current == null || current.isPinyin() != pinyinModeEnabled) switchInputMethod(ours.id, wanted)
        }
    }

    private fun setPinyinMode(enabled: Boolean) {
        englishRun = false
        commitPinyinRaw()
        dismissPinyinPredictions()
        pinyinModeEnabled = enabled
        KeyboardSettings.savePinyinModeEnabled(this, pinyinModeEnabled)
        keyboardView?.setPinyinMode(pinyinWanted())
        if (activeSurface != KeyboardSurface.ClipboardSearch) keyboardView?.setEnterLabel(resolveEnterLabel(currentEditorInfo))
        refreshRewriteLabel()
        if (pinyinWanted()) ensurePinyinDictionary()
        updateAutoShift()
    }

    /** Both modes show polish | translate | the round target-language key, labelled in the mode's language. */
    /** In the field's language, like 润色 / Polish beside it. */
    private fun refreshRewriteLabel() {
        keyboardView?.setRewriteMode(
            if (pinyinWanted()) AgentDefaults.TRANSLATE_REWRITE_LABEL else AgentDefaults.TRANSLATE_REWRITE_LABEL_EN,
            KeyboardSettings.readAgentTranslateTarget(this).code,
        )
    }

    override fun onTranslateLanguageMenuRequested() {
        val bar = pinyinBar ?: return
        if (bar.isChoosingLanguage) {
            renderPinyinBar()
            return
        }
        val current = KeyboardSettings.readAgentTranslateTarget(this)
        bar.renderLanguageChoices(
            AgentDefaults.TRANSLATE_LANGUAGES.map { it.code },
            AgentDefaults.TRANSLATE_LANGUAGES.indexOf(current),
        )
    }

    override fun onTranslateLanguageSelected(index: Int) {
        val language = AgentDefaults.TRANSLATE_LANGUAGES.getOrNull(index) ?: return
        KeyboardSettings.saveAgentTranslateTarget(this, language.code)
        refreshRewriteLabel()
        renderPinyinBar()
    }

    /**
     * Long press on a candidate forgets what was learned about it (a sentence: about its words),
     * so a wrong pick stops coming first. Dictionary words stay, at their own rank.
     */
    override fun onPinyinCandidateLongPressed(index: Int) {
        val dictionary = pinyinDictionary ?: return
        val texts = if (pinyinComposing.isEmpty()) {
            listOfNotNull(pinyinPredictions.getOrNull(index)?.text)
        } else {
            pinyinCandidates.getOrNull(index)?.let { candidate -> candidate.parts?.map { it.text } ?: listOf(candidate.text) }.orEmpty()
        }
        val learned = if (pinyinComposing.isEmpty()) texts else texts.filter { dictionary.isLearned(it) }
        if (learned.isEmpty()) {
            Toast.makeText(this, t("这个词没有学习记录，不用删除。", "Nothing learned about this one."), Toast.LENGTH_SHORT).show()
            return
        }
        learned.forEach(pinyinLearner::forget)
        savePinyinLearning()
        Toast.makeText(this, t("已删除学习记录：${learned.joinToString("、")}", "Forgot: ${learned.joinToString(", ")}"), Toast.LENGTH_SHORT).show()
        if (pinyinComposing.isNotEmpty()) updatePinyinComposition(pinyinComposing) else showPinyinPredictions()
    }

    override fun onPinyinCandidateSelected(index: Int) {
        if (pinyinComposing.isEmpty()) pickPinyinPrediction(index) else selectPinyinCandidate(index, byHand = true)
    }

    private fun isPinyinActive(): Boolean {
        val info = currentEditorInfo
        return pinyinWanted() && pinyinDictionary != null &&
            !isRawKeyEventEditor(info) && !shouldUsePhonePadMode(info)
    }

    /**
     * Loads the dictionary, or rebuilds its index when the fuzzy rules changed, off the main
     * thread, with the learned words applied. The first load also reads the learned data.
     */
    private fun ensurePinyinDictionary() {
        if (pinyinDictionaryLoading) return
        val rules = KeyboardSettings.readPinyinFuzzyRules(this)
        val current = pinyinDictionary
        if (current != null && current.fuzzy.rules == rules) return
        pinyinDictionaryLoading = true
        val appContext = applicationContext
        val generation = KeyboardSettings.readPinyinLearningGeneration(this)
        // Learning keeps going on the main thread meanwhile, so hand the loader a copy.
        val inMemory = pinyinLearner.data.takeIf { pinyinLearningGeneration == generation }
        val snapshot = inMemory?.words()?.map { PinyinUserData.Word(it.text, it.syllables, it.uses, it.lastUsed) }
        val snapshotClock = inMemory?.clock ?: 0L
        Thread {
            val saved = if (inMemory == null) PinyinLearningStore.read(appContext) ?: PinyinUserData() else null
            val loaded = runCatching {
                val dictionary = current?.withFuzzy(rules) ?: appContext.assets.open(PINYIN_DICTIONARY_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { PinyinDictionary.load(it, rules) }
                        .also { it.languageModel = loadPinyinLanguageModel(appContext) }
                for (word in saved?.words() ?: snapshot.orEmpty()) dictionary.learn(word.text, word.syllables, word.uses)
                // Pairs from data in memory are applied on the main thread below, with the newest words.
                saved?.pairs()?.forEach { (previous, next, uses) -> dictionary.learnPair(previous, next, uses) }
                // Build the 9-key index here rather than on the first key press.
                if (chineseKeyboard.isNineKey) dictionary.nineKeySpellings("2")
                dictionary
            }.getOrNull()
            mainHandler.post {
                pinyinDictionaryLoading = false
                if (saved != null) {
                    pinyinLearner.data = saved
                    pinyinLearningGeneration = generation
                    pinyinSavedClock = saved.clock
                }
                if (loaded != null) {
                    if (saved == null) pinyinLearner.applyTo(loaded, pinyinLearner.data.wordsChangedSince(snapshotClock))
                    pinyinLearner.dictionary = loaded
                    pinyinDictionary = loaded
                }
                updateAutoShift()
            }
        }.start()
    }

    /**
     * The sentence model, memory-mapped from the APK (stored uncompressed, see build.gradle.kts)
     * so it costs no heap; null if it is missing or unreadable, and sentences rank as before.
     */
    private fun loadPinyinLanguageModel(context: Context): PinyinLanguageModel? = runCatching {
        context.assets.openFd(PINYIN_LANGUAGE_MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                PinyinLanguageModel.load(channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
            }
        }
    }.onFailure { Log.w(TAG, "pinyin language model unavailable", it) }.getOrNull()

    /** Settings cleared or restored learned pinyin while the keyboard was away: drop ours and reread. */
    private fun reloadPinyinLearningIfReset() {
        val generation = KeyboardSettings.readPinyinLearningGeneration(this)
        if (pinyinLearningGeneration == -1 || generation == pinyinLearningGeneration) return
        pinyinLearningGeneration = generation
        pinyinDictionary?.forgetLearned()
        pinyinLearner.data = PinyinUserData()
        pinyinLearner.breakChain()
        pinyinSavedClock = 0L
        val appContext = applicationContext
        Thread {
            val saved = PinyinLearningStore.read(appContext)
            mainHandler.post {
                if (saved == null || generation != pinyinLearningGeneration) return@post
                pinyinLearner.data = saved
                pinyinSavedClock = saved.clock
                pinyinDictionary?.let { pinyinLearner.applyTo(it) }
            }
        }.start()
    }

    private fun savePinyinLearning() {
        if (pinyinLearningGeneration == -1) return
        val generation = KeyboardSettings.readPinyinLearningGeneration(this)
        if (generation != pinyinLearningGeneration) {
            reloadPinyinLearningIfReset()
            return
        }
        val data = pinyinLearner.data
        if (data.clock == pinyinSavedClock) return
        pinyinSavedClock = data.clock
        pinyinLearningStore.save(data, generation)
    }

    /** Routes keys to the pinyin composer. Returns false to let normal handling run. */
    private fun handlePinyinAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        if (!isPinyinActive() || modifiers.ctrl || modifiers.alt || modifiers.hasHardwareMeta) {
            return false
        }
        val composing = pinyinComposing.isNotEmpty()
        when (action) {
            is KeyboardAction.NineKeyDigit -> {
                if (action.digit == '1') {
                    // 分词: a syllable boundary, only inside a composition.
                    if (composing && !pinyinComposing.endsWith(NineKeyEngine.SEPARATOR)) {
                        updatePinyinComposition(pinyinComposing + NineKeyEngine.SEPARATOR)
                    }
                } else {
                    updatePinyinComposition(pinyinComposing + action.digit)
                }
                return true
            }
            is KeyboardAction.NineKeySpelling -> {
                val spelling = nineKeySpellingOptions.getOrNull(action.index) ?: return true
                updatePinyinComposition(NineKeyEngine.lock(pinyinComposing, spelling))
                return true
            }
            is KeyboardAction.Stroke -> {
                updatePinyinComposition(pinyinComposing + action.stroke)
                return true
            }
            KeyboardAction.ClearComposition -> {
                if (composing) updatePinyinComposition("")
                return true
            }
            KeyboardAction.ToggleNineKeyInput -> {
                if (composing) updatePinyinComposition("")
                setChineseKeyboard(
                    if (chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyStroke) KeyboardSettings.ChineseKeyboard.NineKeyPinyin
                    else KeyboardSettings.ChineseKeyboard.NineKeyStroke,
                    save = true,
                )
                return true
            }
            is KeyboardAction.Text -> {
                val value = action.value
                val char = value.singleOrNull()
                if (char == null || char.lowercaseChar() !in 'a'..'z') englishRun = false
                when {
                    // Shift in pinyin types English: a capital goes in as it is, and the letters after
                    // it too (iPhone, McDonald), until a space or a mark ends the word.
                    char != null && char in 'A'..'Z' -> {
                        if (composing) commitPinyinWholeTop()
                        englishRun = true
                        return false
                    }
                    char != null && char in 'a'..'z' && englishRun && !composing -> return false
                    char != null && char.lowercaseChar() in 'a'..'z' -> {
                        updatePinyinComposition(pinyinComposing + char.lowercaseChar(), keyboardView?.letterTapCosts, keyboardView?.letterTapSample)
                        return true
                    }
                    !composing -> {
                        val punctuation = char?.let { chinesePunctuationFor(it) } ?: return false
                        handleText(punctuation, modifiers)
                        return true
                    }
                    char == PinyinEngine.SEPARATOR -> {
                        if (!pinyinComposing.endsWith(PinyinEngine.SEPARATOR)) {
                            updatePinyinComposition(pinyinComposing + PinyinEngine.SEPARATOR)
                        }
                        return true
                    }
                    value == " " -> {
                        if (pinyinCandidates.isEmpty()) commitPinyinRaw() else selectPinyinCandidate(0, byHand = false)
                        return true
                    }
                    char != null && char in '1'..'9' && char - '1' < pinyinCandidates.size -> {
                        selectPinyinCandidate(char - '1', byHand = true)
                        return true
                    }
                    else -> {
                        // Punctuation ends the phrase: take the top candidate, then type the mark.
                        commitPinyinWholeTop()
                        val punctuation = char?.let { chinesePunctuationFor(it) } ?: return false
                        handleText(punctuation, modifiers)
                        return true
                    }
                }
            }
            KeyboardAction.Backspace -> {
                if (!composing) return false
                updatePinyinComposition(
                    if (chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyPinyin) NineKeyEngine.backspace(pinyinComposing)
                    else pinyinComposing.dropLast(1),
                )
                return true
            }
            KeyboardAction.Enter -> {
                englishRun = false
                if (!composing) return false
                commitPinyinRaw()
                return true
            }
            else -> {
                commitPinyinRaw()
                return false
            }
        }
    }

    /** Full-width marks for Chinese text; a period right after a digit stays a decimal point. */
    private fun chinesePunctuationFor(char: Char): String? {
        if (char == '.') {
            val before = currentInputConnection?.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && before.last().isDigit()) return null
        }
        return CHINESE_PUNCTUATION[char]
    }

    /**
     * [tap]: how the tap that typed the one letter added fitted each key, if it came from a tap;
     * [sample] is the touch learner's record of it.
     */
    private fun updatePinyinComposition(composing: String, tap: FloatArray? = null, sample: TypingLearner.Sample? = null) {
        val inputConnection = currentInputConnection ?: return
        val dictionary = pinyinDictionary ?: return
        pinyinTaps.follow(pinyinComposing, composing, tap, sample)
        pinyinComposing = composing
        pinyinCandidates = when {
            composing.isEmpty() -> emptyList()
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyPinyin -> NineKeyEngine.candidates(composing, dictionary)
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyStroke -> strokeIndex?.candidates(composing, dictionary).orEmpty()
            else -> PinyinEngine.candidates(composing, dictionary, pinyinTaps, pinyinLearner.context)
        }
        pinyinPredictions = emptyList()
        noteOwnEdit()
        if (composing.isEmpty()) {
            inputConnection.setComposingText("", 1)
            inputConnection.finishComposingText()
        } else {
            // 26-key letters stay as typed in the field; the bar shows them as read, slips corrected.
            inputConnection.setComposingText(if (chineseKeyboard.isNineKey) composingDisplay() else composing, 1)
        }
        renderPinyinBar()
    }

    /** The composition as the field and the bar show it: pinyin for digits, stroke marks for strokes. */
    private fun composingDisplay(): String {
        val dictionary = pinyinDictionary
        return when {
            pinyinComposing.isEmpty() -> ""
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyPinyin && dictionary != null ->
                NineKeyEngine.display(pinyinComposing, dictionary, pinyinCandidates.firstOrNull())
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyStroke -> StrokeIndex.display(pinyinComposing)
            else -> pinyinCandidates.firstOrNull()?.spelling?.replace(' ', PinyinEngine.SEPARATOR) ?: pinyinComposing
        }
    }

    /** The spelling list beside the 9-key grid follows the digits not yet locked. */
    private fun refreshNineKeySpellings() {
        val dictionary = pinyinDictionary
        nineKeySpellingOptions = if (
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyPinyin && pinyinComposing.isNotEmpty() && dictionary != null
        ) {
            NineKeyEngine.spellings(pinyinComposing, dictionary).take(NINE_KEY_SPELLING_SLOTS)
        } else {
            emptyList()
        }
        keyboardView?.setNineKeySpellings(nineKeySpellingOptions.takeIf { pinyinComposing.isNotEmpty() })
    }

    /** Switches between 26-key, 9-key pinyin and stroke input; [save] remembers it as the setting. */
    private fun setChineseKeyboard(keyboard: KeyboardSettings.ChineseKeyboard, save: Boolean) {
        if (save) KeyboardSettings.saveChineseKeyboard(this, keyboard)
        chineseKeyboard = keyboard
        // Password, number and terminal fields type no Chinese; they get the letters instead.
        val info = currentEditorInfo
        val chineseHere = !isRawKeyEventEditor(info) && !isSensitiveEditor(info) && !shouldUsePhonePadMode(info)
        keyboardView?.setChineseKeyboard(if (chineseHere) keyboard else KeyboardSettings.ChineseKeyboard.Qwerty)
        if (keyboard == KeyboardSettings.ChineseKeyboard.NineKeyStroke) ensureStrokeIndex()
        refreshNineKeySpellings()
    }

    private fun ensureStrokeIndex() {
        if (strokeIndex != null || strokeIndexLoading) return
        strokeIndexLoading = true
        val appContext = applicationContext
        Thread {
            val loaded = runCatching {
                appContext.assets.open(STROKE_ASSET).bufferedReader(Charsets.UTF_8).use { StrokeIndex.load(it) }
            }.getOrNull()
            mainHandler.post {
                strokeIndexLoading = false
                strokeIndex = loaded
            }
        }.apply {
            name = "ZnKeyboardStrokeLoad"
            start()
        }
    }

    /** [byHand]: tapped or picked by number, as opposed to space taking the first candidate. */
    private fun selectPinyinCandidate(index: Int, byHand: Boolean) {
        val candidate = pinyinCandidates.getOrNull(index) ?: return
        val inputConnection = currentInputConnection ?: return
        val remaining = pinyinComposing.substring(candidate.consumed.coerceAtMost(pinyinComposing.length))
            .trimStart(PinyinEngine.SEPARATOR)
        // A reading that took a tap as another key teaches the touch model that key (wl → wo).
        candidate.spelling?.let { spelling ->
            for ((at, letter) in PinyinTapBuffer.substitutions(pinyinComposing.substring(0, candidate.consumed.coerceAtMost(pinyinComposing.length)), spelling)) {
                (pinyinTaps.token(at) as? TypingLearner.Sample)?.let { typingEngine.learner.correct(it, letter - 'a') }
            }
        }
        noteOwnEdit()
        inputConnection.beginBatchEdit()
        inputConnection.commitText(candidate.text, 1)
        // A whole sentence teaches its words one by one (and which follows which), not itself.
        candidate.parts?.forEach { part -> pinyinLearner.onPick(part.text, part.syllables, byHand, part.id) }
            ?: pinyinLearner.onPick(candidate.text, candidate.syllables, byHand, candidate.id)
        if (remaining.isEmpty()) {
            pinyinLearner.onCompositionDone()
            pinyinComposing = ""
            pinyinCandidates = emptyList()
            showPinyinPredictions()
        } else {
            updatePinyinComposition(remaining)
        }
        inputConnection.endBatchEdit()
    }

    /**
     * Commits the best candidate for each remaining piece until nothing is left composing.
     * Callers type or insert something else next, so no predictions follow.
     */
    private fun commitPinyinWholeTop() {
        var guard = pinyinComposing.length
        while (pinyinComposing.isNotEmpty() && guard-- > 0) {
            if (pinyinCandidates.isEmpty()) {
                commitPinyinRaw()
                return
            }
            selectPinyinCandidate(0, byHand = false)
        }
        dismissPinyinPredictions()
    }

    private fun showPinyinPredictions() {
        pinyinPredictions = if (pinyinPredictionsEnabled && isPinyinActive()) {
            pinyinLearner.predictions(MAX_PINYIN_PREDICTIONS)
        } else {
            emptyList()
        }
        renderPinyinBar()
    }

    /** Hides predictions and forgets the word they followed. */
    private fun dismissPinyinPredictions() {
        pinyinLearner.breakChain()
        if (pinyinPredictions.isEmpty()) return
        pinyinPredictions = emptyList()
        renderPinyinBar()
    }

    private fun pickPinyinPrediction(index: Int) {
        val prediction = pinyinPredictions.getOrNull(index) ?: return
        val inputConnection = currentInputConnection ?: return
        noteOwnEdit()
        inputConnection.commitText(prediction.text, 1)
        pinyinLearner.onPredictionPicked(prediction)
        showPinyinPredictions()
    }

    private fun renderPinyinBar() {
        // Every change to the composition ends here, so the spelling list follows it too.
        refreshNineKeySpellings()
        val bar = pinyinBar ?: return
        if (pinyinComposing.isEmpty() && pinyinPredictions.isNotEmpty()) {
            bar.render("", pinyinPredictions.map { it.text }, pinyinBarTools())
        } else {
            bar.render(composingDisplay(), pinyinCandidates.map { it.text })
        }
    }

    /** 润色 | 翻译 for the prediction bar when it hides the toolbar's own; predictions only follow pinyin. */
    private fun pinyinBarTools(): PinyinCandidateBar.Tools? {
        val keyboard = keyboardView ?: return null
        if (!keyboard.candidateStripHidesRewrite) return null
        return PinyinCandidateBar.Tools(
            polishLabel = AgentDefaults.POLISH_ONLY_LABEL,
            translateLabel = AgentDefaults.TRANSLATE_REWRITE_LABEL,
            languageCode = KeyboardSettings.readAgentTranslateTarget(this).code,
            enabled = keyboard.rewriteAvailable,
        )
    }

    /** A key that starts or continues a composition: a letter, a 9-key digit or a stroke. */
    private fun isLetterAction(action: KeyboardAction): Boolean =
        action is KeyboardAction.Text && action.value.singleOrNull()?.lowercaseChar() in 'a'..'z' ||
            action is KeyboardAction.NineKeyDigit && action.digit != '1' ||
            action is KeyboardAction.Stroke

    /**
     * Puts the typed letters into the field as-is, e.g. for Enter or leaving the keyboard: 9-key
     * digits as their likeliest pinyin; strokes have no text of their own, so they just go.
     */
    private fun commitPinyinRaw() {
        if (pinyinComposing.isEmpty()) return
        val dictionary = pinyinDictionary
        val raw = when {
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyPinyin && dictionary != null ->
                NineKeyEngine.raw(pinyinComposing, dictionary, pinyinCandidates.firstOrNull())
            chineseKeyboard == KeyboardSettings.ChineseKeyboard.NineKeyStroke -> ""
            else -> pinyinComposing.replace(PinyinEngine.SEPARATOR.toString(), "")
        }
        currentInputConnection?.let { inputConnection ->
            noteOwnEdit()
            inputConnection.commitText(raw, 1)
        }
        pinyinLearner.onCompositionAbandoned()
        pinyinComposing = ""
        pinyinCandidates = emptyList()
        pinyinPredictions = emptyList()
        renderPinyinBar()
    }

    /** Forgets the composition without touching the editor, which already let it go. */
    private fun clearPinyinComposition() {
        pinyinLearner.onCompositionAbandoned()
        pinyinComposing = ""
        pinyinCandidates = emptyList()
        pinyinPredictions = emptyList()
        renderPinyinBar()
    }

    private fun noteOwnEdit() {
        if (isRawKeyEventEditor(currentEditorInfo)) return
        pendingOwnSelectionUpdates++
        lastOwnEditAt = SystemClock.uptimeMillis()
    }

    /** Sentence-start capitalization, honoring the editor's own TYPE_TEXT_FLAG_CAP_* request. */
    private fun updateAutoShift() {
        val view = keyboardView ?: return
        val info = currentEditorInfo
        // Pinyin is lowercase; a sentence-start shift would only get in the way.
        if (!autoCapitalizeEnabled || info == null || isRawKeyEventEditor(info) || isSensitiveEditor(info) ||
            isPinyinActive() ||
            activeSurface != KeyboardSurface.Keyboard
        ) {
            view.setAutoShift(false)
            return
        }
        val capsMode = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        view.setAutoShift(capsMode != 0)
    }

    private fun wrapSelectedText(inputConnection: InputConnection, value: String): Boolean {
        val pair = SELECTION_WRAP_SYMBOLS[value] ?: return false
        val selectedText = inputConnection.getSelectedText(0)?.toString()
        if (selectedText.isNullOrEmpty()) return false

        return inputConnection.commitText("${pair.open}$selectedText${pair.close}", 1)
    }

    private fun handleBackspace(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        lastSpaceCommitAt = 0L
        noteOwnEdit()
        // TYPE_NULL editors such as terminals expect raw key events, not surrounding-text edits.
        if (isRawKeyEventEditor(currentEditorInfo)) {
            sendKey(KeyEvent.KEYCODE_DEL, modifiers)
            return
        }

        val selectedText = inputConnection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            inputConnection.commitText("", 1)
        } else {
            if (!inputConnection.deleteSurroundingTextInCodePoints(1, 0)) {
                if (!inputConnection.deleteSurroundingText(1, 0)) {
                    sendKey(KeyEvent.KEYCODE_DEL, modifiers)
                }
            }
        }
        updateAutoShift()
    }

    private fun startBackspaceGestureDelete(): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val info = currentEditorInfo
        if (
            activeSurface != KeyboardSurface.Keyboard ||
            isRawKeyEventEditor(info) ||
            isSensitiveEditor(info)
        ) {
            return false
        }

        val snapshot = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1) ?: return false
        val cursorOffset = snapshot.selectionStart
        if (cursorOffset != snapshot.selectionEnd) return false

        val localCursorOffset = cursorOffset - snapshot.textStartOffset
        if (localCursorOffset !in 0..snapshot.text.length) return false

        val beforeCursor = snapshot.text
            .substring(0, localCursorOffset)
            .takeLast(GESTURE_DELETE_CONTEXT_CHARS)
        val wordSelectionStarts = previousGestureDeleteWordStarts(
            beforeCursor = beforeCursor,
            cursorOffset = cursorOffset,
        )
        if (wordSelectionStarts.isEmpty()) return false
        val wordSelectionTexts = wordSelectionStarts.map { selectionStart ->
            beforeCursor.takeLast(cursorOffset - selectionStart)
        }

        cancelActiveAgentForEditorChange()
        inputConnection.finishComposingText()
        backspaceGestureDeleteState = BackspaceGestureDeleteState(
            anchorOffset = cursorOffset,
            originalSelectionStart = snapshot.selectionStart,
            originalSelectionEnd = snapshot.selectionEnd,
            wordSelectionStarts = wordSelectionStarts,
            wordSelectionTexts = wordSelectionTexts,
        )
        return true
    }

    private fun updateBackspaceGestureDeleteSelection(wordCount: Int) {
        val state = backspaceGestureDeleteState ?: return
        val inputConnection = currentInputConnection ?: return
        val coercedWordCount = wordCount.coerceIn(0, state.wordSelectionStarts.size)
        if (coercedWordCount == state.currentWordCount) return

        val selectionStart = if (coercedWordCount == 0) {
            state.anchorOffset
        } else {
            state.wordSelectionStarts[coercedWordCount - 1]
        }
        var selectionUpdated = false
        inputConnection.beginBatchEdit()
        try {
            selectionUpdated = inputConnection.setSelection(selectionStart, state.anchorOffset)
        } finally {
            inputConnection.endBatchEdit()
        }
        if (selectionUpdated) {
            state.currentWordCount = coercedWordCount
        } else {
            cancelBackspaceGestureDelete()
        }
    }

    private fun finishBackspaceGestureDelete() {
        val state = backspaceGestureDeleteState ?: return
        backspaceGestureDeleteState = null
        val inputConnection = currentInputConnection ?: return
        val selectedWordCount = state.currentWordCount.coerceIn(0, state.wordSelectionStarts.size)
        if (selectedWordCount == 0) {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
            return
        }

        val selectionStart = state.wordSelectionStarts[selectedWordCount - 1]
        val expectedText = state.wordSelectionTexts[selectedWordCount - 1]
        if (!isBackspaceGestureDeleteTargetCurrent(state, selectionStart, expectedText)) {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
            return
        }

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            if (inputConnection.setSelection(selectionStart, state.anchorOffset)) {
                committed = inputConnection.commitText("", 1)
            }
        } finally {
            inputConnection.endBatchEdit()
        }

        if (committed) {
        } else {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
        }
    }

    private fun cancelBackspaceGestureDelete() {
        val state = backspaceGestureDeleteState ?: return
        backspaceGestureDeleteState = null
        currentInputConnection?.let { inputConnection ->
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
        }
    }

    private fun startSpaceCursorDrag(extendSelection: Boolean): Boolean {
        val inputConnection = currentInputConnection ?: return false
        if (activeSurface != KeyboardSurface.Keyboard) return false

        cancelActiveAgentForEditorChange()
        if (isRawKeyEventEditor(currentEditorInfo) || isSensitiveEditor(currentEditorInfo)) {
            spaceCursorDragState = SpaceCursorDragState.KeyEvents(extendSelection = extendSelection)
            return true
        }

        val snapshot = captureEditorSnapshot(CURSOR_DRAG_CONTEXT_CHARS) ?: run {
            spaceCursorDragState = SpaceCursorDragState.KeyEvents(extendSelection = extendSelection)
            return true
        }
        val minOffset = snapshot.textStartOffset
        val maxOffset = snapshot.textStartOffset + snapshot.text.length
        // TextView reports selectionStart as the anchor, so a backwards selection keeps its direction.
        val selectionAnchor = if (extendSelection) snapshot.selectionStart.coerceIn(minOffset, maxOffset) else null
        val anchorOffset = if (extendSelection) {
            snapshot.selectionEnd
        } else {
            maxOf(snapshot.selectionStart, snapshot.selectionEnd)
        }.coerceIn(minOffset, maxOffset)
        inputConnection.finishComposingText()
        spaceCursorDragState = SpaceCursorDragState.Selection(
            anchorOffset = anchorOffset,
            minOffset = minOffset,
            maxOffset = maxOffset,
            currentOffset = anchorOffset,
            selectionAnchor = selectionAnchor,
        )
        return true
    }

    private fun updateSpaceCursorDrag(characterDelta: Int) {
        val state = spaceCursorDragState ?: return
        val coercedDelta = characterDelta.coerceIn(-CURSOR_DRAG_MAX_CHARS, CURSOR_DRAG_MAX_CHARS)
        when (state) {
            is SpaceCursorDragState.Selection -> updateSelectionSpaceCursorDrag(state, coercedDelta)
            is SpaceCursorDragState.KeyEvents -> updateKeyEventSpaceCursorDrag(state, coercedDelta)
        }
    }

    private fun updateSelectionSpaceCursorDrag(
        state: SpaceCursorDragState.Selection,
        characterDelta: Int,
    ) {
        val inputConnection = currentInputConnection ?: return
        val desiredOffset = state.anchorOffset + characterDelta
        val targetOffset = desiredOffset.coerceIn(state.minOffset, state.maxOffset)
        if (targetOffset == state.currentOffset) {
            if (desiredOffset < state.minOffset || desiredOffset > state.maxOffset) {
                val fallbackState = state.toKeyEvents()
                spaceCursorDragState = fallbackState
                updateKeyEventSpaceCursorDrag(fallbackState, characterDelta)
                return
            }
            state.lastCharacterDelta = characterDelta
            return
        }

        var selectionUpdated = false
        inputConnection.beginBatchEdit()
        try {
            selectionUpdated = inputConnection.setSelection(state.selectionAnchor ?: targetOffset, targetOffset)
        } finally {
            inputConnection.endBatchEdit()
        }

        if (selectionUpdated) {
            state.currentOffset = targetOffset
            state.lastCharacterDelta = characterDelta
        } else {
            val fallbackState = state.toKeyEvents()
            spaceCursorDragState = fallbackState
            updateKeyEventSpaceCursorDrag(fallbackState, characterDelta)
        }
    }

    private fun updateKeyEventSpaceCursorDrag(
        state: SpaceCursorDragState.KeyEvents,
        characterDelta: Int,
    ) {
        val stepDelta = characterDelta - state.lastCharacterDelta
        if (stepDelta == 0) return

        sendCursorKeyEvents(stepDelta, state.extendSelection)
        state.lastCharacterDelta = characterDelta
    }

    private fun finishSpaceCursorDrag() {
        spaceCursorDragState = null
    }

    private fun sendCursorKeyEvents(characterDelta: Int, extendSelection: Boolean) {
        val keyCode = if (characterDelta > 0) {
            KeyEvent.KEYCODE_DPAD_RIGHT
        } else {
            KeyEvent.KEYCODE_DPAD_LEFT
        }
        val eventCount = if (characterDelta > 0) characterDelta else -characterDelta
        if (extendSelection) {
            sendShiftedKeys(keyCode, eventCount.coerceAtMost(CURSOR_DRAG_MAX_CHARS), ModifierState(ctrl = false, alt = false))
        } else {
            repeat(eventCount.coerceAtMost(CURSOR_DRAG_MAX_CHARS)) {
                sendKey(keyCode, ModifierState(ctrl = false, alt = false))
            }
        }
    }

    /**
     * TextView extends a selection only after seeing Shift go down (its buffer meta state), not from
     * the arrow event's meta flags alone, so wrap the keys in a Shift press.
     */
    private fun sendShiftedKeys(keyCode: Int, count: Int, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        val shifted = modifiers.copy(shift = true)
        sendModifierEvent(inputConnection, KeyEvent.ACTION_DOWN, shifted.toMetaState())
        repeat(count) { sendKey(keyCode, shifted) }
        sendModifierEvent(inputConnection, KeyEvent.ACTION_UP, modifiers.copy(shift = false).toMetaState())
    }

    private fun sendModifierEvent(inputConnection: InputConnection, action: Int, metaState: Int) {
        val time = SystemClock.uptimeMillis()
        inputConnection.sendKeyEvent(
            KeyEvent(
                time,
                time,
                action,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            ),
        )
    }

    private fun isBackspaceGestureDeleteTargetCurrent(
        state: BackspaceGestureDeleteState,
        selectionStart: Int,
        expectedText: String,
    ): Boolean {
        val snapshot = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1) ?: return false
        val localStart = selectionStart - snapshot.textStartOffset
        val localEnd = state.anchorOffset - snapshot.textStartOffset
        if (localStart < 0 || localEnd > snapshot.text.length || localStart > localEnd) {
            return false
        }
        return snapshot.text.substring(localStart, localEnd) == expectedText
    }

    private fun restoreBackspaceGestureDeleteSelection(
        inputConnection: InputConnection,
        state: BackspaceGestureDeleteState,
    ) {
        val maxOffset = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1)
            ?.let { snapshot -> snapshot.textStartOffset + snapshot.text.length }
            ?: state.originalSelectionEnd
        val selectionStart = state.originalSelectionStart.coerceIn(0, maxOffset)
        val selectionEnd = state.originalSelectionEnd.coerceIn(0, maxOffset)
        inputConnection.beginBatchEdit()
        try {
            inputConnection.setSelection(selectionStart, selectionEnd)
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun previousGestureDeleteWordStarts(
        beforeCursor: String,
        cursorOffset: Int,
    ): List<Int> {
        val starts = mutableListOf<Int>()
        var scanOffset = beforeCursor.length
        while (scanOffset > 0 && starts.size < MAX_GESTURE_DELETE_WORDS) {
            while (scanOffset > 0 && beforeCursor[scanOffset - 1].isWhitespace()) {
                scanOffset--
            }
            if (scanOffset == 0) break

            while (scanOffset > 0 && !beforeCursor[scanOffset - 1].isWhitespace()) {
                scanOffset--
            }

            val selectedCharCount = beforeCursor.length - scanOffset
            val absoluteStart = cursorOffset - selectedCharCount
            if (absoluteStart < 0) break
            starts += absoluteStart
        }
        return starts
    }

    private fun handleEnter(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        val info = currentEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val forceEnter = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (!forceEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            inputConnection.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER, modifiers)
        }
    }

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered) return
        clipboardManager.addPrimaryClipChangedListener(clipboardChangeListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) return
        clipboardManager.removePrimaryClipChangedListener(clipboardChangeListener)
        clipboardListenerRegistered = false
    }

    private fun capturePrimaryClipboardText(inferSourceApp: Boolean = false) {
        val snapshot = readPrimaryPlainClipboardText(inferSourceApp = inferSourceApp) ?: return
        if (snapshot.matchesClip(lastCapturedClipboardSnapshot)) return
        lastCapturedClipboardSnapshot = snapshot

        ClipboardHistoryStore.recordText(
            context = this,
            text = snapshot.text,
            sourcePackageName = snapshot.sourcePackageName,
            sourceAppLabel = snapshot.sourceAppLabel,
        )
        if (activeSurface == KeyboardSurface.Clipboard || activeSurface == KeyboardSurface.ClipboardSearch) {
            refreshClipboardHistoryViews()
        }
    }

    private fun readPrimaryPlainClipboardText(inferSourceApp: Boolean): ClipboardSnapshot? {
        return runCatching {
            val description = clipboardManager.primaryClipDescription ?: return@runCatching null
            if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                return@runCatching null
            }
            val hasTextMimeType = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            if (!hasTextMimeType) return@runCatching null

            val clip = clipboardManager.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            val text = clip.getItemAt(0)
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() && it.length <= ClipboardHistoryStore.MAX_ENTRY_CHARS }
                ?: return@runCatching null
            val sourceApp = if (inferSourceApp) resolveClipboardSourceApp() else null
            ClipboardSnapshot(
                text = text,
                timestampMillis = description.timestamp,
                sourcePackageName = sourceApp?.packageName,
                sourceAppLabel = sourceApp?.label,
            )
        }.getOrNull()
    }

    private fun resolveClipboardSourceApp(): ClipboardSourceApp? {
        val sourcePackageName = currentEditorInfo
            ?.packageName
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: return null
        val appInfo = runCatching {
            packageManager.getApplicationInfo(
                sourcePackageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        }.getOrNull()
        val label = appInfo
            ?.loadLabel(packageManager)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: sourcePackageName
        return ClipboardSourceApp(packageName = sourcePackageName, label = label)
    }

    private fun sendKey(keyCode: Int, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        val downTime = SystemClock.uptimeMillis()
        val metaState = modifiers.toMetaState()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        inputConnection.sendKeyEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
            ),
        )
        inputConnection.sendKeyEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
            ),
        )
    }

    private fun pasteClipboardHistoryText(text: String) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            if (pinyinComposing.isNotEmpty()) {
                // Unpicked pinyin would otherwise stay in the text as letters before the paste.
                noteOwnEdit()
                inputConnection.setComposingText("", 1)
                clearPinyinComposition()
            }
            inputConnection.finishComposingText()
            committed = inputConnection.commitText(text, 1)
        } finally {
            inputConnection.endBatchEdit()
        }

        if (committed) {
            showKeyboardPanel()
        } else {
            Toast.makeText(this, t("粘贴没有成功。", "Couldn't paste that item."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteClipboardHistoryText(text: String) {
        ClipboardHistoryStore.deleteText(this, text)
        refreshClipboardHistoryViews()
    }

    private fun refreshClipboardHistoryViews() {
        val entries = ClipboardHistoryStore.read(this)
        clipboardPanelView?.submitClipboardHistory(entries)
        clipboardSearchView?.setClipboardEntries(entries)
    }

    /** Why voice input can't start in this field right now, or null when it can. */
    private fun voiceInputProblem(): String? {
        val chinese = pinyinModeEnabled
        val gemini = KeyboardSettings.readAgentProviderSettings(this, KeyboardSettings.AgentProviderType.Gemini)
        return when {
            isSensitiveEditor(currentEditorInfo) ->
                if (chinese) "密码框里不能用 AI 语音输入。" else "AI voice input is off in password fields."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                if (chinese) "请先在 设置 → AI 与语音 里开启 AI 语音输入。" else "Turn on AI voice input in Settings → AI & voice."
            !gemini.isConfigured ->
                if (chinese) "请先在设置里填好 Gemini API Key。" else "Add your Gemini API key in Settings first."
            else -> null
        }
    }

    private fun finishVoiceInput(cancelled: Boolean) {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        if (cancelled) {
            recorder.cancel()
            keyboardView?.setVoiceIndicator(null)
            return
        }
        val clip = recorder.stop()
        if (clip.durationMs < AgentDefaults.MIN_VOICE_CLIP_MS) {
            keyboardView?.setVoiceIndicator(null)
            return
        }

        val providerSettings = KeyboardSettings.readAgentProviderSettings(this, KeyboardSettings.AgentProviderType.Gemini)
        val systemPrompt = voiceSystemPrompt()
        val generation = ++voiceGeneration
        voiceProcessing = true
        keyboardView?.setVoiceIndicator(ZnKeyboardView.VoiceIndicator.Processing)
        Log.d(TAG, "Starting voice request for ${clip.durationMs} ms of audio")

        Thread {
            val result = runCatching {
                // Trimming and encoding a long clip takes a moment, so it happens here, not on the UI thread.
                val wav = VoiceRecorder.encodeSpeech(clip.pcm) ?: throw NoSpeechException()
                val userContent = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "Here is my recording."))
                    .put(
                        JSONObject()
                            .put("type", "input_audio")
                            .put(
                                "input_audio",
                                JSONObject()
                                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                                    .put("format", "wav"),
                            ),
                    )
                requestChatCompletion(
                    providerSettings = providerSettings,
                    systemPrompt = systemPrompt,
                    userContent = userContent,
                    maxTokens = AgentDefaults.MAX_VOICE_LLM_TOKENS,
                )
            }
            mainHandler.post {
                if (generation != voiceGeneration) return@post
                voiceProcessing = false
                keyboardView?.setVoiceIndicator(null)
                result
                    .onSuccess { text ->
                        dev.zain.znkeyboard.UsageStats.recordUse(this)
                        commitVoiceText(text)
                    }
                    .onFailure { error ->
                        if (error is NoSpeechException) {
                            showVoiceHint(if (pinyinModeEnabled) "没有听到声音。" else "Didn't hear anything.")
                        } else if (error is EmptyModelTextException) {
                            showVoiceHint(if (pinyinModeEnabled) "没听清，请再说一次。" else "Didn't catch that. Try again.")
                        } else {
                            showAgentError(friendlyError(error))
                        }
                    }
            }
        }.apply {
            name = "ZnKeyboardVoiceRequest"
            start()
        }
    }

    private fun cancelVoiceInput() {
        voiceRecorder?.cancel()
        voiceRecorder = null
        voiceGeneration++
        voiceProcessing = false
        voiceHintRunnable?.let(mainHandler::removeCallbacks)
        voiceHintRunnable = null
        keyboardView?.setVoiceIndicator(null)
    }

    private fun commitVoiceText(text: String) {
        val inputConnection = currentInputConnection ?: return
        // Pinyin typed while waiting is composing text, which commitText would overwrite.
        commitPinyinWholeTop()
        val before = inputConnection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        // Dictated English after a word needs a separating space; CJK text never does.
        val needsSpace = before.isNotEmpty() &&
            before.last().let { it.isLetterOrDigit() && it.code < CJK_START } &&
            text.first().let { it.isLetterOrDigit() && it.code < CJK_START }
        inputConnection.commitText(if (needsSpace) " $text" else text, 1)
    }

    private fun showVoiceHint(message: String) {
        voiceHintRunnable?.let(mainHandler::removeCallbacks)
        keyboardView?.setVoiceIndicator(ZnKeyboardView.VoiceIndicator.Message(message))
        voiceHintRunnable = Runnable {
            voiceHintRunnable = null
            if (voiceRecorder == null && !voiceProcessing) keyboardView?.setVoiceIndicator(null)
        }.also { mainHandler.postDelayed(it, VOICE_HINT_DURATION_MS) }
    }

    /** 中 mode writes down what was said; EN mode also translates it, like 翻译. */
    private fun voiceSystemPrompt(): String {
        return buildList {
            add(loadPromptAsset(VOICE_PROMPT_ASSET, fallback = ""))
            if (!pinyinModeEnabled) {
                val translateTarget = KeyboardSettings.readAgentTranslateTarget(this@ZnKeyboardInputMethodService)
                add(
                    loadPromptAsset(TRANSLATE_PROMPT_ASSET, fallback = "")
                        .replace(AgentDefaults.TRANSLATE_LANGUAGE_PLACEHOLDER, translateTarget.name),
                )
            }
        }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n---\n\n")
    }

    private fun startRewriteFromCurrentEditor() {
        val target = captureRewriteTarget() ?: return
        startLlmRequest(
            target = target,
            requestText = target.originalText,
            selectTargetForRequest = true,
        )
    }

    private fun applyAgentReview() {
        val review = activeReview ?: return
        val inputConnection = currentInputConnection ?: return

        if (!isTargetStillCurrent(review.target)) {
            activeReview = null
            showKeyboardPanel()
            showAgentError(t("文字已经改动，请重新润色。", "The text changed. Run it again."))
            return
        }

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            if (!inputConnection.setSelection(review.target.replaceStart, review.target.replaceEnd)) {
                showAgentError(t("找不到原来的文字，没有替换。", "Couldn't find the original text."))
                return
            }
            committed = inputConnection.commitText(review.replacementText, 1)
        } finally {
            inputConnection.endBatchEdit()
        }

        if (!committed) {
            showAgentError(t("替换没有成功。", "Couldn't apply the result."))
            return
        }

        requestGeneration++
        agentError = null
        agentLoading = false
        activeRequestTarget = null
        activeReview = null
        showKeyboardPanel()
    }

    private fun cancelAgentReview() {
        activeReview?.target?.let(::restoreSelectionIfNeeded)
        activeReview = null
        activeRequestTarget = null
        agentError = null
        agentLoading = false
        showKeyboardPanel()
    }

    private fun copyAgentError() {
        val error = agentError?.takeIf { it.isNotBlank() } ?: return
        val clip = ClipData.newPlainText(getString(R.string.agent_error_clip_label), error).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboardManager.setPrimaryClip(clip)
    }

    /** The selected text when there is a selection (as on Mac and Windows), otherwise the whole field. */
    private fun captureRewriteTarget(): AgentEditTarget? {
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1) ?: run {
            showAgentError(t("读取不到这个输入框的文字。", "Couldn't read this field."))
            return null
        }
        val selectionStart = minOf(snapshot.selectionStart, snapshot.selectionEnd)
        val selectionEnd = maxOf(snapshot.selectionStart, snapshot.selectionEnd)
        val localStart = selectionStart - snapshot.textStartOffset
        val localEnd = selectionEnd - snapshot.textStartOffset
        if (selectionEnd > selectionStart && localStart >= 0 && localEnd <= snapshot.text.length) {
            val selected = snapshot.text.substring(localStart, localEnd)
            if (selected.length > MAX_REWRITE_SOURCE_CHARS) {
                showAgentError(t("文字太长，请分段处理（最多 5000 字）。", "Too long; do it in parts (5,000 characters max)."))
                return null
            }
            if (selected.isNotBlank()) {
                return AgentEditTarget(
                    originalText = selected,
                    replaceStart = selectionStart,
                    replaceEnd = selectionEnd,
                    restoreSelectionStart = selectionStart,
                    restoreSelectionEnd = selectionEnd,
                    // Already selected by the user; nothing to put back.
                    selectionWasChangedForRequest = false,
                )
            }
        }
        if (snapshot.textStartOffset != 0) {
            showAgentError(t("这个输入框的文字太长，请先选中一部分。", "This field is too long; select part of it first."))
            return null
        }
        if (snapshot.text.length > MAX_REWRITE_SOURCE_CHARS) {
            showAgentError(t("文字太长，请分段处理（最多 5000 字）。", "Too long; do it in parts (5,000 characters max)."))
            return null
        }
        if (snapshot.text.isBlank()) {
            showAgentError(t("输入框里还没有文字。", "There's no text yet."))
            return null
        }

        return AgentEditTarget(
            originalText = snapshot.text,
            replaceStart = snapshot.textStartOffset,
            replaceEnd = snapshot.textStartOffset + snapshot.text.length,
            restoreSelectionStart = snapshot.selectionStart,
            restoreSelectionEnd = snapshot.selectionEnd,
            selectionWasChangedForRequest = true,
        )
    }

    private fun captureEditorSnapshot(maxChars: Int): EditorSnapshot? {
        val inputConnection = currentInputConnection ?: return null
        val extracted = inputConnection.getExtractedText(
            ExtractedTextRequest().apply {
                hintMaxChars = maxChars
            },
            0,
        ) ?: return null
        val text = extracted.text?.toString() ?: return null
        val textStartOffset = extracted.startOffset.coerceAtLeast(0)
        return EditorSnapshot(
            text = text,
            textStartOffset = textStartOffset,
            selectionStart = absoluteExtractedOffset(extracted.selectionStart, textStartOffset, text.length),
            selectionEnd = absoluteExtractedOffset(extracted.selectionEnd, textStartOffset, text.length),
        )
    }

    private fun absoluteExtractedOffset(offset: Int, textStartOffset: Int, textLength: Int): Int {
        if (offset < 0) return textStartOffset + textLength
        val textEndOffset = textStartOffset + textLength
        return when (offset) {
            in textStartOffset..textEndOffset -> offset
            else -> textStartOffset + offset.coerceIn(0, textLength)
        }
    }

    private fun selectTargetText(target: AgentEditTarget): Boolean {
        val inputConnection = currentInputConnection ?: return false
        return inputConnection.setSelection(target.replaceStart, target.replaceEnd)
    }

    private fun restoreSelectionIfNeeded(target: AgentEditTarget) {
        if (!target.selectionWasChangedForRequest) return
        val inputConnection = currentInputConnection ?: return
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1)
        val maxOffset = snapshot?.let { it.textStartOffset + it.text.length } ?: target.replaceEnd
        val start = target.restoreSelectionStart.coerceIn(0, maxOffset)
        val end = target.restoreSelectionEnd.coerceIn(0, maxOffset)
        inputConnection.setSelection(start, end)
    }

    private fun isTargetStillCurrent(target: AgentEditTarget): Boolean {
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1) ?: return true
        val localStart = target.replaceStart - snapshot.textStartOffset
        val localEnd = target.replaceEnd - snapshot.textStartOffset
        if (localStart < 0 || localEnd > snapshot.text.length || localStart > localEnd) {
            return false
        }
        return snapshot.text.substring(localStart, localEnd) == target.originalText
    }

    private fun cancelActiveAgentForEditorChange() {
        if (!agentLoading && activeRequestTarget == null && activeReview == null) {
            if (agentError != null) {
                agentError = null
                if (activeSurface == KeyboardSurface.Review) {
                    showKeyboardPanel()
                } else {
                    renderSecondRow()
                }
            }
            return
        }
        // A running request selected the whole field: give the cursor back before the edit lands,
        // or a key or paste would replace (and backspace delete) everything.
        if (agentLoading) activeRequestTarget?.let(::restoreSelectionIfNeeded)
        requestGeneration++
        activeRequestTarget = null
        activeReview = null
        agentError = null
        agentLoading = false
        if (activeSurface == KeyboardSurface.Review) {
            showKeyboardPanel()
        } else {
            renderSecondRow()
        }
    }

    private fun startLlmRequest(
        target: AgentEditTarget,
        requestText: String,
        selectTargetForRequest: Boolean,
    ) {
        if (!isRewriteAvailable()) return
        val providerSettings = KeyboardSettings.readAgentProviderSettings(this)
        if (!providerSettings.isConfigured) {
            showAgentError(t("请先在设置里填好 API Key。", "Add your API key in Settings first."))
            return
        }
        if (selectTargetForRequest && !selectTargetText(target)) {
            showAgentError(t("选不中这个输入框的文字。", "Couldn't select the text in this field."))
            return
        }

        val generation = ++requestGeneration
        agentError = null
        agentLoading = true
        activeRequestTarget = target
        Log.d(TAG, "Starting rewrite request for ${requestText.length} chars")
        renderSecondRow()
        val systemPrompt = rewriteSystemPrompt()

        Thread {
            val result = runCatching {
                requestChatCompletion(
                    providerSettings = providerSettings,
                    systemPrompt = systemPrompt,
                    userContent = requestText,
                    maxTokens = maxTokensFor(requestText),
                )
            }

            mainHandler.post {
                if (generation != requestGeneration || !isRewriteAvailable()) return@post
                agentLoading = false
                activeRequestTarget = null
                result
                    .onSuccess { content ->
                        applyLlmResult(target, content)
                    }
                    .onFailure { error ->
                        restoreSelectionIfNeeded(target)
                        showAgentError(friendlyError(error))
                    }
                renderSecondRow()
            }
        }.apply {
            name = "ZnKeyboardAgentRequest"
            start()
        }
    }

    /** [userContent] is the user message: plain text, or a JSONArray of content parts (text, audio). */
    private fun requestChatCompletion(
        providerSettings: KeyboardSettings.AgentProviderSettings,
        systemPrompt: String,
        userContent: Any,
        maxTokens: Int,
    ): String {
        val endpoint = buildChatCompletionsUrl(providerSettings)
        var structuredOutputError: IOException? = null
        val outputModes = listOf(StructuredOutputMode.StandardJsonSchema, StructuredOutputMode.JsonObject)

        for (outputMode in outputModes) {
            try {
                return withBusyRetries {
                    executeChatCompletionRequest(
                        endpoint = endpoint,
                        providerSettings = providerSettings,
                        systemPrompt = systemPrompt,
                        userContent = userContent,
                        maxTokens = maxTokens,
                        outputMode = outputMode,
                    )
                }
            } catch (error: LlmHttpException) {
                if (
                    outputMode != StructuredOutputMode.JsonObject &&
                    error.isStructuredOutputUnsupported()
                ) {
                    structuredOutputError = error
                    Log.w(TAG, "Structured output mode $outputMode unsupported; trying compatibility fallback.")
                    continue
                }
                throw error
            }
        }

        throw structuredOutputError ?: IOException("LLM request failed before returning structured output.")
    }

    /**
     * Runs [request] (on the request's background thread), trying again after a short wait when the
     * provider answers busy (429, 5xx; DeepSeek's 503 "Service is too busy" at peak times): those pass
     * within seconds, so the user rarely sees them. Anything else fails at once.
     */
    private inline fun <T> withBusyRetries(request: () -> T): T {
        for (delayMs in AgentDefaults.BUSY_RETRY_DELAYS_MS) {
            try {
                return request()
            } catch (error: LlmHttpException) {
                if (!error.isBusy()) throw error
                Log.w(TAG, "Provider busy (HTTP ${error.responseCode}); trying again in $delayMs ms.")
                Thread.sleep(delayMs)
            }
        }
        return request()
    }

    private fun executeChatCompletionRequest(
        endpoint: URL,
        providerSettings: KeyboardSettings.AgentProviderSettings,
        systemPrompt: String,
        userContent: Any,
        maxTokens: Int,
        outputMode: StructuredOutputMode,
    ): String {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // A whole answer in one piece: a long translation or a 90-second recording takes a while.
            readTimeout = REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${providerSettings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject()
            .put("model", providerSettings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )
            .put("temperature", 0.4)
            .put("max_tokens", maxTokens)
            .put("response_format", structuredResponseFormatFor(outputMode))
            .applyReasoningSettings(providerSettings)

        return try {
            connection.outputStream.writer(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw LlmHttpException(
                    responseCode = responseCode,
                    responseText = responseText,
                    detailMessage = "LLM request failed ($responseCode): ${responseText.take(ERROR_PREVIEW_CHARS)}",
                )
            }

            extractChatCompletionContent(
                responseJson = JSONObject(responseText),
                includeReasoningText = providerSettings.reasoningTextEnabled,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractChatCompletionContent(
        responseJson: JSONObject,
        includeReasoningText: Boolean,
    ): String {
        val choice = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)

        // A cut-off answer is half a JSON object; say why instead of reporting a parse error.
        if (choice.optString("finish_reason") == "length") throw TooLongException()

        val message = choice.optJSONObject("message")
            ?: throw IOException("LLM response did not include a message.")

        val refusal = message.optNullableString("refusal")
        if (!refusal.isNullOrBlank()) {
            throw RefusedException()
        }

        val structuredPayload = parseStructuredOutput(message.opt("content"), includeReasoningText)
        val text = structuredPayload
            .optString(STRUCTURED_OUTPUT_TEXT_FIELD)
            .let(AsciiTextSanitizer::clean)
            .trim()

        if (text.isBlank() || text.equals("null", ignoreCase = true)) {
            val finishReason = choice.optString("finish_reason", "unknown")
            throw EmptyModelTextException("No text returned by the model. Finish reason: $finishReason.")
        }

        return text
    }

    private fun parseStructuredOutput(content: Any?, includeReasoningText: Boolean): JSONObject {
        return when (content) {
            null, JSONObject.NULL -> throw IOException("LLM response did not include structured content.")
            is JSONObject -> content
            is String -> parseStructuredOutputJson(content, includeReasoningText)
            is JSONArray -> parseStructuredOutputJson(extractTextFromContentArray(content), includeReasoningText)
            else -> throw IOException("LLM response content was not a structured JSON object.")
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return value.toString()
            .trim()
            .takeUnless { it.equals("null", ignoreCase = true) }
    }

    private fun parseStructuredOutputJson(rawContent: String, includeReasoningText: Boolean): JSONObject {
        val jsonText = rawContent
            .stripReasoningBlocks(includeReasoningText)
            .trim()
        if (jsonText.isBlank()) {
            throw IOException("LLM response did not include structured JSON.")
        }

        try {
            return JSONObject(jsonText)
        } catch (error: JSONException) {
            throw IOException("LLM response was not valid structured JSON.", error)
        }
    }

    private fun String.stripReasoningBlocks(includeReasoningText: Boolean): String {
        if (includeReasoningText) return this

        return replace(THINK_BLOCK_REGEX, "")
            .replace(REASONING_BLOCK_REGEX, "")
    }

    private fun extractTextFromContentArray(content: JSONArray): String {
        return buildString {
            for (index in 0 until content.length()) {
                when (val part = content.opt(index)) {
                    is String -> append(part)
                    is JSONObject -> append(part.optString("text"))
                }
            }
        }
    }

    /**
     * Room for the answer: a CJK character can cost a whole token while Latin text packs several
     * characters per token, so CJK counts in full. Translation can lengthen text, hence the margin.
     */
    private fun maxTokensFor(text: String): Int {
        val cjk = text.count { it.code >= CJK_START }
        val estimatedOutputTokens = cjk + (text.length - cjk) / CHARS_PER_OUTPUT_TOKEN_ESTIMATE
        return (estimatedOutputTokens * 3 / 2 + REWRITE_TOKEN_MARGIN)
            .coerceIn(MIN_REWRITE_LLM_TOKENS, MAX_REWRITE_LLM_TOKENS)
    }

    private fun structuredResponseFormatFor(outputMode: StructuredOutputMode): JSONObject {
        if (outputMode == StructuredOutputMode.JsonObject) {
            return JSONObject().put("type", "json_object")
        }

        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        STRUCTURED_OUTPUT_TEXT_FIELD,
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "The final rewritten text only, without labels, explanations, or prefaces.",
                            ),
                    ),
            )
            .put("required", JSONArray().put(STRUCTURED_OUTPUT_TEXT_FIELD))

        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "znkeyboard_rewrite_response")
                    .put("strict", true)
                    .put("schema", schema),
            )
    }

    private fun JSONObject.applyReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        if (providerSettings.reasoningMode == KeyboardSettings.AgentReasoningMode.Off) return this
        return applyOpenAiCompatibleReasoningSettings(providerSettings)
    }

    private fun JSONObject.applyOpenAiCompatibleReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        providerSettings.reasoningMode.effort?.let { effort ->
            put("reasoning_effort", effort)
        }
        if (providerSettings.reasoningTextEnabled) {
            put("include_reasoning", true)
            put(
                "reasoning",
                JSONObject().put("exclude", false),
            )
        }
        return this
    }

    private fun buildChatCompletionsUrl(providerSettings: KeyboardSettings.AgentProviderSettings): URL {
        val normalizedBaseUrl = providerSettings.baseUrl.trim().trimEnd('/')
        val endpoint = URL("$normalizedBaseUrl/chat/completions")
        if (endpoint.protocol != "https") {
            throw IOException("Use an HTTPS OpenAI-compatible endpoint.")
        }
        return endpoint
    }

    private fun rewriteSystemPrompt(): String {
        return buildList {
            add(loadPromptAsset(REWRITE_BASE_PROMPT_ASSET, FALLBACK_REWRITE_PROMPT))
            appPromptAssetFor(currentEditorInfo?.packageName)?.let { assetPath ->
                add(loadPromptAsset(assetPath, fallback = ""))
            }
            // 翻译 translates in either input mode; 润色 just polishes.
            // Last, so it overrides the language-matching rules above.
            if (rewriteTranslates) {
                val translateTarget = KeyboardSettings.readAgentTranslateTarget(this@ZnKeyboardInputMethodService)
                add(
                    loadPromptAsset(TRANSLATE_PROMPT_ASSET, fallback = "")
                        .replace(AgentDefaults.TRANSLATE_LANGUAGE_PLACEHOLDER, translateTarget.name),
                )
            }
        }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n---\n\n")
    }

    private fun appPromptAssetFor(packageName: String?): String? {
        val normalizedPackageName = packageName?.lowercase(Locale.US) ?: return null
        return when {
            normalizedPackageName == WHATSAPP_PACKAGE_NAME ||
                normalizedPackageName == WHATSAPP_BUSINESS_PACKAGE_NAME -> WHATSAPP_PROMPT_ASSET
            else -> null
        }
    }

    private fun loadPromptAsset(path: String, fallback: String): String {
        return runCatching {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        }.getOrElse {
            fallback
        }.trim()
    }

    private fun applyLlmResult(target: AgentEditTarget, content: String) {
        val normalized = AsciiTextSanitizer.clean(content).trim()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            showAgentError(t("AI 没有返回结果，请再试一次。", "The AI returned nothing. Try again."))
            return
        }

        if (normalized == target.originalText.trim()) {
            restoreSelectionIfNeeded(target)
            showAgentError(t("没有需要修改的地方。", "Nothing to change."))
            return
        }

        dev.zain.znkeyboard.UsageStats.recordUse(this)
        // The model drops surrounding spaces and line breaks; keep the original's so the text fits back in.
        val leading = target.originalText.takeWhile { it.isWhitespace() }
        val trailing = target.originalText.takeLastWhile { it.isWhitespace() }
        agentError = null
        activeReview = AgentReview(
            target = target,
            replacementText = leading + normalized + trailing,
        )
        activeReview?.let(::showAgentReviewPanel)
    }

    /** Keyboard text follows the 中 / EN key, like the voice hints. */
    private fun t(chinese: String, english: String): String = if (pinyinModeEnabled) chinese else english

    /** What went wrong, in words, with the HTTP status for reference; never the server's raw reply. */
    private fun friendlyError(error: Throwable): String = when (error) {
        is TooLongException -> t("文字太长，请分段处理。", "Too long; do it in parts.")
        is EmptyModelTextException -> t("AI 没有返回结果，请再试一次。", "The AI returned nothing. Try again.")
        is LlmHttpException -> when (error.responseCode) {
            400 -> t("请求被拒绝（HTTP 400），请检查模型名称。", "The request was rejected (HTTP 400). Check the model name.")
            401, 403 -> t("API Key 无效或没有权限（HTTP ${error.responseCode}），请在设置里检查。", "The API key is invalid or not allowed (HTTP ${error.responseCode}). Check it in Settings.")
            404 -> t("找不到这个模型或接口地址（HTTP 404），请在设置里检查。", "Model or API address not found (HTTP 404). Check Settings.")
            402 -> t("额度已用完（HTTP 402），请检查账户余额。", "Out of credit (HTTP 402). Check your account.")
            // Already tried three times (withBusyRetries).
            429, in 500..599 -> t(
                "AI 服务商暂时繁忙（HTTP ${error.responseCode}），自动重试后仍未成功。请稍后再试，或在设置里换用其他服务商。",
                "The AI provider is busy (HTTP ${error.responseCode}) and still didn't answer after a few tries. Try again shortly, or switch providers in Settings.",
            )
            else -> t("AI 请求失败（HTTP ${error.responseCode}）。", "The AI request failed (HTTP ${error.responseCode}).")
        }
        is java.net.SocketTimeoutException -> t("AI 服务响应超时，请稍后再试。", "The AI service took too long. Try again.")
        is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException ->
            t("无法连接 AI 服务，请检查网络。", "Couldn't reach the AI service. Check your connection.")
        is RefusedException -> t("AI 拒绝处理这段文字。", "The AI declined this text.")
        else -> t("AI 请求失败，请稍后再试。", "The AI request failed. Try again later.")
    }

    private fun showAgentError(message: String) {
        agentLoading = false
        agentError = message
        Log.w(TAG, message)
        showAgentErrorPanel(message)
    }

    private fun resetAgentState(returnToKeyboard: Boolean = false) {
        requestGeneration++
        activeRequestTarget = null
        activeReview = null
        agentError = null
        agentLoading = false
        if (returnToKeyboard) {
            showKeyboardPanel()
        } else {
            renderSecondRow()
        }
    }

    private fun renderSecondRow() {
        val visible = activeSurface == KeyboardSurface.Keyboard
        val providerConfigured = KeyboardSettings.readAgentProviderSettings(this).isConfigured

        keyboardView?.renderShortcutRows(
            ZnKeyboardView.ShortcutRowState(
                // Numbers and phone numbers have nothing to polish or translate, nor use for @ & % ?.
                secondRowVisible = visible && !shouldUsePhonePadMode(currentEditorInfo),
                loading = agentLoading,
                rewriteEnabled = visible && isRewriteAvailable() && providerConfigured,
                // A password field keeps the clipboard (pasting is common) but never offers AI.
                aiVisible = !isSensitiveEditor(currentEditorInfo),
            ),
        )
    }

    private fun isRewriteAvailable(): Boolean {
        return KeyboardSettings.readAgentModeEnabled(this) && !isSensitiveEditor(currentEditorInfo)
    }

    private fun isRawKeyEventEditor(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
    }

    private fun shouldUsePhonePadMode(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE -> true
            else -> false
        }
    }

    /** Never learn from passwords or from fields that ask IMEs not to (e.g. incognito browsing). */
    private fun isPersonalizationAllowed(info: EditorInfo?): Boolean {
        if (isSensitiveEditor(info)) return false
        val imeOptions = info?.imeOptions ?: return true
        return imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun resolveEnterLabel(info: EditorInfo?): String {
        val imeOptions = info?.imeOptions ?: return "Enter"
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "Enter"

        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            // Enter and Search are icons; the rest are words, in the 中 / EN mode's language.
            EditorInfo.IME_ACTION_DONE -> t("完成", "Done")
            EditorInfo.IME_ACTION_GO -> t("前往", "Go")
            EditorInfo.IME_ACTION_NEXT -> t("下一项", "Next")
            EditorInfo.IME_ACTION_PREVIOUS -> t("上一项", "Prev")
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> t("发送", "Send")
            else -> "Enter"
        }
    }

    private fun keyCodeFor(char: Char): Int? {
        return when (char.lowercaseChar()) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char.lowercaseChar() - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\t' -> KeyEvent.KEYCODE_TAB
            '\n' -> KeyEvent.KEYCODE_ENTER
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            else -> null
        }
    }

    private fun ModifierState.toMetaState(): Int {
        var state = 0
        if (ctrl) state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return state
    }

    private val ModifierState.hasHardwareMeta: Boolean
        get() = ctrl || alt

    private data class EditorSnapshot(
        val text: String,
        val textStartOffset: Int,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    private data class AgentEditTarget(
        val originalText: String,
        val replaceStart: Int,
        val replaceEnd: Int,
        val restoreSelectionStart: Int,
        val restoreSelectionEnd: Int,
        val selectionWasChangedForRequest: Boolean,
    )

    private data class AgentReview(
        val target: AgentEditTarget,
        val replacementText: String,
    )

    private data class BackspaceGestureDeleteState(
        val anchorOffset: Int,
        val originalSelectionStart: Int,
        val originalSelectionEnd: Int,
        val wordSelectionStarts: List<Int>,
        val wordSelectionTexts: List<String>,
        var currentWordCount: Int = 0,
    )

    private sealed class SpaceCursorDragState {
        data class Selection(
            val anchorOffset: Int,
            val minOffset: Int,
            val maxOffset: Int,
            var currentOffset: Int,
            var lastCharacterDelta: Int = 0,
            /** Fixed end of the selection when Shift is held; null moves a plain cursor. */
            val selectionAnchor: Int? = null,
        ) : SpaceCursorDragState() {
            fun toKeyEvents() = KeyEvents(lastCharacterDelta, extendSelection = selectionAnchor != null)
        }

        data class KeyEvents(
            var lastCharacterDelta: Int = 0,
            val extendSelection: Boolean = false,
        ) : SpaceCursorDragState()
    }

    private data class ClipboardSnapshot(
        val text: String,
        val timestampMillis: Long,
        val sourcePackageName: String?,
        val sourceAppLabel: String?,
    ) {
        fun matchesClip(other: ClipboardSnapshot?): Boolean {
            return other != null &&
                text == other.text &&
                timestampMillis == other.timestampMillis
        }
    }

    private data class ClipboardSourceApp(
        val packageName: String,
        val label: String,
    )

    private data class SelectionWrapPair(
        val open: String,
        val close: String,
    )

    private enum class KeyboardSurface {
        Keyboard,
        Clipboard,
        ClipboardSearch,
        Review,
    }

    private class EmptyModelTextException(message: String) : IOException(message)

    /** The answer was cut off at the token limit. */
    private class TooLongException : IOException("Text is too long to rewrite.")

    private class RefusedException : IOException("The model refused the request.")

    /** The clip held nothing loud enough to be speech, so it was never sent. */
    private class NoSpeechException : IOException("No speech in the recording.")

    private class LlmHttpException(
        val responseCode: Int,
        val responseText: String,
        detailMessage: String,
    ) : IOException(detailMessage)

    /** Too many requests, or the server failing or overloaded: worth trying again. */
    private fun LlmHttpException.isBusy(): Boolean = responseCode == 429 || responseCode in 500..599

    private fun LlmHttpException.isStructuredOutputUnsupported(): Boolean {
        if (responseCode !in setOf(400, 404, 422)) return false

        val normalized = responseText.lowercase(Locale.US)
        return normalized.contains("no endpoints found") ||
            normalized.contains("failed to initialize samplers") ||
            normalized.contains("response_format") ||
            normalized.contains("json_schema") ||
            normalized.contains("structured output") ||
            normalized.contains("schema")
    }

    private enum class StructuredOutputMode {
        StandardJsonSchema,
        JsonObject,
    }

    private companion object {
        const val TAG = "ZnKeyboardAgent"
        const val MAX_REWRITE_SOURCE_CHARS = AgentDefaults.MAX_REWRITE_SOURCE_CHARS
        const val GESTURE_DELETE_CONTEXT_CHARS = 4_096
        const val MAX_GESTURE_DELETE_WORDS = 80
        const val CURSOR_DRAG_CONTEXT_CHARS = 8_192
        const val CURSOR_DRAG_MAX_CHARS = 120
        const val TYPING_CONTEXT_CHARS = 16
        const val PINYIN_DICTIONARY_ASSET = "pinyin/dict.tsv"
        const val PINYIN_LANGUAGE_MODEL_ASSET = "pinyin/lm.bin"
        const val MAX_PINYIN_PREDICTIONS = 16
        val CHINESE_PUNCTUATION = mapOf(
            ',' to "，",
            '.' to "。",
            '?' to "？",
            '!' to "！",
            ':' to "：",
            ';' to "；",
            '(' to "（",
            ')' to "）",
        )
        const val OWN_EDIT_SETTLE_MS = 1_000L
        const val DOUBLE_SPACE_PERIOD_WINDOW_MS = 700L
        const val REQUEST_TIMEOUT_MS = AgentDefaults.REQUEST_TIMEOUT_MS
        const val STROKE_ASSET = "stroke/strokes.tsv"
        const val NINE_KEY_SPELLING_SLOTS = 3
        const val CONNECT_TIMEOUT_MS = AgentDefaults.CONNECT_TIMEOUT_MS
        const val MIN_REWRITE_LLM_TOKENS = AgentDefaults.MIN_REWRITE_LLM_TOKENS
        const val MAX_REWRITE_LLM_TOKENS = AgentDefaults.MAX_REWRITE_LLM_TOKENS
        const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = AgentDefaults.CHARS_PER_OUTPUT_TOKEN_ESTIMATE
        const val REWRITE_TOKEN_MARGIN = AgentDefaults.REWRITE_TOKEN_MARGIN
        const val ERROR_PREVIEW_CHARS = AgentDefaults.ERROR_PREVIEW_CHARS
        const val STRUCTURED_OUTPUT_TEXT_FIELD = AgentDefaults.STRUCTURED_OUTPUT_TEXT_FIELD
        const val REWRITE_BASE_PROMPT_ASSET = AgentDefaults.REWRITE_BASE_PROMPT_ASSET
        const val WHATSAPP_PROMPT_ASSET = AgentDefaults.WHATSAPP_PROMPT_ASSET
        const val TRANSLATE_PROMPT_ASSET = AgentDefaults.TRANSLATE_PROMPT_ASSET
        const val VOICE_PROMPT_ASSET = AgentDefaults.VOICE_PROMPT_ASSET
        const val VOICE_HINT_DURATION_MS = 2_500L
        // CJK scripts start at U+2E80; text from there on is written without word spaces.
        const val CJK_START = 0x2E80
        const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE_NAME = "com.whatsapp.w4b"
        val SELECTION_WRAP_SYMBOLS = mapOf(
            "(" to SelectionWrapPair("(", ")"),
            ")" to SelectionWrapPair("(", ")"),
            "[" to SelectionWrapPair("[", "]"),
            "]" to SelectionWrapPair("[", "]"),
            "{" to SelectionWrapPair("{", "}"),
            "}" to SelectionWrapPair("{", "}"),
            "<" to SelectionWrapPair("<", ">"),
            ">" to SelectionWrapPair("<", ">"),
            "\"" to SelectionWrapPair("\"", "\""),
            "'" to SelectionWrapPair("'", "'"),
            "`" to SelectionWrapPair("`", "`"),
        )
        val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        val REASONING_BLOCK_REGEX = Regex("(?is)<reasoning>.*?</reasoning>")
        val FALLBACK_REWRITE_PROMPT = """
            Improve or rewrite the user's text while preserving the original meaning and tone.
            Return a valid JSON object with exactly one field, text, containing only the rewritten text. Do not include labels, explanations, or prefaces in text.
        """.trimIndent()
    }
}
