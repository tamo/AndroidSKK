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
import android.hardware.display.DisplayManager
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
import android.text.SpannableString
import android.util.TypedValue
import android.view.Display.DEFAULT_DISPLAY
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import jp.deadend.noname.skk.databinding.InputViewBinding
import jp.deadend.noname.skk.engine.RomajiConverter
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKAbbrevState
import jp.deadend.noname.skk.engine.SKKEngine
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import android.graphics.Insets as AGInsets
import android.view.WindowInsets.Type as InsetsType

class SKKService : InputMethodService() {
    private lateinit var mBinding: InputViewBinding
    private val mCandidatesViewContainer get() = mBinding.candidatesContainer.root
    internal val mCandidatesView get() = mBinding.candidatesContainer.candidates

    private var mFlickJPInputView: FlickJPKeyboardView? = null
    private var mQwertyInputView: QwertyKeyboardView? = null

    // 現在表示中の KeyboardView
    private var mInputView: KeyboardView? = mFlickJPInputView
    internal var inputViewWidth
        get() = mInputView?.keyboard?.width ?: mRootWidth
        set(width) {
            // CandidatesViewContainer をドラッグしている間にしか呼ばれない
            // だからすべての keyboard を resize するのではなく現行だけでいい
            mInputView?.let { inputView ->
                inputView.keyboard.resize(width, keyboardHeight())
                inputView.requestLayout()
                setInputView(null)
            }
        }

    // テンキー強制などから復元するための各種状態
    // 生で保持すると createInputView を通過できないので enum で保持
    private var mPrevStates: PrevStates? = null

    private class PrevStates(
        val context: SKKService,
        originalView: KeyboardView?,
        val state: SKKState
    ) {
        val keyboardType: KeyboardType? = when (originalView?.keyboard) {
            null -> null
            context.mFlickJPInputView?.mJPKeyboard -> KeyboardType.FlickJPJP
            context.mFlickJPInputView?.mNumKeyboard -> KeyboardType.FlickJPNum
            context.mQwertyInputView?.mLatinKeyboard -> KeyboardType.QwertyLatin
            context.mQwertyInputView?.mSymbolsKeyboard -> KeyboardType.QwertySymbols
            else -> null // ないはず
        }

        val inputView: KeyboardView?
            get() = when (keyboardType) {
                KeyboardType.FlickJPJP, KeyboardType.FlickJPNum -> context.mFlickJPInputView
                KeyboardType.QwertyLatin, KeyboardType.QwertySymbols -> context.mQwertyInputView
                null -> null
            }
        val keyboard: Keyboard?
            get() = when (keyboardType) {
                KeyboardType.FlickJPJP -> context.mFlickJPInputView?.mJPKeyboard
                KeyboardType.FlickJPNum -> context.mFlickJPInputView?.mNumKeyboard
                KeyboardType.QwertyLatin -> context.mQwertyInputView?.mLatinKeyboard
                KeyboardType.QwertySymbols -> context.mQwertyInputView?.mSymbolsKeyboard
                else -> null
            }

        private enum class KeyboardType {
            FlickJPJP, FlickJPNum, QwertyLatin, QwertySymbols
        }
    }

    internal fun isFlick(view: KeyboardView? = null): Boolean =
        (view ?: mInputView).let { it is FlickJPKeyboardView }

    private val isInPref
        get() = currentInputEditorInfo?.privateImeOptions == EDITOR_OPTION_PREF

    internal val isTemporaryQwerty: Boolean
        get() = skkPrefs.softKeyboardType == "switch" &&
                mInputView === mQwertyInputView &&
                engineState.isTemporaryQwerty

    internal var leftOffset = 0
    private val bottomOffset
        get() = max(
            mInsets.bottom,
            keyboardHeight() * skkPrefs.keyPaddingBottom / 100
        )

    // 画面サイズが実際に変わる前に onConfigurationChanged で受け取ってサイズ計算
    private var mOrientation = Configuration.ORIENTATION_UNDEFINED
    internal var mRootWidth = 0
    internal var mCutout = AGInsets.NONE // カメラ穴などで、すでに消えている部分
    internal var mInsets = AGInsets.NONE // mCutout に gesture 用の空間を加えたもの
    internal var mScreenWidth = 0
    internal var mScreenHeight = 0

    // 入力中かどうか
    private var mInputStarted = false

    private val mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    private var mIsRecording = false
    private lateinit var mAudioManager: AudioManager
    private var mStreamVolume = 0

    internal lateinit var mEngine: SKKEngine
    internal val engineState: SKKState get() = mEngine.state
    internal lateinit var mUserDict: SKKUserDictionary
    internal lateinit var mAsciiDict: SKKUserDictionary
    internal lateinit var mEmojiDict: SKKUserDictionary
    internal lateinit var mSymbolDict: SKKUserDictionary

    internal val isHiragana: Boolean
        get() = kanaState is SKKHiraganaState
    internal var kanaState: SKKState
        get() = mEngine.kanaState
        set(state) {
            val oldState = kanaState
            mEngine.kanaState = state
            if (oldState != state) {
                listOf(mFlickJPInputView, mQwertyInputView)
                    .forEach { it?.setKeyState(state) }
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
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        when (intent?.getStringExtra(KEY_COMMAND)) {
            COMMAND_READ_PREFS -> {
                mInputView = null // 以前のviewは新しい設定と矛盾が出るかもしれないので無効化
                onConfigurationChanged(Configuration(resources.configuration))
                onInitializeInterface()
            }

            COMMAND_RELOAD_DICT -> {
                mUserDict.reopen()
                mAsciiDict.reopen()
                mEmojiDict.reopen()
                mEngine.reopenDictionaries(openDictionaries())
            }

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

    internal fun extractDictionary(dictName: String): Boolean {
        return runCatching {
            SKKLog.d("dict extract start")
            mHandler.post {
                Toast.makeText(
                    applicationContext,
                    getText(R.string.message_extracting_dict),
                    Toast.LENGTH_SHORT
                ).show()
            }

            unzipFile(resources.assets.open("$dictName.zip"), filesDir)

            mHandler.post {
                Toast.makeText(
                    applicationContext, getText(R.string.message_dict_extracted), Toast.LENGTH_SHORT
                ).show()
            }
            true
        }.getOrElse { e ->
            if (e is IOException) {
                SKKLog.e("I/O error in extracting dictionary files", e)
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                notify(
                    NOTIFY_ID_DETAILS,
                    "深刻なエラー",
                    getText(R.string.error_extracting_dict_failed).toString(),
                    pendingIntent
                )
                false
            } else throw e
        }
    }

    private fun openDictionaries(): List<SKKDictionaryInterface> {
        val result = mutableListOf<SKKDictionaryInterface>()
        val dd = filesDir.absolutePath
        SKKLog.d("dict dir=$dd order=${skkPrefs.dictOrder}")

        val oldDictList = if (::mEngine.isInitialized) mEngine.mDictList else emptyList()

        skkPrefs.dictOrder.split("/").chunked(2).forEach { chunk ->
            if (chunk.size < 2) return@forEach
            when (val dictPath = chunk[1]) {
                getString(R.string.dict_name_user) -> mUserDict
                getString(R.string.dict_name_emoji) -> null // 以前の設定が残っているが無視
                else -> (if (dictPath.startsWith("/")) dictPath else "$dd/$dictPath").let { fullPath ->
                    (oldDictList.find { it.mFilePath == fullPath } as? SKKSystemDictionary)?.let {
                        if (it.mStore != null) it else null
                    } ?: SKKSystemDictionary.newInstance(
                        fullPath, getString(R.string.btree_name),
                        fun() = mHandler.post(fun() = Toast.makeText(
                            applicationContext, getText(R.string.message_dict_migrating),
                            Toast.LENGTH_SHORT
                        ).show()).run {}
                    ) ?: null.also { SKKLog.d("failed to open $dictPath") }
                }
            }?.let { result.add(it) }
        }
        SKKLog.d("dict list: ${result.joinToString { it.mFilePath }}")

        return result
    }

    override fun onCreate() {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .permitDiskReads()
                    .permitDiskWrites()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        super.onCreate()

        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        // 事前に権限を持っていないと、onCreate 内では権限要求を出せないみたいなので注意！

        fun openUserDictionary(
            name: String, isASCII: Boolean, readonly: Boolean = false
        ): SKKUserDictionary? {
            val dict = SKKUserDictionary.newInstance(
                this@SKKService,
                filesDir.absolutePath + "/" + name, getString(R.string.btree_name),
                isASCII, readonly
            )
            if (dict == null) {
                val intent = Intent(applicationContext, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                notify(
                    NOTIFY_ID_ERROR_DICT,
                    "エラー ($name)",
                    getString(R.string.error_open_user_dict, name),
                    pendingIntent
                )
                stopSelf()
            }
            return dict
        }
        // エラー時には、上で通知を出しているので単に return する (throw しない)
        mUserDict =
            openUserDictionary(getString(R.string.dict_name_user), isASCII = false) ?: return
        mAsciiDict =
            openUserDictionary(getString(R.string.dict_name_ascii), isASCII = true) ?: return
        mEmojiDict =
            openUserDictionary(getString(R.string.dict_name_emoji), isASCII = true, readonly = true)
                ?: return
        mSymbolDict =
            openUserDictionary(
                getString(R.string.dict_name_symbol), isASCII = false, readonly = true
            ) ?: return
        val dictList = openDictionaries()
        if (!dictList.any { it is SKKSystemDictionary }) {
            val intent = Intent(applicationContext, SKKDictManager::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            notify(
                NOTIFY_ID_ERROR_DICT,
                "辞書を設定してください",
                getString(R.string.error_open_dict),
                pendingIntent
            )
        }

        mEngine =
            SKKEngine(this@SKKService, dictList, mUserDict, mAsciiDict, mEmojiDict, mSymbolDict)

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
            override fun onError(error: Int) {
                restoreState()
            }

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
        mAudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        updateDimensions()
        readPrefs()
        instance = this
    }

    private fun readPrefs() {
        val context = applicationContext
        mStickyShift = skkPrefs.useStickyMeta
        mSandS = skkPrefs.useSandS
        mEngine.setZenkakuPunctuationMarks(skkPrefs.kutoutenType)

        val kanaRules = SKKKanaRule.loadFromInternalStorage(context)
        RomajiConverter.loadRules(kanaRules)

        updateInputViewShown()

        if (mFlickJPInputView != null) readPrefsForInputView()

        if (::mBinding.isInitialized) {
            mCandidatesViewContainer.setSize(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    skkPrefs.candidatesSize.toFloat(),
                    context.resources.displayMetrics
                ).toInt()
            )
            mCandidatesViewContainer.setTypeface(getTypeface(skkPrefs.font))
        }
    }

    private fun readPrefsForInputView() {
        val flick = mFlickJPInputView?.setKeyState(engineState)
        val qwerty = mQwertyInputView?.setKeyState(engineState)
        if (flick == null || qwerty == null) return

        val context = createNightModeContext(applicationContext, skkPrefs.theme)
        val keyHeight = keyboardHeight()
        val alpha = skkPrefs.backgroundAlpha
        val typeface = getTypeface(skkPrefs.font)

        val flickWidth = keyboardWidth(flick)
        SKKLog.d("prepare flick ($flickWidth, $keyHeight)")
        flick.prepareNewKeyboard(context, flickWidth, keyHeight)
        flick.backgroundAlpha = 255 * alpha / 100
        flick.setTypeface(typeface)

        val qwertyWidth = keyboardWidth(qwerty)
        SKKLog.d("prepare qwerty ($qwertyWidth, $keyHeight)")
        qwerty.keyboard.resize(qwertyWidth, keyHeight)
        qwerty.mSymbolsKeyboard.resize(qwertyWidth, keyHeight)
        qwerty.setTypeface(typeface)

        val density = context.resources.displayMetrics.density
        val sensitivity = (skkPrefs.flickSensitivity * density + 0.5f).toInt()
        flick.setFlickSensitivity(sensitivity)
        qwerty.setFlickSensitivity(sensitivity)
        qwerty.backgroundAlpha = 255 * alpha / 100
    }

    private fun checkUseSoftKeyboard(
        default: Boolean = super.onEvaluateInputViewShown()
    ): Boolean = when (skkPrefs.useSoftKey) {
        "on" -> true.also { SKKLog.d("software keyboard forced") }
        "off" -> false.also { SKKLog.d("software keyboard disabled") }
        else -> default
    }

    override fun onBindInput() {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
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
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        setInputView(onCreateInputView())
        readPrefs()
        updateInputViewShown()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        // candidatesView は inputViewShown に依存するはず
        val atLeastCandidatesAreShown = skkPrefs.useCandidatesView ||
                checkUseSoftKeyboard(super.onEvaluateInputViewShown())
        setCandidatesViewShown(atLeastCandidatesAreShown)
        return atLeastCandidatesAreShown
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        updateDimensions(newConfig)
        super.onConfigurationChanged(newConfig) // これが onInitializeInterface を呼ぶ
    }

    private fun updateDimensions(config: Configuration? = null) {
        val conf = config ?: resources.configuration
        mOrientation = conf.orientation
        val density = resources.displayMetrics.density
        mRootWidth = (conf.screenWidthDp * density + 0.5f).toInt()
        mScreenWidth = mRootWidth
        mScreenHeight = (conf.screenHeightDp * density + 0.5f).toInt()
        SKKLog.d("updateDimensions: legacy width=$mRootWidth height=$mScreenHeight")

        if (Build.VERSION.SDK_INT >= 34) {
            val manager = getSystemService(WindowManager::class.java)
            val metrics = manager.currentWindowMetrics
            mRootWidth = metrics.bounds.width()
            mScreenWidth = mRootWidth
            mScreenHeight = metrics.bounds.height()
            SKKLog.d("updateDimensions: modern width=$mRootWidth height=$mScreenHeight")

            mCutout = metrics.windowInsets.getInsetsIgnoringVisibility(
                InsetsType.systemBars() or InsetsType.displayCutout()
            )
            mInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
                InsetsType.systemBars() or InsetsType.displayCutout() or
                        if (skkPrefs.gestureInsets) InsetsType.systemGestures() else 0
            )
            SKKLog.d("updateDimensions: cutout=$mCutout (systemBars|displayCutout)")
            SKKLog.d("updateDimensions: insets=$mInsets (systemBars|displayCutout|systemGestures)")

            mRootWidth -= mInsets.run { left + right }
            mScreenHeight -= mInsets.run { top + bottom }
            SKKLog.d("updateDimensions: rootWidth=$mRootWidth screenHeight=$mScreenHeight")
        }
    }

    private fun createNightModeContext(context: Context, mode: String): Context {
        val uiModeFlag = when (mode) {
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            "light" -> Configuration.UI_MODE_NIGHT_NO
            else -> Configuration.UI_MODE_NIGHT_UNDEFINED
        }
        val config = Configuration(context.resources.configuration)
        config.uiMode = uiModeFlag or (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        return context
            .createDisplayContext(
                getSystemService(DisplayManager::class.java).getDisplay(DEFAULT_DISPLAY)
            )
            .createWindowContext(WindowManager.LayoutParams.TYPE_INPUT_METHOD, null)
            .createConfigurationContext(config) // 上書きするため最後である必要がある
    }

    private fun createInputView() {
        val context = createNightModeContext(applicationContext, skkPrefs.theme)
        if (skkPrefs.useInset) context.theme.applyStyle(R.style.ThemeOverlay_SKK_RoundedKey, true)

        mFlickJPInputView = FlickJPKeyboardView(context, null)
        mFlickJPInputView?.setService(this)
        mQwertyInputView = QwertyKeyboardView(context, null)
        mQwertyInputView?.setService(this)

        if (skkPrefs.useInset) ResourcesCompat
            .getDrawable(context.resources, R.drawable.key_bg_inset, context.theme)?.let {
                mFlickJPInputView?.setKeyBackground(it)
                mQwertyInputView?.setKeyBackground(it)
            }
        mCandidatesViewContainer.apply {
            background = ResourcesCompat
                .getDrawable(context.resources, R.drawable.key_bg, context.theme)
            background.alpha = 0
            clipToOutline = skkPrefs.useInset
        }

        readPrefsForInputView()
    }

    private val backAnimationCallback = if (Build.VERSION.SDK_INT >= 34) {
        OnBackAnimationCallback {
            if (mInputView?.handleBack() != true && !mEngine.handleCancel(false))
                requestHideSelf(0)
        }
    } else Unit

    override fun onWindowShown() = backAnimationCallback.let {
        if (skkPrefs.gestureInsets && Build.VERSION.SDK_INT >= 34 && it is OnBackAnimationCallback)
            window.onBackInvokedDispatcher
                .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
    }

    override fun onWindowHidden() = backAnimationCallback.let {
        if (skkPrefs.gestureInsets && Build.VERSION.SDK_INT >= 34 && it is OnBackAnimationCallback)
            window.onBackInvokedDispatcher
                .unregisterOnBackInvokedCallback(it)
    }

    override fun requestHideSelf(flags: Int) {
        onWindowHidden()
        super.requestHideSelf(flags)
    }

    override fun onCreateInputView(): View {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")

        val context = createNightModeContext(applicationContext, skkPrefs.theme)
        if (skkPrefs.useInset) context.theme.applyStyle(R.style.ThemeOverlay_SKK_RoundedKey, true)
        mBinding = InputViewBinding.inflate(LayoutInflater.from(context))

        mCandidatesViewContainer.apply {
            setService(this@SKKService)
            initViews()
            setSize(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    skkPrefs.candidatesSize.toFloat(),
                    context.resources.displayMetrics
                ).toInt()
            )
            setAlpha(skkPrefs.inactiveAlpha)
            visibility = if (skkPrefs.useCandidatesView) View.VISIBLE else View.GONE
        }
        mCandidatesView.apply {
            setService(this@SKKService)
            setContainer(mCandidatesViewContainer)
        }

        val wasFlick = mInputView?.equals(mFlickJPInputView) == true
        createInputView()

        SKKLog.d("onCreateInputView: wasFlick=$wasFlick engineState=${engineState.name}")
        val keyboardView = (when (engineState) {
            SKKASCIIState, SKKZenkakuState, SKKAbbrevState -> if (skkPrefs.softKeyboardType == "flick") mFlickJPInputView else mQwertyInputView
            else -> when {
                skkPrefs.softKeyboardType == "qwerty" -> mQwertyInputView
                else -> mFlickJPInputView
            }
        })!!.setKeyState(engineState)

        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        mBinding.keyboardContainer.addView(keyboardView)
        mInputView = keyboardView

        return keyboardView
    }

    /**
     * This is the main point where we do our initialization of the
     * input method to begin operating on an application. At this
     * point we have been bound to the client, and are now receiving
     * all the detailed information about the target of our edits.
     */
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        /* 以前ここで requestHideSelf(0) しないとホーム画面に candidatesView が残ったり
         * 色々と問題があったはずなのだが、今は何もせずに return しても大丈夫みたい
         * 以前はホーム画面で即座に検索できるよう TYPE_NULL の不可視フィールドがあったのかも
         * さらに mPendingInput が暴発しないために return していたが
         * 音声入力も mushroom も普通に使えるのでこれも不要のようだ
         * 以上の理由から、将来的にはこの if をまるごと消しても問題ないと思われる
         */
        if (attribute.inputType == InputType.TYPE_NULL) {
            requestHideSelf(0)
            return
        }
        SKKLog.d("onStartInput pkg=${attribute.packageName} type=${attribute.inputType} restarting=$restarting")
        super.onStartInput(attribute, restarting)

        mPendingInput?.let { pending ->
            SKKLog.d("commiting pending input: $pending")
            currentInputConnection.commitText(pending, 1)
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
            InputType.TYPE_CLASS_TEXT if isInPref -> "qwerty" // ハードキーボード設定
            InputType.TYPE_CLASS_TEXT -> {
                val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
                when (variation) {
                    InputType.TYPE_TEXT_VARIATION_URI,
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> skkPrefs.typeURI

                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> skkPrefs.typePassword

                    else -> skkPrefs.typeText
                }
            }
            // InputType.TYPE_CLASS_DATETIME -> "ignore" // ウェブブラウザが使ってないタイプなので無視
            else -> "ignore"
        }

        if (keyboardType == "ignore") {
            if (restarting) {
                if (mEngine.mComposingText.isNotEmpty()) {
                    val composingText = mEngine.mComposingText.toString()
                    SKKLog.d("restarting: setComposingText(${mEngine.mComposingText})")
                    currentInputConnection.apply {
                        getTextBeforeCursor(composingText.length, 0).let {
                            if (it == composingText) {
                                deleteSurroundingText(it.length, 0)
                            } // 回転などでテキストが確定されていることがあるので消す
                        }
                        // SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length
                        // と言われないため empty なら最初から SpannableString にしておく
                        setComposingText(mEngine.mComposingText.ifEmpty { SpannableString("") }, 1)
                        // candidatesView も復元したいけど無理っぽい
                    }
                }
            } else {
                mEngine.resetOnStartInput()
            }

            restorePrevStates()
        } else {
            // keyboardType で強制された状態を一時的なものとして扱うため以前の状態を覚えておく
            if (mPrevStates == null) {
                mPrevStates = PrevStates(this, mInputView, engineState)
            } else {
                // 未使用の prev を上書きせず保持するが、keyboard だけは戻しておく
                mPrevStates?.let { prev ->
                    prev.keyboard?.let { kb ->
                        prev.inputView?.keyboard = kb
                    }
                }
            }

            mEngine.resetOnStartInput()

            when (keyboardType) {
                // 日本語にする
                "flick-jp" -> {
                    if (
                        !engineState.isJapanese
                        || (mInputView !== mFlickJPInputView)
                        || (mInputView?.equals(mFlickJPInputView) == true
                                && mInputView!!.keyboard !== mFlickJPInputView!!.mJPKeyboard)
                    ) {
                        mEngine.changeState(SKKHiraganaState)
                    }
                }

                // テンキーにする
                "flick-num" -> {
                    mEngine.handleASCIIKey()
                    mFlickJPInputView?.let {
                        setInputView(it) // Flick のテンキーを強制
                        it.mNumKeyboard?.let { kb -> it.keyboard = kb }
                    }
                }

                // 英字にする
                "qwerty" -> {
                    mEngine.handleASCIIKey()
                    if (mInputView?.equals(mQwertyInputView) == true) {
                        mQwertyInputView!!.keyboard = mQwertyInputView!!.mLatinKeyboard
                    }
                }

                // 英数記号にする
                "symbols" -> {
                    mEngine.handleASCIIKey()
                    if (mInputView?.equals(mQwertyInputView) == true) {
                        mQwertyInputView!!.keyboard = mQwertyInputView!!.mSymbolsKeyboard
                    }
                }

                else -> throw Exception("invalid keyboardType: $keyboardType")
            }

            // 変更していないように見える場合は prev を保持しない
            mPrevStates?.let { prev ->
                if (
                    prev.inputView?.equals(mInputView) == true // null 不可
                    && prev.keyboard?.equals(mInputView?.keyboard) != false // null 可
                    && prev.state == engineState
                ) {
                    mPrevStates = null
                }
            }
        }
    }

    internal fun restorePrevStates() {
        mPrevStates?.let { prev ->
            if (prev.state != engineState) {
                mEngine.changeState(prev.state)
            }
            prev.inputView?.let {
                prev.keyboard?.let { kb ->
                    it.keyboard = kb
                }
                if (it != mInputView) {
                    setInputView(it)
                }
            }
        }
        mPrevStates = null
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        super.onStartInputView(editorInfo, restarting)

        // シフト等の状態を同期
        listOf(mFlickJPInputView, mQwertyInputView)
            .forEach { it?.setKeyState(engineState) }

        showStatusIcon(engineState.icon)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        mEngine.commitComposing()
        super.onFinishInputView(finishingInput)
        hideStatusIcon()
        clearCandidatesView()
        onWindowHidden()
    }

    /**
     * This is called when the user is done editing a field.  We can use
     * this to reset our state.
     */
    override fun onFinishInput() {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        mEngine.commitComposing()
        restorePrevStates()
        super.onFinishInput()
        hideStatusIcon()
        clearCandidatesView()
        mInputStarted = false

        mQwertyInputView?.handleBack()
        onWindowHidden()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        super.onUnbind(intent)

        // このあと onDestroy() が呼ばれないことがあるので強制終了しておく
        // onDestroy() なしだと、次回起動がエラーで起動し直しになる
        MainScope().launch { stopSelf() }

        return false // rebind 不可能であることを示す
    }

    override fun onDestroy() {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        if (instance == null) {
            SKKLog.d("skip onDestroy(): instance is null")
            return
        }

        runBlocking(Dispatchers.IO) { mEngine.close() }
        mSpeechRecognizer.destroy()
        instance = null

        super.onDestroy()
    }

    // never use fullscreen mode
    override fun onEvaluateFullscreenMode() = false

    override fun onComputeInsets(outInsets: Insets?) {
        //SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        super.onComputeInsets(outInsets)
        if (outInsets == null || mInputView == null) return
        outInsets.apply {
            val variableHeight = mCandidatesViewContainer.let { it.height - it.minHeight }
            contentTopInsets = when {
                isFloating() -> mBinding.root.height // 高さをすべて無効にして floating を実現
                skkPrefs.candidatesMinHeight -> visibleTopInsets + variableHeight
                else -> visibleTopInsets // 変動する CandidatesView の高さも確保
            }
            //SKKLog.d("outInsets contentTop=$contentTopInsets visibleTop=$visibleTopInsets")
            touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            touchableRegion.set(
                if (isFloating()) leftOffset else mInsets.left, 0,
                leftOffset + mInputView!!.keyboard.width, mBinding.root.height
            )
        }
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        SKKLog.d("lifecycle: ${Thread.currentThread().stackTrace[2].methodName}")
        super.onUpdateEditorToolType(toolType)
        completeASCII()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        // 文字列の途中をタップした場合などに補完する (state == ASCII チェックは内部でしている)
        completeASCII()
    }

    /**
     * Use this to monitor key events being delivered to the
     * application. We get first crack at them, and can either resume
     * them or let them continue to the app.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // SandS: ASCII モードでのスペースアップ処理
        if (!mStickyShift && mSandS && skkPrefs.sandSInAscii &&
            mEngine.state is SKKASCIIState && keyCode == KeyEvent.KEYCODE_SPACE
        ) {
            mSpacePressed = false
            if (!mSandSUsed) currentInputConnection?.commitText(" ", 1) else mSandSUsed = false
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT if mStickyShift -> return true.also { mShiftKey.release() }
            KeyEvent.KEYCODE_SHIFT_RIGHT if mStickyShift -> return true.also { mShiftKey.release() }

            KeyEvent.KEYCODE_SPACE if !mStickyShift && mSandS -> {
                mSpacePressed = false
                if (!mSandSUsed) processKey(' ') else mSandSUsed = false
                return true
            }

            KeyEvent.KEYCODE_SPACE if isEnterUsed -> return true.also { isEnterUsed = false }
            KeyEvent.KEYCODE_ENTER if isEnterUsed -> return true.also { isEnterUsed = false }

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
        if (handleKey(event)) return true

        return super.onKeyDown(keyCode, event)
    }

    internal fun handleKey(ev: KeyEvent? = null, encKey: Int? = null): Boolean {
        val engineState = mEngine.state
        val (event, encodedKey) = when {
            ev != null -> ev to encodeKey(ev)
            encKey == null -> return false
            else -> {
                val now = System.currentTimeMillis()
                KeyEvent(
                    now, now, KeyEvent.ACTION_DOWN,
                    encKey.charCode, 0, encKey.metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0
                ) to encKey
            }
        }

        if (isInPref) {
            currentInputConnection?.sendKeyEvent(event)
            return true
        }
        val keyCode = event.keyCode

        when (encodedKey) {
            skkPrefs.kanaKey -> return true.also { mEngine.handleKanaKey() }
            skkPrefs.cancelKey -> return mEngine.handleCancel()

            // Emacs 風ナビゲーションキー（設定が 0 の場合は encodedKey と合致し得ない）
            skkPrefs.navLineStartKey -> KeyEvent.KEYCODE_MOVE_HOME
            skkPrefs.navLineEndKey -> KeyEvent.KEYCODE_MOVE_END
            skkPrefs.navForwardKey -> KeyEvent.KEYCODE_DPAD_RIGHT
            skkPrefs.navBackwardKey -> KeyEvent.KEYCODE_DPAD_LEFT
            else -> null
        }?.let { navKey ->
            return when {
                engineState.isTransient || mEngine.mRegister.isOngoing ->
                    true.also { handleDpad(navKey) }

                engineState is SKKASCIIState && !skkPrefs.emacsNavInAscii -> false

                else -> true.also { sendDownUpKeyEvents(navKey) }
            }
        }

        // SandS: ASCII モードでのスペース＆修飾処理（早期リターンより前に置く）
        if (!mStickyShift && mSandS && skkPrefs.sandSInAscii &&
            engineState is SKKASCIIState && !mEngine.mRegister.isOngoing
        ) when {
            keyCode == KeyEvent.KEYCODE_SPACE -> {
                mSpacePressed = true
                return true
            }

            mSpacePressed -> {
                val shiftedEvent = event.run {
                    KeyEvent(
                        downTime, eventTime, action, event.keyCode, repeatCount,
                        metaState or KeyEvent.META_SHIFT_ON, deviceId, scanCode, flags, source
                    )
                }
                mSandSUsed = true
                val result = super.onKeyDown(keyCode, shiftedEvent)
                completeASCII()
                return result
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_TAB if engineState.canComplete -> {
                val isShifted = when {
                    mStickyShift -> mShiftKey.useState() != 0
                    mSandS && mSpacePressed -> true.also { mSandSUsed = true }
                    else -> event.isShiftPressed
                }
                mEngine.mCandidates.cycleCompletionCursor(!isShifted)
                return true
            }

            KeyEvent.KEYCODE_SHIFT_LEFT if mStickyShift -> return true.also { mShiftKey.press() }
            KeyEvent.KEYCODE_SHIFT_RIGHT if mStickyShift -> return true.also { mShiftKey.press() }

            KeyEvent.KEYCODE_BACK -> if (mEngine.handleCancel(false)) return true

            KeyEvent.KEYCODE_DEL -> if (handleBackspace()) return true

            KeyEvent.KEYCODE_FORWARD_DEL -> if (handleForwardDel()) return true

            KeyEvent.KEYCODE_ENTER -> if (handleEnter()) return true

            KeyEvent.KEYCODE_SPACE -> {
                if (!mStickyShift && mSandS) mSpacePressed = true
                else processKey(' ')
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END ->
                if (handleDpad(keyCode)) return true
        }

        if (KeyEvent.isModifierKey(event.keyCode)) return false

        // シフトを内部状態で更新したキーイベントを作って使う
        val newEvent = event.run {
            KeyEvent(
                downTime, eventTime, action, keyCode, repeatCount,
                metaState or when {
                    mStickyShift -> mShiftKey.useState()
                    mSandS && mSpacePressed -> KeyEvent.META_SHIFT_ON.also { mSandSUsed = true }
                    else -> 0
                }, deviceId, scanCode, flags, source
            )
        }
        val k = encodeKey(newEvent)

        // 割り当てのない特殊キーは無視 (rules で指定できる場合は別途考える必要があるか)
        if (k and (RAW_KEYCODE or META_PRESSED or CTRL_PRESSED or ALT_PRESSED) != 0
            && !skkPrefs.isModeKey(k)
        ) return false

        // shift 単体なら無視 (sticky-shift は keyup で見る)
        // meta/ctrl/alt 単体は、モード変更に割り当てられないのでここを通らないはず
        if (k.lowerCode == 0) return false

        if (currentInputConnection == null) return false

        processKey(k)

        return true
    }

    internal fun processKey(code: Int) {
        if (!skkPrefs.isModeKey(code) && (code and (CTRL_PRESSED or ALT_PRESSED) != 0)) {
            val downTime = System.currentTimeMillis()
            val keyCode = code.charCode
            val metaState = code.metaState
            currentInputConnection.sendKeyEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            )
            currentInputConnection.sendKeyEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0, 0)
            )
            return
        }
        return mEngine.processKey(code)
    }

    fun processKey(char: Char) = mEngine.processKey(encodeKey(char.code))

    fun processKeyIn(state: SKKState, char: Char) = state.processKey(mEngine, encodeKey(char.code))

    fun handleKanaKey() = mEngine.handleKanaKey()

    fun handleCancel(): Boolean = mEngine.handleCancel()

    fun changeLastChar(type: String) = mEngine.changeLastChar(type)

    fun commitTextSKK(text: String) = mEngine.commitTextSKK(text)

    fun googleTransliterate() = mEngine.googleTransliterate()

    fun symbolCandidates() = mEngine.symbolCandidates()

    fun emojiCandidates() = mEngine.emojiCandidates()

    fun pickCandidatesViewManually(index: Int, unregister: Boolean = false) {
        val sequential = mInputView?.isShifted == true
        mEngine.pickCandidatesViewManually(index, unregister, sequential)
    }

    fun handleBackspace(): Boolean {
        if (mStickyShift) mShiftKey.useState()
        return mEngine.handleBackspace()
    }

    fun handleForwardDel(): Boolean {
        if (mStickyShift) mShiftKey.useState()
        return mEngine.handleForwardDel()
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
        SKKLog.d("handleDpad(${KeyEvent.keyCodeToString(keyCode)}) in ${mEngine.state.name}")
        if (mStickyShift) mShiftKey.useState()
        return mEngine.handleDpad(keyCode)
    }

    /**
     * Helper to send a key down / key up pair to the current editor.
     */
    fun keyDownUp(keyEventCode: Int) {
        val ic = currentInputConnection ?: return
        if (!skkPrefs.moveOverEdge) when (keyEventCode) {
            // 端でカーソル移動しようとすると閉じてしまうので回避
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) return
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return
        }
        sendDownUpKeyEvents(keyEventCode)
        completeASCII()
    }

    fun pressDel() {
        if (skkPrefs.useDel) return keyDownUp(KeyEvent.KEYCODE_DEL)

        // 一部環境では KeyEvent.KEYCODE_DEL がブロックされるので回避
        // 可能ならキーをエミュレートした方が諸々の面倒を見てくれるのでラク
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) {
            if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) return
            ic.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            ic.commitText("", 1)
        }
        completeASCII()
    }

    fun pressForwardDel() {
        if (skkPrefs.useDel) return keyDownUp(KeyEvent.KEYCODE_FORWARD_DEL)
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) {
            ic.deleteSurroundingTextInCodePoints(0, 1)
        } else {
            ic.commitText("", 1)
        }
        // 前方を消しても completeASCII に影響ないはず
    }

    fun pressSearch() {
        val ic = currentInputConnection ?: return
        ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
        completeASCII()
    }

    fun pressEnter() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo

        SKKLog.d("pressEnter(): NO_ENTER=${0 != (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)} label=${editorInfo.actionLabel} action=${editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION}")
        when {
            editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ->
                ic.commitText("\n", 1) // 古いAndroidでは keyDownUp(KeyEvent.KEYCODE_ENTER)
            editorInfo.actionLabel != null ->
                ic.performEditorAction(editorInfo.actionId) // よく知らない
            else ->
                ic.performEditorAction(editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        }
    }

    fun sendToMushroom() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val str = mEngine.prepareToMushroom(clip)

        val mushroom = Intent(this, SKKMushroom::class.java)
        mushroom.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        mushroom.putExtra(SKKMushroom.REPLACE_KEY, str)
        startActivity(mushroom)
    }

    fun pasteClip() {
        if (skkPrefs.forbidPaste) {
            val intent = Intent(applicationContext, SKKSettingsActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            notify(
                NOTIFY_ID_DETAILS,
                "貼り付け禁止",
                getText(R.string.error_pasting_forbidden).toString(),
                pendingIntent
            )
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val cs = cm.primaryClip?.getItemAt(0)?.text
        val clip = cs?.toString().orEmpty().ifEmpty {
            if (mEngine.mRegister.isOngoing) mEngine.mRegister.first()!!.key else ""
        }
        commitTextSKK(clip)
    }

    private fun notify(
        id: Int,
        title: String,
        text: String,
        intent: PendingIntent?
    ): Boolean {
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
                ) == PackageManager.PERMISSION_GRANTED
            ) {
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

    class PermissionRequestActivity : AppCompatActivity() {
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
            // mSpeechRecognizer.stopListening()
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
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        mSpeechRecognizer.startListening(intent)
        mFlickJPInputView?.let {
            it.keyboard.keys[2].on = true // 「声」キー
            it.invalidateKey(2)
            it.isEnabled = false
        }
        mIsRecording = true
    }

    fun completeASCII() = mEngine.mCandidates.completeASCII()

    private fun getTypeface(fontName: String) = when (fontName) {
        "sans_serif" -> android.graphics.Typeface.SANS_SERIF
        "serif" -> android.graphics.Typeface.SERIF
        "monospace" -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.DEFAULT
    }

    fun suspendCompletion() = mEngine.mCandidates.suspendCompletion()

    fun resumeCompletion() = mEngine.mCandidates.resumeCompletion()

    internal fun setCandidates(
        layout: CandidateLayout?,
        viewLines: Int = skkPrefs.candidatesNormalLines,
        index: Int = 0
    ) {
        mCandidatesViewContainer.apply {
            if (layout == null) {
                setAlpha(skkPrefs.inactiveAlpha)
                lines = if (skkPrefs.candidatesReserveLines) viewLines else 0
                mCandidatesView.setContents(null)
            } else {
                setAlpha(skkPrefs.activeAlpha)
                lines = viewLines
                mCandidatesView.setContents(layout, index)
            }
        }
    }

    internal fun setCandidatesCursor(index: Int) = mCandidatesView.setCursor(index)

    internal fun clearCandidatesView() = setCandidates(null)

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
        SKKLog.d("changeSoftKeyboard(${state.name})")
        // 長押しリピートの message が残っている可能性があるので止める
        for (kv in listOf(mFlickJPInputView, mQwertyInputView)) {
            kv?.stopRepeatKey(true)
        }

        setInputView(
            if (skkPrefs.softKeyboardType == "flick") {
                mFlickJPInputView?.setKeyState(state)
            } else when (state) {
                // 実際の engineState とは関係なく qwerty を使いたい場合もあるので
                // if-else せず無条件に qwerty を使う
                SKKASCIIState -> mQwertyInputView

                else if state.isJapanese ->
                    if (skkPrefs.softKeyboardType == "qwerty") mQwertyInputView
                    else mFlickJPInputView

                else if state.isTemporaryQwerty ->
                    if (skkPrefs.softKeyboardType == "flick") mFlickJPInputView
                    else mQwertyInputView

                else -> throw Exception("invalid state: $state")
            }?.setKeyState(engineState) ?: return
            // state==ASCII は Qwerty にするためだけの場合があるので
            // setKeyState(state) を使わないで setKeyState(engineState)
            // 他の場合は基本的に state==engineState のはずなので、どちらでも構わない
        )
    }

    fun changeToFlick() {
        if (!engineState.changeToFlick(mEngine)) {
            val flick = mFlickJPInputView
            setInputView(flick?.setKeyState(engineState))
        }
    }

    override fun setInputView(view: View?) {
        // view が null のときはここをスキップして再描画だけする (ドラッグで位置調整のとき使う)
        (view as? KeyboardView)?.let { inputView ->
            SKKLog.d("setInputView(${view.javaClass.simpleName})")
            mInputView = inputView // keyboardWidth と keyboardHeight で参照されるので早く代入
            inputView.keyboard.resize(keyboardWidth(), keyboardHeight())
            mBinding.keyboardContainer.apply {
                if (getChildAt(0) != inputView) {
                    removeAllViews()
                    (inputView.parent as? ViewGroup)?.removeView(inputView)
                    addView(inputView)
                }
            }
            super.setInputView(mBinding.root)
            computeLeftOffset()
        }

        // |    insets.left    |   rootWidth   |    insets.right    |
        // |cutout.left|padding|   rootWidth   |padding|cutout.right|
        // |cutout.left|leftOffset|keyboard|rightOffset|cutout.right|
        val padding = AGInsets.subtract(mInsets, mCutout)
        val rightOffset = mInsets.left + mRootWidth + mInsets.right -
                (mCutout.left + leftOffset + mInputView!!.keyboard.width + mCutout.right)
        (mBinding.container.layoutParams as ViewGroup.MarginLayoutParams).apply {
            leftMargin = if (isFloating()) leftOffset else padding.left
            rightMargin = if (isFloating()) rightOffset else padding.right
            mBinding.container.layoutParams = this
        }

        mBinding.keyboardContainer.setPadding(
            if (isFloating()) 0 else leftOffset - padding.left, 0,
            if (isFloating()) 0 else rightOffset - padding.right, bottomOffset
        )

        mCandidatesViewContainer.setSize(-1)

        val opaque = if (isFloating()) mBinding.container else mBinding.root
        val trans = if (isFloating()) mBinding.root else mBinding.container
        trans.background = null
        val uri = skkPrefs.backgroundImage ?: run { opaque.background = null }
        if ((opaque.background as? CroppedDrawable)?.uri == uri) return
        MainScope().launch {
            opaque.background = skkPrefs.backgroundImage?.let { uriString ->
                withContext(Dispatchers.IO) {
                    val maxDimension = max(mScreenWidth, mScreenHeight)
                    CroppedDrawable.decode(
                        contentResolver, uriString.toUri(),
                        maxDimension, maxDimension
                    )?.let { bitmap ->
                        CroppedDrawable(
                            resources, bitmap, uriString,
                            getFullHeight = {
                                keyboardHeight() + mCandidatesViewContainer.maxHeight + mInsets.bottom
                            },
                            getVarHeight = {
                                if (skkPrefs.candidatesMinHeight)
                                    mCandidatesViewContainer.run { (height - minHeight) }
                                else 0
                            }
                        )
                    }
                }
            }
        }
    }

    private fun keyboardWidth(view: KeyboardView? = mInputView): Int {
        if (!checkUseSoftKeyboard()) return mRootWidth
        val minWidth = resources.getDimensionPixelSize(R.dimen.keyboard_minimum_width)
        val baseWidth = when (mOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                val w = skkPrefs.keyWidthPort
                if (w < minWidth) mRootWidth else w.coerceIn(minWidth, mRootWidth)
            }

            Configuration.ORIENTATION_LANDSCAPE -> {
                val w = skkPrefs.keyWidthLand
                if (w < minWidth) mRootWidth * 3 / 10 else w.coerceIn(minWidth, mRootWidth)
            }

            else -> mRootWidth
        }
        return (baseWidth * (if (isFlick(view)) 100 else skkPrefs.keyWidthQwertyZoom) / 100)
            .coerceAtMost(mRootWidth)
    }

    private fun keyboardHeight() = if (!checkUseSoftKeyboard()) 0 else
        mScreenHeight * when (mOrientation) {
            Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyHeightPort
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyHeightLand
            else -> 30
        } / 100

    private fun computeLeftOffset() {
        val center = when (mOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyCenterLand
            else -> skkPrefs.keyCenterPort
        }
        leftOffset = mInsets.left - mCutout.left +
                (mRootWidth * center - keyboardWidth() / 2 + 0.5f).toInt()
                    .coerceIn(0, mRootWidth - keyboardWidth())
    }

    private fun isFloating(): Boolean = mRootWidth - keyboardWidth() > mScreenHeight
//            && when (mOrientation) {
//                Configuration.ORIENTATION_PORTRAIT -> skkPrefs.keyHeightPort > 50
//                Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyHeightLand > 30
//                else -> false // 30 > 30..50
//            }

    override fun showStatusIcon(iconRes: Int) {
        if ((mInputStarted && (!checkUseSoftKeyboard() || skkPrefs.showStatusIcon))
            && iconRes != 0
        ) {
            super.showStatusIcon(iconRes)
        }
    }

    internal fun getStore(filePath: String): SKKStore? =
        if (!::mEngine.isInitialized) null
        else (mEngine.mDictList + mAsciiDict + mEmojiDict + mSymbolDict)
            .find { it.mFilePath == filePath }?.mStore

    @Suppress("SameReturnValue")
    private fun ping() = true

    companion object {
        private var instance: SKKService? = null
        internal fun isRunning(): Boolean {
            return try {
                instance?.ping() ?: false
            } catch (_: NullPointerException) {
                false
            }
        }

        internal fun getStore(filePath: String) = instance?.getStore(filePath)

        internal const val KEY_COMMAND = "jp.deadend.noname.skk.KEY_COMMAND"
        internal const val COMMAND_READ_PREFS = "jp.deadend.noname.skk.COMMAND_READ_PREFS"
        internal const val COMMAND_RELOAD_DICT = "jp.deadend.noname.skk.COMMAND_RELOAD_DICT"
        internal const val COMMAND_MUSHROOM = "jp.deadend.noname.skk.COMMAND_MUSHROOM"
        internal const val EDITOR_OPTION_PREF = "jp.deadend.noname.skk.OPTION_PREF"
        private const val CHANNEL_ID = "skk_notification"
        private const val CHANNEL_NAME = "SKK"
        private const val NOTIFY_ID_ERROR_DICT = 1
        private const val NOTIFY_ID_DETAILS = 2
    }
}
