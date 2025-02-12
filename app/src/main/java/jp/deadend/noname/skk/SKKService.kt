package jp.deadend.noname.skk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import jp.deadend.noname.skk.engine.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.*


class SKKService : InputMethodService() {
    private var mCandidateViewContainer: CandidateViewContainer? = null
    private var mCandidateView: CandidateView? = null
    private var mFlickJPInputView: FlickJPKeyboardView? = null
    private var mGodanInputView: GodanKeyboardView? = null
    private var mQwertyInputView: QwertyKeyboardView? = null
    private var mAbbrevKeyboardView: AbbrevKeyboardView? = null
    // 現在表示中の KeyboardView
    private var mInputView: KeyboardView? = mFlickJPInputView
    // 幅の確認
    internal val isFlickWidth: Boolean
        get() = (mInputView != mQwertyInputView && mInputView != mAbbrevKeyboardView)
    internal val isTemporaryView: Boolean
        get() = (mInputView == mAbbrevKeyboardView || mEngine.state === SKKZenkakuState)
    internal var leftOffset = 0
    // 画面サイズが実際に変わる前に onConfigurationChanged で受け取ってサイズ計算
    private var mOrientation = Configuration.ORIENTATION_UNDEFINED
    internal var mScreenWidth = 0
    internal var mScreenHeight = 0
    // 入力中かどうか
    private var mInputStarted = false

    private val mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    private var mIsRecording = false
    private lateinit var mAudioManager: AudioManager
    private var mStreamVolume = 0

    private lateinit var mEngine: SKKEngine
    internal val engineState: SKKState
        get() {
            val rawState = mEngine.state
            return if (rawState === SKKEmojiState) mEngine.oldState else rawState
        }
    private lateinit var mUserDic: SKKUserDictionary
    private lateinit var mAsciiDic: SKKUserDictionary
    private lateinit var mEmojiDic: SKKUserDictionary

    internal val isHiragana: Boolean
        get() = kanaState === SKKHiraganaState
    internal var kanaState: SKKState
        get() = mEngine.kanaState
        set(state) {
            val oldState = kanaState
            mEngine.kanaState = state
            if (oldState != state) {
                mFlickJPInputView?.setKeyState(state)
                mQwertyInputView?.setKeyState(state)
                mGodanInputView?.setKeyState(state)
            }
        }
    internal val isComposingN
        get() = mEngine.mComposing.toString() == "n"

    // onKeyDown()でEnterキーのイベントを消費したかどうかのフラグ．onKeyUp()で判定するのに使う
    private var isEnterUsed = false

    private val mShiftKey = SKKStickyShift(this)
    private var mStickyShift = false
    private var mSandS = false
    private var mSpacePressed = false
    private var mSandSUsed = false

    private val mHandler = Handler(Looper.getMainLooper())

    private var mPendingInput: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(KEY_COMMAND)) {
            COMMAND_LOCK_USERDIC -> {
                dlog("commit user dictionary!")
                mEngine.commitUserDictChanges()
                mEngine.lockUserDict()
            }
            COMMAND_UNLOCK_USERDIC -> {
                mEngine.unlockUserDict()
            }
            COMMAND_READ_PREFS -> {
                onConfigurationChanged(Configuration(resources.configuration))
                onInitializeInterface()
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

    internal fun extractDictionary(dicName: String): Boolean {
        try {
            dlog("dic extract start")
            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.message_extracting_dic), Toast.LENGTH_SHORT
                ).show()
            }

            unzipFile(resources.assets.open("$dicName.zip"), filesDir)

            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.message_dic_extracted), Toast.LENGTH_SHORT
                ).show()
            }
            return true
        } catch (e: IOException) {
            Log.e("SKK", "I/O error in extracting dictionary files: $e")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            notify(NOTIFY_ID_DETAILS, "深刻なエラー", getText(R.string.error_extracting_dic_failed), pendingIntent)
            return false
        }
    }

    private fun openDictionaries(): List<SKKDictionaryInterface> {
        val result = mutableListOf<SKKDictionaryInterface>()
        val dd = filesDir.absolutePath
        dlog("dict dir: $dd")

        val prefVal = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(
                    getString(R.string.prefkey_dics_order),
                    "ユーザー辞書/${getString(R.string.dic_name_user)}/絵文字辞書/${getString(R.string.dic_name_emoji)}/"
                )
        dlog("dict pref: $prefVal")
        if (!prefVal.isNullOrEmpty()) {
            val vals = prefVal.split("/").dropLastWhile { it.isEmpty() }
            for (i in 1 until vals.size step 2) {
                when (vals[i]) {
                    getString(R.string.dic_name_user) -> result.add(mUserDic)
                    //getString(R.string.dic_name_ascii) -> result.add(mAsciiDic)
                    getString(R.string.dic_name_emoji) -> result.add(mEmojiDic)
                    else -> SKKDictionary.newInstance(
                        dd + "/" + vals[i], getString(R.string.btree_name)
                    )?.let { result.add(it) } ?: dlog("failed to open ${vals[i]}")
                }
            }
        }

        return result
    }

    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(MyUncaughtExceptionHandler(applicationContext))

        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        // 事前に権限を持っていないと、onCreate 内では権限要求を出せないみたいなので注意！

        fun openUserDictionary(name: String, isASCII: Boolean): SKKUserDictionary {
            val dic = SKKUserDictionary.newInstance(
                this@SKKService,
                filesDir.absolutePath + "/" + name,
                getString(R.string.btree_name),
                isASCII
            )
            if (dic == null) {
                val intent = Intent(applicationContext, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                notify(
                    NOTIFY_ID_ERROR_DIC,
                    "エラー ($name)",
                    getString(R.string.error_user_dic),
                    pendingIntent
                )
                super.onDestroy()
            }
            return dic!!
        }
        mUserDic = openUserDictionary(getString(R.string.dic_name_user), isASCII = false)
        mAsciiDic = openUserDictionary(getString(R.string.dic_name_ascii), isASCII = true)
        mEmojiDic = openUserDictionary(getString(R.string.dic_name_emoji), isASCII = true)
        val dics = openDictionaries()
        if (dics.minus(mUserDic).minus(mEmojiDic).isEmpty()) {
            val intent = Intent(applicationContext, SKKDicManager::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            notify(NOTIFY_ID_ERROR_DIC, "辞書を設定してください", getString(R.string.error_open_dicfile), pendingIntent)
        }

        mEngine = SKKEngine(this@SKKService, dics, mUserDic, mAsciiDic, mEmojiDic)

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
                            commitTextSKK(matches[0])
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
        mScreenWidth = if (Build.VERSION.SDK_INT >= 35) {
            val manager = getSystemService(WindowManager::class.java)
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            metrics.bounds.width() - insets.left - insets.right
        } else {
            resources.displayMetrics.widthPixels
        }
        mScreenHeight = resources.displayMetrics.heightPixels

        readPrefs()
        instance = this
    }

    private fun readPrefs() {
        val context = applicationContext
        mStickyShift = skkPrefs.useStickyMeta
        mSandS = skkPrefs.useSandS
        mEngine.setZenkakuPunctuationMarks(skkPrefs.kutoutenType)

        updateInputViewShown()

        if (mFlickJPInputView != null) readPrefsForInputView()

        mCandidateViewContainer?.setSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            skkPrefs.candidatesSize.toFloat(),
            context.resources.displayMetrics
        ).toInt())
    }

    private fun readPrefsForInputView() {
        val flick = mFlickJPInputView?.setKeyState(engineState)
        val godan = mGodanInputView?.setKeyState(engineState)
        val qwerty = mQwertyInputView?.setKeyState(engineState)
        val abbrev = mAbbrevKeyboardView?.setKeyState(engineState)
        if (flick == null || godan == null || qwerty == null || abbrev == null) return

        val context = when (skkPrefs.theme) {
            "light" -> createNightModeContext(applicationContext, false)
            "dark"  -> createNightModeContext(applicationContext, true)
            else    -> applicationContext
        }
        val keyHeight = keyboardHeight()
        val keyBottom = skkPrefs.keyPaddingBottom
        val flickWidth = when (mOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyWidthPort
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyWidthLand
            else -> mScreenWidth
        }
        val alpha = skkPrefs.backgroundAlpha
        flick.prepareNewKeyboard(context, flickWidth, keyHeight, keyBottom)
        flick.backgroundAlpha = 255 * alpha / 100
        godan.prepareNewKeyboard(context, flickWidth, keyHeight, keyBottom)
        godan.backgroundAlpha = 255 * alpha / 100

        val qwertyWidth = (flickWidth * skkPrefs.keyWidthQwertyZoom / 100).coerceAtMost(mScreenWidth)
        qwerty.keyboard.resize(qwertyWidth, keyHeight, keyBottom)
        qwerty.mSymbolsKeyboard.resize(qwertyWidth, keyHeight, keyBottom)
        abbrev.keyboard.resize(qwertyWidth, keyHeight, keyBottom)

        val density = context.resources.displayMetrics.density
        val sensitivity = when (skkPrefs.flickSensitivity) {
            "low"  -> (36 * density + 0.5f).toInt()
            "high" -> (12 * density + 0.5f).toInt()
            else   -> (24 * density + 0.5f).toInt()
        }
        flick.setFlickSensitivity(sensitivity)
        godan.setFlickSensitivity(sensitivity)
        qwerty.setFlickSensitivity(sensitivity)
        qwerty.backgroundAlpha = 255 * alpha / 100
        abbrev.setFlickSensitivity(sensitivity)
        abbrev.backgroundAlpha = 255 * alpha / 100
    }

    private fun checkUseSoftKeyboard(
        default: Boolean = super.onEvaluateInputViewShown()
    ): Boolean = when (skkPrefs.useSoftKey) {
        "on" -> true.also { dlog("software keyboard forced") }
        "off" -> false.also { dlog("software keyboard disabled") }
        else -> default
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
        setInputView(onCreateInputView())
        readPrefs()
        updateInputViewShown()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // candidatesView は inputViewShown に依存するはず
        val atLeastCandidatesAreShown = skkPrefs.useCandidatesView ||
                checkUseSoftKeyboard(super.onEvaluateInputViewShown())
        setCandidatesViewShown(atLeastCandidatesAreShown)
        return atLeastCandidatesAreShown
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        assert(newConfig.orientation == resources.configuration.orientation)
        mOrientation = resources.configuration.orientation
        mScreenWidth = if (Build.VERSION.SDK_INT >= 35) {
            val manager = getSystemService(WindowManager::class.java)
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            metrics.bounds.width() - insets.left - insets.right
        } else {
            resources.displayMetrics.widthPixels
        }
        mScreenHeight = resources.displayMetrics.heightPixels
        super.onConfigurationChanged(newConfig) // これが onInitializeInterface を呼ぶ
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
        mGodanInputView = GodanKeyboardView(context, null)
        mGodanInputView?.setService(this)
        mQwertyInputView = QwertyKeyboardView(context, null)
        mQwertyInputView?.setService(this)
        mAbbrevKeyboardView = AbbrevKeyboardView(context, null)
        mAbbrevKeyboardView?.setService(this)

        if (skkPrefs.useInset) {
            ResourcesCompat.getDrawable(context.resources, R.drawable.key_bg_inset, null)?.let {
                mFlickJPInputView?.setKeyBackground(it)
                mGodanInputView?.setKeyBackground(it)
                mQwertyInputView?.setKeyBackground(it)
                mAbbrevKeyboardView?.setKeyBackground(it)
            }
        }

        readPrefsForInputView()
    }

    override fun onCreateInputView(): View? {
        setCandidatesView(onCreateCandidatesView())

        val wasGodan =
            if (mInputView == null) skkPrefs.preferGodan
            else (mInputView == mGodanInputView)
        val wasFlick =
            if (mInputView == null) skkPrefs.preferFlick
            else (mInputView == mFlickJPInputView)
        createInputView()

        return if (wasGodan) mGodanInputView?.setKeyState(engineState) else when (engineState) {
            SKKASCIIState, SKKZenkakuState -> mQwertyInputView?.setKeyState(engineState)
            SKKAbbrevState -> mAbbrevKeyboardView?.setKeyState(engineState)
            else -> if (wasFlick) mFlickJPInputView?.setKeyState(engineState)
            else mQwertyInputView?.setKeyState(engineState)
        }
    }

    /**
     * This is the main point where we do our initialization of the
     * input method to begin operating on an application. At this
     * point we have been bound to the client, and are now receiving
     * all of the detailed information about the target of our edits.
     */
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        if (attribute.inputType == InputType.TYPE_NULL) {
            requestHideSelf(0)
            return
        }
        super.onStartInput(attribute, restarting)

        if (!mPendingInput.isNullOrEmpty()) {
            currentInputConnection.commitText(mPendingInput!!, 1)
            mPendingInput = null
        }

        if (mStickyShift) mShiftKey.clearState()
        if (mSandS) {
            mSpacePressed = false
            mSandSUsed = false
        }

        mInputStarted = true
        showStatusIcon(engineState.icon)
        // 現在の実装では、表示しないとき (ハードキー利用時など) もUIを作っておかないとあとで表示するとき困る
        if (mFlickJPInputView == null) {
            onInitializeInterface()
        }

        mEngine.isPersonalizedLearning =
            (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0

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
                mEngine.resetOnStartInput()
                if (mEngine.state === SKKASCIIState) handleKanaKey()
                if (mFlickJPInputView?.keyboard !== mFlickJPInputView?.mJPKeyboard) {
                    mFlickJPInputView?.keyboard = mFlickJPInputView!!.mJPKeyboard
                }
            }
            "flick-num" -> {
                mEngine.resetOnStartInput()
                if (mEngine.state === SKKASCIIState) handleKanaKey()
                if (mFlickJPInputView?.keyboard !== mFlickJPInputView?.mNumKeyboard) {
                    mFlickJPInputView?.keyboard = mFlickJPInputView!!.mNumKeyboard
                }
            }
            "qwerty" -> {
                mEngine.resetOnStartInput()
                if (mEngine.state !== SKKASCIIState) mEngine.processKey('l'.code)
                if (mQwertyInputView?.keyboard !== mQwertyInputView?.mLatinKeyboard) {
                    mQwertyInputView?.keyboard = mQwertyInputView!!.mLatinKeyboard
                }
            }
            "symbols" -> {
                mEngine.resetOnStartInput()
                if (mEngine.state !== SKKASCIIState) mEngine.processKey('l'.code)
                if (mQwertyInputView?.keyboard !== mQwertyInputView!!.mSymbolsKeyboard) {
                    mQwertyInputView?.keyboard = mQwertyInputView!!.mSymbolsKeyboard
                }
            }
            else ->
                if (restarting) {
                    if (mEngine.mComposingText.isNotEmpty()) {
                        dlog("restarting: setComposingText(${mEngine.mComposingText})")
                        currentInputConnection
                            .setComposingText(mEngine.mComposingText, 1)
                    }
                } else {
                    mEngine.resetOnStartInput()
                }
        }
    }

    /**
     * Called by the framework when your view for showing candidates
     * needs to be generated, like [.onCreateInputView].
     */
    override fun onCreateCandidatesView(): View {
        val context = when (skkPrefs.theme) {
            "light" -> createNightModeContext(applicationContext, false)
            "dark" -> createNightModeContext(applicationContext, true)
            else -> applicationContext
        }

        return (View.inflate(context, R.layout.view_candidates, null) as CandidateViewContainer)
            .apply {
                setService(this@SKKService)
                initViews()
                setSize(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        skkPrefs.candidatesSize.toFloat(),
                        context.resources.displayMetrics
                    ).toInt()
                )
                setAlpha(96)

                mCandidateView = findViewById<CandidateView>(R.id.candidates)
                    .also { view ->
                        view.setService(this@SKKService)
                        view.setContainer(this)
                    }
            }
    }

    override fun setCandidatesView(view: View?) {
        (view?.parent as? FrameLayout)?.removeView(view)
        super.setCandidatesView(view)
        mCandidateViewContainer = view as CandidateViewContainer
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        // 設定を変更した場合に反映する
        when {
            skkPrefs.preferGodan && mInputView != mGodanInputView ->
                setInputView(mGodanInputView)
            !skkPrefs.preferGodan && mInputView == mGodanInputView ->
                setInputView(
                    if (skkPrefs.preferFlick && engineState !in listOf(
                        SKKAbbrevState, SKKASCIIState, SKKZenkakuState
                    )) mFlickJPInputView else mQwertyInputView
                )
        }
        super.onStartInputView(editorInfo, restarting)

        // シフト等の状態を同期
        mFlickJPInputView?.setKeyState(engineState)
        mGodanInputView?.setKeyState(engineState)
        mQwertyInputView?.setKeyState(engineState)
        mAbbrevKeyboardView?.setKeyState(engineState)

        showStatusIcon(engineState.icon)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        mEngine.commitComposing()
        super.onFinishInputView(finishingInput)
        hideStatusIcon()
        clearCandidatesView()
    }

    /**
     * This is called when the user is done editing a field.  We can use
     * this to reset our state.
     */
    override fun onFinishInput() {
        mEngine.commitComposing()
        super.onFinishInput()
        hideStatusIcon()
        clearCandidatesView()
        mInputStarted = false

        mQwertyInputView?.handleBack()
        mAbbrevKeyboardView?.handleBack()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        super.onUnbind(intent)

        // このあと onDestroy() が呼ばれないことがあるので強制終了しておく
        // onDestroy() なしだと、次回起動がエラーで起動し直しになる
        MainScope().launch { stopSelf() }

        return false // rebind 不可能であることを示す
    }

    override fun onDestroy() {
        if (instance == null) {
            dlog("skip onDestroy(): instance is null")
            return
        }

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

    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        updateSuggestionsASCII()
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
            val result = super.onKeyDown(keyCode, event)
            updateSuggestionsASCII()
            return result
        }

        if (encodedKey == skkPrefs.cancelKey) {
            if (handleCancel()) { return true }
        }

        if (encodedKey == 724) { // Ctrl-Q
            processKey(17) // 基本的には半角カナだがAbbrevでは全角への変換となる
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
    fun commitTextSKK(text: CharSequence) {
        mEngine.commitTextSKK(text)
    }
    fun googleTransliterate() {
        mEngine.googleTransliterate()
    }
    fun symbolCandidates(sequential: Boolean) {
        mEngine.symbolCandidates(sequential)
    }
    fun emojiCandidates(sequential: Boolean) {
        mEngine.emojiCandidates(sequential)
    }
    fun pickCandidateViewManually(index: Int) {
        mEngine.pickCandidateViewManually(index)
    }

//    fun getTextBeforeCursor(length: Int): CharSequence? {
//        return currentInputConnection?.getTextBeforeCursor(length, 0)
//    }

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
        dlog("handleDpad(${KeyEvent.keyCodeToString(keyCode)}) in ${mEngine.state}")
        if (mStickyShift) mShiftKey.useState()
        when {
            mEngine.state === SKKChooseState -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> mEngine.chooseAdjacentCandidate(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mEngine.chooseAdjacentCandidate(true)
                }
                return true
            }
            mEngine.state === SKKEmojiState -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> mEngine.chooseAdjacentSuggestion(false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mEngine.chooseAdjacentSuggestion(true)
                }
                return true
            }
            mEngine.isRegistering -> { return true }
            mEngine.state.isTransient -> { return true }
        }

        return false
    }

    /**
     * Helper to send a key down / key up pair to the current editor.
     */
    fun keyDownUp(keyEventCode: Int) {
        val ic = currentInputConnection ?: return
        when (keyEventCode) { // 端でカーソル移動しようとすると閉じてしまうので回避
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) return
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEventCode))
        updateSuggestionsASCII()
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
        mFlickJPInputView?.setRegisterMode(true)
    }

    fun onFinishRegister() {
        mFlickJPInputView?.setRegisterMode(false)
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
        commitTextSKK(clip)
    }

    private fun notify(id: Int, title: CharSequence, text: CharSequence, intent: PendingIntent?): Boolean {
        val builder = if (title.isEmpty() || text.isEmpty()) null
        else NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { intent?.let { setContentIntent(it) } }
            .setAutoCancel(true)
        with(NotificationManagerCompat.from(this)) {
            return if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) {
                builder?.let { notify(id, it.build()) }
                true
            } else {
                // onCreate 内では startActivity できないみたいなので注意！
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayListOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                false
            }
        }
    }

    class PermissionRequestActivity: AppCompatActivity() {
        override fun onStart() {
            super.onStart()
            intent?.getStringArrayListExtra("perm")?.let {
                ActivityCompat.requestPermissions(this, it.toTypedArray(), 1)
            }
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            finish()
        }
    }

    private fun requestPermissions(perms: ArrayList<String>) {
        val intent = Intent(this, PermissionRequestActivity::class.java).apply {
            putStringArrayListExtra("perm", perms)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun recognizeSpeech() {
        if (mIsRecording) {
//            mSpeechRecognizer.stopListening()
            return
        }
        arrayListOf(Manifest.permission.RECORD_AUDIO).let { perms ->
            if (perms.any {
                    checkSelfPermission(it) == PackageManager.PERMISSION_DENIED
                }) {
                requestPermissions(perms)
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

    fun suspendSuggestions() {
        mEngine.suspendSuggestions()
    }
    fun resumeSuggestions() {
        mEngine.resumeSuggestions()
    }

    fun setCandidates(list: List<String>?, kanjiKey: String, lines: Int) {
        if (list.isNullOrEmpty()) {
            mCandidateViewContainer?.setAlpha(96)
            mCandidateViewContainer?.lines = 1
        } else {
            mCandidateViewContainer?.setAlpha(255)
            mCandidateViewContainer?.lines = lines
        }
        mCandidateView?.setContents(list, kanjiKey)
    }

    fun requestChooseCandidate(index: Int) {
        mCandidateView?.choose(index)
    }

    fun clearCandidatesView() {
        setCandidates(null, "", 1)
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

    // SKKState で指定された種類のキーボードに setInputView する
    // engineState とは違うものが指定されることもある
    // (FlickJP から、ひらがなモードのまま Qwerty に変更する場合など)
    fun changeSoftKeyboard(state: SKKState) {
        // 長押しリピートの message が残っている可能性があるので止める
        for (kv in arrayOf(mAbbrevKeyboardView, mFlickJPInputView, mGodanInputView, mQwertyInputView)) {
            kv?.stopRepeatKey()
        }

        setInputView(
            if (skkPrefs.preferGodan) {
                mGodanInputView?.setKeyState(state)
            } else when (state) {
                // state==ASCII は Qwerty にしたがっているだけの場合があるので引数を使わない
                // 他の場合は基本的に state==engineState のはずなので、どちらでも構わない
                SKKASCIIState   -> mQwertyInputView?.setKeyState(engineState)
                SKKKanjiState   -> mFlickJPInputView?.setKeyState(state)
                SKKHiraganaState, SKKKatakanaState, SKKHanKanaState
                                -> mFlickJPInputView?.setKeyState(state)
                SKKAbbrevState  -> mAbbrevKeyboardView?.setKeyState(state)
                SKKZenkakuState -> mQwertyInputView?.setKeyState(state)
                else -> throw Exception("invalid state: $state")
            } ?: return
        )
    }

    fun changeToFlick() {
        if (!engineState.changeToFlick(mEngine)) {
            setInputView(mFlickJPInputView?.setKeyState(engineState))
        }
    }

    internal fun setInputViewWidth(width: Int) {
        mInputView?.let { inputView ->
            inputView.keyboard.resize(width, keyboardHeight(), skkPrefs.keyPaddingBottom)
            inputView.requestLayout()
            setInputView(null)
        }
    }

    override fun setInputView(view: View?) {
        // view が null のときはここをスキップして再描画だけする (ドラッグで位置調整のとき使う)
        (view as? KeyboardView)?.let { inputView ->
            mInputView = inputView
            mInputView!!.apply {
                parent?.let { (it as FrameLayout).removeView(view) }
                keyboard.resize(keyboardWidth(), keyboardHeight(), skkPrefs.keyPaddingBottom)
                requestLayout()
            }
            super.setInputView(mInputView)
            computeLeftOffset()
        }

        val right = mScreenWidth - leftOffset - mInputView!!.keyboard.width
        mInputView!!.parent?.let {
            (it as FrameLayout).setPadding(leftOffset, 0, right, 0)
        }
        mCandidateViewContainer?.parent?.let {
            (it as FrameLayout).setPadding(leftOffset, 0, right, 0)
        }
    }

    private fun keyboardWidth() =
        (when (mOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyWidthPort
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyWidthLand
            else -> mScreenWidth
        } * (if (isFlickWidth) 100 else skkPrefs.keyWidthQwertyZoom) / 100)
            .coerceAtMost(mScreenWidth)

    private fun keyboardHeight() = if (!checkUseSoftKeyboard()) 0 else
        mScreenHeight * when (mOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyHeightPort
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyHeightLand
            else -> 30
        } / 100

    private fun computeLeftOffset() {
        leftOffset = (mScreenWidth * when (mOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyCenterLand
            else -> skkPrefs.keyCenterPort
        } - keyboardWidth() / 2)
            .toInt()
            .coerceIn(0, mScreenWidth - keyboardWidth())
    }

    private fun isFloating(): Boolean = keyboardWidth() < mScreenWidth - mScreenHeight
//            && when (mOrientation) {
//                Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyHeightPort > 50
//                Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyHeightLand > 30
//                else -> false // 30 > 30..50
//            }

    override fun showStatusIcon(iconRes: Int) {
        if ((mInputStarted && (!checkUseSoftKeyboard() || skkPrefs.showStatusIcon))
            && iconRes != 0)
        {
            super.showStatusIcon(iconRes)
        }
    }

    @Suppress("SameReturnValue")
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
        internal const val COMMAND_LOCK_USERDIC = "jp.deadend.noname.skk.COMMAND_LOCK_USERDIC"
        internal const val COMMAND_UNLOCK_USERDIC = "jp.deadend.noname.skk.COMMAND_UNLOCK_USERDIC"
        internal const val COMMAND_READ_PREFS = "jp.deadend.noname.skk.COMMAND_READ_PREFS"
        internal const val COMMAND_RELOAD_DICS = "jp.deadend.noname.skk.COMMAND_RELOAD_DICS"
        internal const val COMMAND_MUSHROOM = "jp.deadend.noname.skk.COMMAND_MUSHROOM"
        private const val CHANNEL_ID = "skk_notification"
        private const val CHANNEL_NAME = "SKK"
        private const val NOTIFY_ID_ERROR_DIC = 1
        private const val NOTIFY_ID_DETAILS = 2
    }
}
