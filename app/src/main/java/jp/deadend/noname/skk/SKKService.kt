package jp.deadend.noname.skk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat

import jp.deadend.noname.skk.engine.*
import java.io.*

class SKKService : InputMethodService() {
    private var mCandidateViewContainer: CandidateViewContainer? = null
    private var mCandidateView: CandidateView? = null
    private var mFlickJPInputView: FlickJPKeyboardView? = null
    private var mQwertyInputView: QwertyKeyboardView? = null
    private var mAbbrevKeyboardView: AbbrevKeyboardView? = null
    // 現在表示中の KeyboardView
    private var mInputView: KeyboardView? = mFlickJPInputView
    internal val isFlickJP: Boolean
        get() = (mInputView != mQwertyInputView && mInputView != mAbbrevKeyboardView)
    internal val isTemporaryView: Boolean
        get() = (mInputView == mAbbrevKeyboardView || mEngine.state === SKKZenkakuState)
    internal var leftOffset = 0

    private val mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    private var mIsRecording = false
    private lateinit var mAudioManager: AudioManager
    private var mStreamVolume = 0

    private lateinit var mEngine: SKKEngine
    internal val engineState: SKKState
        get() = mEngine.state
    internal var isHiragana: Boolean
        get() = mFlickJPInputView?.isHiragana ?: true
        set(value) {
            if (value) {
                mFlickJPInputView?.setHiraganaMode()
                mQwertyInputView?.setKeyState(SKKHiraganaState)
            }
            else {
                mFlickJPInputView?.setKatakanaMode()
                mQwertyInputView?.setKeyState(SKKKatakanaState)
            }
        }

    // onKeyDown()でEnterキーのイベントを消費したかどうかのフラグ．onKeyUp()で判定するのに使う
    private var isEnterUsed = false

    private val mShiftKey = SKKStickyShift(this)
    private var mStickyShift = false
    private var mSandS = false
    private var mSpacePressed = false
    private var mSandSUsed = false

    private var mUseSoftKeyboard = false

    private val mHandler = Handler(Looper.getMainLooper())

    private var mPendingInput: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(KEY_COMMAND)) {
            COMMAND_COMMIT_USERDIC -> {
                dlog("commit user dictionary!")
                mEngine.commitUserDictChanges()
            }
            COMMAND_READ_PREFS -> {
                requestHideSelf(0)
                setInputView(onCreateInputView())
                onCreateCandidatesView()
                readPrefs()
            }
            COMMAND_RELOAD_DICS -> mEngine.reopenDictionaries(openDictionaries())
            COMMAND_MUSHROOM -> {
                mPendingInput = intent.getStringExtra(SKKMushroom.REPLACE_KEY)
//                if (mMushroomWord != null) {
//                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
//                    cm.setText(mMushroomWord)
//                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    internal fun extractDictionary(): Boolean {
        try {
            dlog("dic extract start")
            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.message_extracting_dic), Toast.LENGTH_SHORT
                ).show()
            }

            unzipFile(resources.assets.open(DICT_ASCII_ZIP_FILE), filesDir)

            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.message_dic_extracted), Toast.LENGTH_SHORT
                ).show()
            }
            return true
        } catch (e: IOException) {
            Log.e("SKK", "I/O error in extracting dictionary files: $e")
            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.error_extracting_dic_failed), Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
    }

    private fun openDictionaries(): List<SKKDictionary> {
        val result = mutableListOf<SKKDictionary>()
        val dd = filesDir.absolutePath
        dlog("dict dir: $dd")

        val prefVal = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(getString(R.string.prefkey_optional_dics), "")
        if (!prefVal.isNullOrEmpty()) {
            val vals = prefVal.split("/").dropLastWhile { it.isEmpty() }
            for (i in 1 until vals.size step 2) {
                SKKDictionary.newInstance(
                    dd + "/" + vals[i], getString(R.string.btree_name)
                )?.let { result.add(it) }
            }
        }

        return result
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Thread.setDefaultUncaughtExceptionHandler(MyUncaughtExceptionHandler(applicationContext))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val dics = openDictionaries()
        if (dics.isEmpty()) {
            val dicManagerIntent = Intent(this, SKKDicManager::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(dicManagerIntent)
        }

        val userDic = SKKUserDictionary.newInstance(
            this,
            filesDir.absolutePath + "/" + getString(R.string.dic_name_user),
            getString(R.string.btree_name),
            isASCII = false
        )
        if (userDic == null) {
            mHandler.post {
                Toast.makeText(
                    applicationContext, getString(R.string.error_user_dic), Toast.LENGTH_LONG
                ).show()
            }
            stopSelf()
        }
        val asciiDic = SKKUserDictionary.newInstance(
            this,
            filesDir.absolutePath + "/" + getString(R.string.dic_name_ascii),
            getString(R.string.btree_name),
            isASCII = true
        )
        if (asciiDic == null) {
            mHandler.post {
                Toast.makeText(
                    applicationContext, getString(R.string.error_user_dic), Toast.LENGTH_LONG
                ).show()
            }
            stopSelf()
        }

        mEngine = SKKEngine(this@SKKService, dics, userDic!!, asciiDic!!)

        mSpeechRecognizer.setRecognitionListener(object : RecognitionListener {
            private fun restoreState() {
                mFlickJPInputView?.let {
                    it.isEnabled = true
                    it.keyboard.keys[2].on = false // 「声」キー
                    it.invalidateKey(2)
                }
                mIsRecording = false
                mHandler.postDelayed({
                    mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mStreamVolume, 0)
                }, 500)
            }

            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restoreState() }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onResults(results: Bundle?) {
                results?.let {
                    it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { matches ->
                        if (matches.size == 1) {
                            commitTextSKK(matches[0], 1)
                        } else {
                            mFlickJPInputView?.speechRecognitionResultsList(matches)
                        }
                    }
                }
                restoreState()
            }
        })
        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mOrientation = resources.configuration.orientation
        mScreenWidth = resources.displayMetrics.widthPixels
        mScreenHeight = resources.displayMetrics.heightPixels

        readPrefs()
    }

    private fun readPrefs() {
        val context = applicationContext
        mStickyShift = skkPrefs.useStickyMeta
        mSandS = skkPrefs.useSandS
        mEngine.setZenkakuPunctuationMarks(skkPrefs.kutoutenType)

        mUseSoftKeyboard = checkUseSoftKeyboard()
        updateInputViewShown()

        if (mFlickJPInputView != null) readPrefsForInputView()
        val container = mCandidateViewContainer
        container?.let {
            val sp = skkPrefs.candidatesSize
            val px = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics
            ).toInt()
            container.setSize(px)
        }
    }

    private fun readPrefsForInputView() {
        val flick = mFlickJPInputView
        val qwerty = mQwertyInputView?.setKeyState(engineState)
        val abbrev = mAbbrevKeyboardView?.setKeyState()
        if (flick == null || qwerty == null || abbrev == null) return

        val context = when (skkPrefs.theme) {
            "light" -> createNightModeContext(applicationContext, false)
            "dark"  -> createNightModeContext(applicationContext, true)
            else    -> applicationContext
        }
        val config = resources.configuration
        val keyHeight: Int
        val keyWidth: Int
        when (config.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                keyHeight = skkPrefs.keyHeightPort
                keyWidth = skkPrefs.keyWidthPort
            }
            Configuration.ORIENTATION_LANDSCAPE -> {
                keyHeight = skkPrefs.keyHeightLand
                keyWidth = skkPrefs.keyWidthLand
            }
            else -> {
                keyHeight = 30
                keyWidth = 100
            }
        }
        val alpha = skkPrefs.backgroundAlpha
        flick.prepareNewKeyboard(context, keyWidth, keyHeight)
        flick.backgroundAlpha = 255 * alpha / 100

        val qwertyWidth = (keyWidth * skkPrefs.keyWidthQwertyZoom / 100).coerceAtMost(100)
        qwerty.keyboard.resizeByPercentageOfScreen(qwertyWidth, keyHeight)
        qwerty.mSymbolsKeyboard.resizeByPercentageOfScreen(qwertyWidth, keyHeight)
        abbrev.keyboard.resizeByPercentageOfScreen(qwertyWidth, keyHeight)

        val density = context.resources.displayMetrics.density
        val sensitivity = when (skkPrefs.flickSensitivity) {
            "low"  -> (36 * density + 0.5f).toInt()
            "high" -> (12 * density + 0.5f).toInt()
            else   -> (24 * density + 0.5f).toInt()
        }
        qwerty.setFlickSensitivity(sensitivity)
        qwerty.backgroundAlpha = 255 * alpha / 100
        abbrev.setFlickSensitivity(sensitivity)
        abbrev.backgroundAlpha = 255 * alpha / 100
    }

    private fun checkUseSoftKeyboard(): Boolean {
        var result = true
        when (skkPrefs.useSoftKey) {
            "on" -> {
                dlog("software keyboard forced")
                result = true
            }
            "off" -> {
                dlog("software keyboard disabled")
                result = false
            }
            else -> {
                val config = resources.configuration
                if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
                    result = false
                } else if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
                    result = true
                }
            }
        }

        if (result) hideStatusIcon()

        return result
    }

    override fun onBindInput() {
        super.onBindInput()

        if (!mPendingInput.isNullOrEmpty()) {
            keyDownUp(KeyEvent.KEYCODE_DPAD_CENTER)
            // この時点ではまだ入力できないので onStartInput まで mPendingInput を保持
        }
    }

    /**
     * This is the point where you can do all of your UI initialization.  It
     * is called after creation and any configuration change.
     */
    override fun onInitializeInterface() {
        mUseSoftKeyboard = checkUseSoftKeyboard()
        updateInputViewShown()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return mUseSoftKeyboard
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        mFlickJPInputView = null
        mQwertyInputView = null
        mAbbrevKeyboardView = null
        mCandidateViewContainer?.removeAllViews()
        mCandidateViewContainer = null
        mCandidateView = null
        super.onConfigurationChanged(newConfig)
    }

    private fun createNightModeContext(context: Context, isNightMode: Boolean): Context {
        val uiModeFlag = if (isNightMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(context.resources.configuration)
        config.uiMode = uiModeFlag or (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        return context.createConfigurationContext(config)
    }

    private fun createInputView() {
        val context = when (skkPrefs.theme) {
            "light" -> createNightModeContext(applicationContext, false)
            "dark"  -> createNightModeContext(applicationContext, true)
            else    -> applicationContext
        }

        mFlickJPInputView = FlickJPKeyboardView(context, null)
        mFlickJPInputView?.setService(this)
        mQwertyInputView = QwertyKeyboardView(context, null)
        mQwertyInputView?.setService(this)
        mAbbrevKeyboardView = AbbrevKeyboardView(context, null)
        mAbbrevKeyboardView?.setService(this)

        if (skkPrefs.useInset) {
            ResourcesCompat.getDrawable(context.resources, R.drawable.key_bg_inset, null)?.let {
                mFlickJPInputView?.setKeyBackground(it)
                mQwertyInputView?.setKeyBackground(it)
                mAbbrevKeyboardView?.setKeyBackground(it)
            }
        }

        readPrefsForInputView()
    }

    override fun onCreateInputView(): View? {
        createInputView()

        return when (mEngine.state) {
            SKKASCIIState -> mQwertyInputView?.setKeyState(SKKASCIIState)
            SKKKatakanaState -> {
                mFlickJPInputView?.setKatakanaMode()
                if (skkPrefs.preferFlick) mFlickJPInputView else mQwertyInputView
            }
            else -> if (skkPrefs.preferFlick) mFlickJPInputView else mQwertyInputView
        }
    }

    /**
     * This is the main point where we do our initialization of the
     * input method to begin operating on an application. At this
     * point we have been bound to the client, and are now receiving
     * all of the detailed information about the target of our edits.
     */
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!mPendingInput.isNullOrEmpty() && attribute.inputType != InputType.TYPE_NULL) {
            currentInputConnection.commitText(mPendingInput!!, 1)
            mPendingInput = null
        }

        if (mStickyShift) mShiftKey.clearState()
        if (mSandS) {
            mSpacePressed = false
            mSandSUsed = false
        }

        // ここで作成しておいて、表示するかどうかは onCreateCandidatesView 内で判定する
        if (mFlickJPInputView == null) {
            setCandidatesViewShown(true)
        }

        // restarting なら composingText だけ再描画して再利用できるので reset しない
        if (!restarting) {
            mEngine.resetOnStartInput()
        }
        if (attribute.inputType == InputType.TYPE_NULL) {
            requestHideSelf(0)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mEngine.isPersonalizedLearning =
                (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        }
        val keyboardType = when (attribute.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> skkPrefs.typeNumber
            InputType.TYPE_CLASS_PHONE -> skkPrefs.typePhone
            InputType.TYPE_CLASS_TEXT -> {
                val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
                when (variation) {
                    InputType.TYPE_TEXT_VARIATION_URI-> skkPrefs.typeURI
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> skkPrefs.typePassword
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "qwerty"
                    else -> skkPrefs.typeText
                }
            }
            // InputType.TYPE_CLASS_DATETIME -> "ignore" // ウェブブラウザが使ってないタイプなので無視
            else -> "ignore"
        }
        when (keyboardType) {
            "flick-jp" -> {
                if (mEngine.state === SKKASCIIState) handleKanaKey()
                if (mFlickJPInputView?.keyboard !== mFlickJPInputView?.mJPKeyboard) {
                    mFlickJPInputView?.keyboard = mFlickJPInputView!!.mJPKeyboard
                }
            }
            "flick-num" -> {
                if (mEngine.state === SKKASCIIState) handleKanaKey()
                if (mFlickJPInputView?.keyboard !== mFlickJPInputView?.mNumKeyboard) {
                    mFlickJPInputView?.keyboard = mFlickJPInputView!!.mNumKeyboard
                }
            }
            "qwerty" -> {
                if (mEngine.state !== SKKASCIIState) mEngine.processKey('l'.code)
                if (mQwertyInputView?.keyboard !== mQwertyInputView?.mLatinKeyboard) {
                    mQwertyInputView?.keyboard = mQwertyInputView!!.mLatinKeyboard
                }
            }
            "symbols" -> {
                if (mEngine.state !== SKKASCIIState) mEngine.processKey('l'.code)
                if (mQwertyInputView?.keyboard !== mQwertyInputView!!.mSymbolsKeyboard) {
                    mQwertyInputView?.keyboard = mQwertyInputView!!.mSymbolsKeyboard
                }
            }
            else -> if (restarting) currentInputConnection
                .setComposingText(mEngine.mComposingText, mEngine.mCursorPosition)
        }
    }

    /**
     * Called by the framework when your view for showing candidates
     * needs to be generated, like [.onCreateInputView].
     */
    override fun onCreateCandidatesView(): View {
        if (mCandidateViewContainer == null) {
            val context = when (skkPrefs.theme) {
                "light" -> createNightModeContext(applicationContext, false)
                "dark" -> createNightModeContext(applicationContext, true)
                else -> applicationContext
            }

            val container = View.inflate(context, R.layout.view_candidates, null) as CandidateViewContainer
            container.setService(this)
            container.initViews()
            val view: CandidateView = container.findViewById(R.id.candidates)
            view.setService(this)
            view.setContainer(container)
            mCandidateView = view

            val sp = skkPrefs.candidatesSize
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), context.resources.displayMetrics
            ).toInt()
            container.setSize(px)

            setCandidatesView(container)
            mCandidateViewContainer = container
        }
        if (isInputViewShown && (mUseSoftKeyboard || skkPrefs.useCandidatesView)) {
            setCandidatesViewShown(true)
            mCandidateViewContainer?.setAlpha(96)
        }
        return mCandidateViewContainer!!
    }

    // 状態変化を onConfigurationChanged に頼ると取りこぼすので自前でチェック
    // FIXME: 分割画面使用時に画面回転した場合など変な位置に表示されることがある
    private var mOrientation = Configuration.ORIENTATION_UNDEFINED
    private var mScreenWidth = 0
    private var mScreenHeight = 0
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        if (mOrientation != resources.configuration.orientation
            || mScreenWidth != resources.displayMetrics.widthPixels
            || mScreenHeight != resources.displayMetrics.heightPixels
        ) {
            mOrientation = resources.configuration.orientation
            mScreenWidth = resources.displayMetrics.widthPixels
            mScreenHeight = resources.displayMetrics.heightPixels

            // なぜか一度隠しておいて showWindow(true) しないと後で隠れてしまう
            requestHideSelf(0)
            mHandler.postDelayed({
                // なぜか readPrefsForInputView() ではサイズ修正されない
                setInputView(onCreateInputView())
                showWindow(true)
            }, 800)
        }
    }

    /**
     * This is called when the user is done editing a field.  We can use
     * this to reset our state.
     */
    override fun onFinishInput() {
        super.onFinishInput()

        mQwertyInputView?.handleBack()
        mAbbrevKeyboardView?.handleBack()
    }

    override fun onDestroy() {
        mEngine.commitUserDictChanges()
        mSpeechRecognizer.destroy()
        instance = null

        super.onDestroy()
    }

    // never use fullscreen mode
    override fun onEvaluateFullscreenMode() = false

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null || mInputView == null || mCandidateViewContainer == null) return
        outInsets.apply {
            if (isFloating()) {
                val height = mInputView!!.height + mCandidateViewContainer!!.height
                contentTopInsets = height
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(leftOffset, 0, leftOffset + mInputView!!.keyboard.width, height)
            } else {
                contentTopInsets = visibleTopInsets
                // CandidatesViewに対して強制的にActivityをリサイズさせるためのhack
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    /**
     * Use this to monitor key events being delivered to the
     * application. We get first crack at them, and can either resume
     * them or let them continue to the app.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mEngine.state === SKKASCIIState) { return super.onKeyUp(keyCode, event) }

        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (mStickyShift) {
                    mShiftKey.release()
                    return true
                }
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (mSandS) {
                    mSpacePressed = false
                    if (!mSandSUsed) processKey(' '.code)
                    mSandSUsed = false
                    return true
                }
                if (isEnterUsed) {
                    isEnterUsed = false
                    return true
                }
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (isEnterUsed) {
                    isEnterUsed = false
                    return true
                }
            }
            else -> {}
        }

        return super.onKeyUp(keyCode, event)
    }

    /**
     * Use this to monitor key events being delivered to the
     * application. We get first crack at them, and can either resume
     * them or let them continue to the app.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val engineState = mEngine.state
        val encodedKey = SetKeyDialogFragment.encodeKey(event)

        // Process special keys
        if (encodedKey == skkPrefs.kanaKey) {
            mEngine.handleKanaKey()
            return true
        }

        if (engineState === SKKASCIIState && !mEngine.isRegistering) {
            return super.onKeyDown(keyCode, event)
        }

        if (encodedKey == skkPrefs.cancelKey) {
            if (handleCancel()) { return true }
        }

        if (engineState === SKKAbbrevState && encodedKey == 724) { // 724はCtrl+q
            processKey(-1010)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (engineState === SKKKanjiState || engineState === SKKAbbrevState) {
                var isShifted = false
                if (mStickyShift) {
                    if (mShiftKey.useState() and KeyEvent.META_SHIFT_ON != 0) {
                        isShifted = true
                    }
                } else if (mSandS) {
                    if (mSpacePressed) {
                        isShifted = true
                        mSandSUsed = true
                    }
                } else {
                    if (event.metaState and KeyEvent.META_SHIFT_ON != 0) {
                        isShifted = true
                    }
                }
                mEngine.chooseAdjacentSuggestion(!isShifted)
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (mStickyShift) {
                    mShiftKey.press()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK  -> if (mEngine.handleBackKey()) { return true }
            KeyEvent.KEYCODE_DEL   -> if (handleBackspace()) { return true }
            KeyEvent.KEYCODE_ENTER -> if (handleEnter()) { return true }
            KeyEvent.KEYCODE_SPACE -> {
                if (mSandS) {
                    mSpacePressed = true
                } else {
                    processKey(' '.code)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> if (handleDpad(keyCode)) { return true }
            else ->
                // For all other keys, if we want to do transformations on
                // text being entered with a hard keyboard, we need to
                // process it and do the appropriate action.
                if (translateKeyDown(event)) { return true }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * This translates incoming hard key events in to edit operations
     * on an InputConnection.
     */
    private fun translateKeyDown(event: KeyEvent): Boolean {
        val c: Int
        if (mStickyShift) {
            c = event.getUnicodeChar(mShiftKey.useState())
        } else {
            if (mSandS && mSpacePressed) {
                c = event.getUnicodeChar(KeyEvent.META_SHIFT_ON)
                mSandSUsed = true
            } else {
                c = event.unicodeChar
            }
        }

        val ic = currentInputConnection
        if (c == 0 || ic == null) { return false }

        processKey(c)

        return true
    }

    fun processKey(pcode: Int) {
        mEngine.processKey(pcode)
    }
    fun processKeyIn(state: SKKState, pcode: Int) {
        state.processKey(mEngine, pcode)
    }
    fun handleKanaKey() {
        mEngine.handleKanaKey()
    }
    fun handleCancel(): Boolean {
        return mEngine.handleCancel()
    }
    fun changeLastChar(type: String) {
        mEngine.changeLastChar(type)
    }
    fun commitTextSKK(text: CharSequence, newCursorPosition: Int) {
        mEngine.commitTextSKK(text, newCursorPosition)
    }
    fun googleTransliterate() {
        mEngine.googleTransliterate()
    }
    fun pickCandidateViewManually(index: Int) {
        mEngine.pickCandidateViewManually(index)
    }

    fun handleBackspace(): Boolean {
        if (mStickyShift) mShiftKey.useState()
        return mEngine.handleBackspace()
    }

    fun handleEnter(): Boolean {
        if (mStickyShift) mShiftKey.useState()

        if (mEngine.handleEnter()) {
            isEnterUsed = true
            return true
        } else {
            return false
        }
    }

    fun handleDpad(keyCode: Int): Boolean {
        if (mStickyShift) mShiftKey.useState()
        when {
            mEngine.isRegistering -> { return true }
            mEngine.state === SKKChooseState -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> mEngine.chooseAdjacentCandidate(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mEngine.chooseAdjacentCandidate(true)
                }
                return true
            }
            mEngine.state.isTransient -> { return true }
        }

        return false
    }

    /**
     * Helper to send a key down / key up pair to the current editor.
     */
    fun keyDownUp(keyEventCode: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEventCode))
    }

    fun pressEnter() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo

        when (editorInfo.imeOptions and
                (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            EditorInfo.IME_ACTION_DONE   -> ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            EditorInfo.IME_ACTION_GO     -> ic.performEditorAction(EditorInfo.IME_ACTION_GO)
            EditorInfo.IME_ACTION_NEXT   -> ic.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            EditorInfo.IME_ACTION_SEARCH -> ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            EditorInfo.IME_ACTION_SEND   -> ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            else -> keyDownUp(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun onStartRegister() {
        val flick = mFlickJPInputView ?: return
        if (mUseSoftKeyboard) flick.setRegisterMode(true)
    }

    fun onFinishRegister() {
        val flick = mFlickJPInputView ?: return
        if (mUseSoftKeyboard) flick.setRegisterMode(false)
    }

    fun sendToMushroom() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(this) ?: ""
        val str = mEngine.prepareToMushroom(clip.toString())

        val mushroom = Intent(this, SKKMushroom::class.java)
        mushroom.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        mushroom.putExtra(SKKMushroom.REPLACE_KEY, str)
        startActivity(mushroom)
    }

    fun pasteClip()  {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val cs = cm.primaryClip?.getItemAt(0)?.text
        val clip = cs?.toString() ?: ""
        commitTextSKK(clip, 1)
    }

    fun startSettings() {
        val settingsIntent = Intent(this, SKKSettingsActivity::class.java)
        settingsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(settingsIntent)
    }

    fun recognizeSpeech() {
        if (mIsRecording) {
//            mSpeechRecognizer.stopListening()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // checkSelfPermission は api 23 必要
            if (listOf(Manifest.permission.RECORD_AUDIO).any { // 将来複数必要になったときのため List.any
                checkSelfPermission(it) == PackageManager.PERMISSION_DENIED
            }) {
                // requestPermissions は activity が必要なので雑に設定画面を出すだけにする
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
        }
        mStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        mSpeechRecognizer.startListening(intent)
        mFlickJPInputView?.let {
            it.keyboard.keys[2].on = true // 「声」キー
            it.invalidateKey(2)
            it.isEnabled = false
        }
        mIsRecording = true
    }

    fun updateSuggestionsASCII() {
        mEngine.updateSuggestionsASCII()
    }

    fun setCandidates(list: List<String>?, number: String) {
        if (list != null) {
            mCandidateViewContainer?.setAlpha(255)
            mCandidateView?.setContents(list, number)
        }
    }

    fun requestChooseCandidate(index: Int) {
        mCandidateView?.choose(index)
    }

    fun clearCandidatesView() {
        mCandidateView?.setContents(listOf(), "#")
        mCandidateViewContainer?.setAlpha(96)
    }

    // カーソル直前に引数と同じ文字列があるなら，それを消してtrue なければfalse
    fun prepareReConversion(candidate: String): Boolean {
        val ic = currentInputConnection
        if (ic != null && candidate == ic.getTextBeforeCursor(candidate.length, 0)) {
            ic.deleteSurroundingText(candidate.length, 0)
            return true
        }

        return false
    }

    fun changeSoftKeyboard(state: SKKState) {
        if (!mUseSoftKeyboard) return

        // 長押しリピートの message が残っている可能性があるので止める
        for (kv in arrayOf(mAbbrevKeyboardView, mFlickJPInputView, mQwertyInputView)) {
            kv?.stopRepeatKey()
        }

        mQwertyInputView?.setKeyState(mEngine.state) // 整合性のため必ず通っておく

        val inputView = when (state) {
            SKKASCIIState    -> mQwertyInputView ?: return
            SKKKanjiState    -> mFlickJPInputView ?: return
            SKKHiraganaState -> mFlickJPInputView?.setHiraganaMode() ?: return
            SKKKatakanaState -> mFlickJPInputView?.setKatakanaMode() ?: return
            SKKAbbrevState   -> mAbbrevKeyboardView ?: return
            SKKZenkakuState  -> mQwertyInputView ?: return
            else -> return
        }
        setInputView(inputView)
    }

    fun changeToFlick() {
        if (!engineState.changeToFlick(mEngine)) {
            setInputView(mFlickJPInputView)
        }
    }

    override fun setInputView(view: View?) {
        val displayWidth = resources.displayMetrics.widthPixels

        if (view != null) { // null だと再描画だけ
            super.setInputView(view)
            mInputView = view as KeyboardView
            val widthRate = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyWidthPort
                Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyWidthLand
                else -> 100
            }
            val zoom = if (mInputView == mFlickJPInputView) 100 else skkPrefs.keyWidthQwertyZoom
            val keyWidth = displayWidth * (widthRate * zoom).coerceAtMost(10000) / 10000f
            leftOffset = ((displayWidth - keyWidth) * when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyLeftLand
                else -> skkPrefs.keyLeftPort
            }).toInt()
        }

        mInputView?.let { inputView ->
            val right = displayWidth - leftOffset - inputView.keyboard.width
            (inputView.parent as FrameLayout).let {
                it.setPadding(leftOffset, 0, right, 0)
            }
            mCandidateViewContainer?.parent?.let {
                (it as FrameLayout).setPadding(leftOffset, 0, right, 0)
            }
        }
    }

    private fun isFloating(): Boolean = skkPrefs.run {
        val baseWidthRate = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> keyWidthPort
            Configuration.ORIENTATION_LANDSCAPE -> keyWidthLand
            else -> 100 // readPrefsForInputView を参照
        }
        val zoom = if (mInputView == mFlickJPInputView) 100 else keyWidthQwertyZoom
        (baseWidthRate * zoom).coerceAtMost(10000) / 100 < 50 &&
                when (mOrientation) {
                    Configuration.ORIENTATION_PORTRAIT -> keyHeightPort > 50
                    Configuration.ORIENTATION_LANDSCAPE -> keyHeightLand > 50
                    else -> false // 30 > 50
                }
    }

    override fun showStatusIcon(iconRes: Int) {
        if (!mUseSoftKeyboard && iconRes != 0) super.showStatusIcon(iconRes)
    }

    private fun ping() = true

    companion object {
        private var instance: SKKService? = null
        internal fun isRunning(): Boolean {
            return try {
                instance?.ping() ?: false
            } catch (e: NullPointerException) {
                false
            }
        }

        internal const val KEY_COMMAND = "jp.deadend.noname.skk.KEY_COMMAND"
        internal const val COMMAND_COMMIT_USERDIC = "jp.deadend.noname.skk.COMMAND_COMMIT_USERDIC"
        internal const val COMMAND_READ_PREFS = "jp.deadend.noname.skk.COMMAND_READ_PREFS"
        internal const val COMMAND_RELOAD_DICS = "jp.deadend.noname.skk.COMMAND_RELOAD_DICS"
        internal const val COMMAND_MUSHROOM = "jp.deadend.noname.skk.COMMAND_MUSHROOM"
        internal const val DICT_ASCII_ZIP_FILE = "skk_asciidict.zip"
        private const val CHANNEL_ID = "skk_notification"
        private const val CHANNEL_NAME = "SKK"
    }
}
