package jp.deadend.noname.skk

import android.content.Context
import android.util.AttributeSet
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import jp.deadend.noname.skk.databinding.PopupFlickguideBinding
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKEngine
import jp.deadend.noname.skk.engine.SKKZenkakuState
import java.util.EnumSet

class FlickJPKeyboardView(context: Context, attrs: AttributeSet?) : KeyboardView(context, attrs), KeyboardView.OnKeyboardActionListener {
    private var mFlickSensitivitySquared = 100
    private var mLastPressedKey = KEYCODE_FLICK_JP_NONE
    private var mFlickState = EnumSet.of(FlickState.NONE)
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private var mCurrentPopupLabels = POPUP_LABELS_NULL

    private var mUsePopup = true
    private var mFixedPopup = false
    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mPopupOffset = intArrayOf(0, 0)
    private val mFixedPopupPos = intArrayOf(0, 0)

    val mJPKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_jp, mService.mScreenWidth, mService.mScreenHeight)
    }
    val mNumKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_number, mService.mScreenWidth, mService.mScreenHeight)
    }
    private val mVoiceKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_voice, mService.mScreenWidth, mService.mScreenHeight)
    }

    private var mKutoutenLabel = "？\n． ，！\n…"
    private val mKutoutenKey: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_CHAR_TEN)) { "BUG: no kutoten key" }
    }
    private val mSpaceKey: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_SPACE)) { "BUG: no space key" }
    }
    private val mQwertyKey: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_TOQWERTY)) { "BUG: no qwerty key" }
    }
    private val mShiftKeyJP: Keyboard.Key by lazy { mJPKeyboard.keys[0] }
    private val mShiftKeyNum: Keyboard.Key by lazy { mNumKeyboard.keys[0] }
    private val mShiftKeyVoice: Keyboard.Key by lazy { mVoiceKeyboard.keys[0] }
    private val mKanaKeyJP: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_MOJI)) { "BUG: no moji key" }
    }
    private val mKanaKeyNum: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mNumKeyboard, KEYCODE_FLICK_JP_TOKANA)) { "BUG: no kana key in num" }
    }
    private val mKanaKeyVoice: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mVoiceKeyboard, KEYCODE_FLICK_JP_TOKANA)) { "BUG: no kana key in voice" }
    }

    //フリックガイドTextView用
    private val mFlickGuideLabelList = SparseArray<Array<String>>()

    init {
        val a = mFlickGuideLabelList
        a.append(KEYCODE_FLICK_JP_CHAR_A, arrayOf("あ", "い", "う", "え", "お", "小", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_KA, arrayOf("か", "き", "く", "け", "こ", "", "゛"))
        a.append(KEYCODE_FLICK_JP_CHAR_SA, arrayOf("さ", "し", "す", "せ", "そ", "", "゛"))
        a.append(KEYCODE_FLICK_JP_CHAR_TA, arrayOf("た", "ち", "つ", "て", "と", "", "゛"))
        a.append(KEYCODE_FLICK_JP_CHAR_NA, arrayOf("な", "に", "ぬ", "ね", "の", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_HA, arrayOf("は", "ひ", "ふ", "へ", "ほ", "゜", "゛"))
        a.append(KEYCODE_FLICK_JP_CHAR_MA, arrayOf("ま", "み", "む", "め", "も", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_YA, arrayOf("や", "（", "ゆ", "）", "よ", "小", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_RA, arrayOf("ら", "り", "る", "れ", "ろ", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_WA, arrayOf("わ", "を", "ん", "ー", "～", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("、", "。", "？", "！", "…", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED, arrayOf("　", "（", "「", "）", "」", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_TEN_NUM, arrayOf("，", "．", "－", "：", "／", "", ""))
        a.append(KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT, arrayOf("＃", "￥", "＋", "＄", "＊", "", ""))
        a.append(KEYCODE_FLICK_JP_MOJI, arrayOf("カナ", "：", "10", "＞", "声", "", ""))
        a.append(KEYCODE_FLICK_JP_TOQWERTY, arrayOf("abc", "", "全角", "", "qwe", "", ""))
    }

    override fun setService(service: SKKService) {
        super.setService(service)
        onKeyboardActionListener = this
        isPreviewEnabled = false
        setBackgroundColor(0x00000000)

        keyboard = mJPKeyboard
    }

    private fun setShiftPosition() {
        val defaultShiftKeys = arrayOf(mShiftKeyJP, mShiftKeyNum, mShiftKeyVoice)
        val defaultKanaKeys = arrayOf(mKanaKeyJP, mKanaKeyNum, mKanaKeyVoice)
        val (shiftKeys, kanaKeys) = if (!skkPrefs.changeShift) {
            defaultShiftKeys to defaultKanaKeys
        } else {
            defaultKanaKeys to defaultShiftKeys
        }

        for (shiftKey in shiftKeys) {
            shiftKey.codes[0] = 0
            shiftKey.label = ""
            shiftKey.icon = null
        }
        shiftKeys[0].codes[0] = Keyboard.KEYCODE_SHIFT
        shiftKeys[0].icon = ResourcesCompat.getDrawable(
            resources, R.drawable.ic_keyboard_shift, null
        )?.also {
            it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
        }

        for (kanaKey in kanaKeys) {
            kanaKey.codes[0] = KEYCODE_FLICK_JP_TOKANA
            kanaKey.label = "かな"
            kanaKey.icon = null
        }
        kanaKeys[0].codes[0] = KEYCODE_FLICK_JP_MOJI
        kanaKeys[0].label = if (mService.isHiragana) "10\n：カナ＞\n声" else "10\n：かな＞\n声"

        for (keyboard in arrayOf(mJPKeyboard, mNumKeyboard, mVoiceKeyboard)) {
            keyboard.reloadShiftKeys()
        }
    }

    internal fun setHiraganaMode(): FlickJPKeyboardView {
        mService.isHiragana = true
        for (key in keyboard.keys) {
            when (key.codes[0]) {
                KEYCODE_FLICK_JP_CHAR_A  -> key.label = "あ"
                KEYCODE_FLICK_JP_CHAR_KA -> key.label = "か"
                KEYCODE_FLICK_JP_CHAR_SA -> key.label = "さ"
                KEYCODE_FLICK_JP_CHAR_TA -> key.label = "た"
                KEYCODE_FLICK_JP_CHAR_NA -> key.label = "な"
                KEYCODE_FLICK_JP_CHAR_HA -> key.label = "は"
                KEYCODE_FLICK_JP_CHAR_MA -> key.label = "ま"
                KEYCODE_FLICK_JP_CHAR_YA -> key.label = "や"
                KEYCODE_FLICK_JP_CHAR_RA -> key.label = "ら"
                KEYCODE_FLICK_JP_CHAR_WA -> key.label = "わ"
                KEYCODE_FLICK_JP_MOJI    -> key.label = "10\n：カナ＞\n声"
            }
        }
        invalidateAllKeys()
        return this
    }

    internal fun setKatakanaMode(): FlickJPKeyboardView {
        mService.isHiragana = false
        for (key in keyboard.keys) {
            when (key.codes[0]) {
                KEYCODE_FLICK_JP_CHAR_A  -> key.label = "ア"
                KEYCODE_FLICK_JP_CHAR_KA -> key.label = "カ"
                KEYCODE_FLICK_JP_CHAR_SA -> key.label = "サ"
                KEYCODE_FLICK_JP_CHAR_TA -> key.label = "タ"
                KEYCODE_FLICK_JP_CHAR_NA -> key.label = "ナ"
                KEYCODE_FLICK_JP_CHAR_HA -> key.label = "ハ"
                KEYCODE_FLICK_JP_CHAR_MA -> key.label = "マ"
                KEYCODE_FLICK_JP_CHAR_YA -> key.label = "ヤ"
                KEYCODE_FLICK_JP_CHAR_RA -> key.label = "ラ"
                KEYCODE_FLICK_JP_CHAR_WA -> key.label = "ワ"
                KEYCODE_FLICK_JP_MOJI    -> key.label = "10\n：かな＞\n声"
            }
        }
        invalidateAllKeys()
        return this
    }

    private fun findKeyByCode(keyboard: Keyboard, code: Int) =
            keyboard.keys.find { it.codes[0] == code }

    private fun onSetShifted(isShifted: Boolean) {
        if (isShifted) {
            mKutoutenKey.codes[0] = KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED
            mKutoutenKey.label = "「\n（　□　）\n」"
            mSpaceKey.label = "設定"
            mQwertyKey.label = "abbr"
        } else {
            mKutoutenKey.codes[0] = KEYCODE_FLICK_JP_CHAR_TEN
            mKutoutenKey.label = mKutoutenLabel
            mSpaceKey.label = ""
            mQwertyKey.label = "ＡＢＣ\nabc\nqwerty"
        }
    }

    internal fun setRegisterMode(isRegistering: Boolean) {
        if (isRegistering) {
            var key = findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_LEFT)
            if (key != null) {
                key.codes[0] = KEYCODE_FLICK_JP_PASTE
                key.label = "貼り付け"
            }
            key = findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_RIGHT)
            if (key != null) {
                key.codes[0] = KEYCODE_FLICK_JP_GOOGLE
                key.label = "Google\n変換"
            }
        } else {
            var key = findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_PASTE)
            if (key != null) {
                key.codes[0] = KEYCODE_FLICK_JP_LEFT
                key.label = ""
            }
            key = findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_GOOGLE)
            if (key != null) {
                key.codes[0] = KEYCODE_FLICK_JP_RIGHT
                key.label = ""
            }
        }
        invalidateAllKeys()
    }

    internal fun prepareNewKeyboard(context: Context, widthPixel: Int, heightPixel: Int) {
        mJPKeyboard.resize(widthPixel, heightPixel)
        mNumKeyboard.resize(widthPixel, heightPixel)
        mVoiceKeyboard.resize(widthPixel, heightPixel)
        keyboard = mJPKeyboard
        invalidateAllKeys()

        readPrefs(context)
    }

    private fun readPrefs(context: Context) {
        // フリック感度
        val density = context.resources.displayMetrics.density
        val sensitivity = when (skkPrefs.flickSensitivity) {
            "low"  -> (36 * density + 0.5f).toInt()
            "high" -> (12 * density + 0.5f).toInt()
            else   -> (24 * density + 0.5f).toInt()
        }
        mFlickSensitivitySquared = sensitivity * sensitivity
        // シフトかな交換
        setShiftPosition()
        // 句読点
        when (skkPrefs.kutoutenType) {
            "en" -> {
                mKutoutenLabel = "？\n． ，！\n…"
                mFlickGuideLabelList.put(
                        KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("，", "．", "？", "！", "…", "", "")
                )
            }
            "jp_en" -> {
                mKutoutenLabel = "？\n。 ，！\n…"
                mFlickGuideLabelList.put(
                        KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("，", "。", "？", "！", "…", "", "")
                )
            }
            else -> {
                mKutoutenLabel = "？\n。 、！\n…"
                mFlickGuideLabelList.put(
                        KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("、", "。", "？", "！", "…", "", "")
                )
            }
        }
        mKutoutenKey.label = mKutoutenLabel
        when {
            skkPrefs.useSoftCancelKey -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)?.label = "小\n └゛CXL └゜\n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("CXL", "゛", "小", "゜", "▽", "", "")
                )
            }
            skkPrefs.useSoftTransKey -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)?.label = "CXL\n└゛└゜\n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("└゛└゜", "゛", "CXL", "゜", "▽", "", "")
                )
            }
            else -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)?.label = "CXL\n └゛小 └゜\n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("小", "゛", "CXL", "゜", "▽", "", "")
                )
            }
        }
        // ポップアップ
        mUsePopup = skkPrefs.usePopup
        if (mUsePopup) {
            mFixedPopup = skkPrefs.useFixedPopup
            if (mPopup == null) {
                val popup = createPopupGuide(context)
                mPopup = popup
                val binding = PopupFlickguideBinding.bind(popup.contentView)
                mPopupTextView = arrayOf(
                        binding.labelA,
                        binding.labelI,
                        binding.labelU,
                        binding.labelE,
                        binding.labelO,
                        binding.labelLeftA,
                        binding.labelRightA,
                        binding.labelLeftI,
                        binding.labelRightI,
                        binding.labelLeftU,
                        binding.labelRightU,
                        binding.labelLeftE,
                        binding.labelRightE,
                        binding.labelLeftO,
                        binding.labelRightO
                )
            }
        }
    }

    private fun createPopupGuide(context: Context): PopupWindow {
        val view = inflate(context, R.layout.popup_flickguide, null)

        val scale = getContext().resources.displayMetrics.density
        val size = (mPopupSize * scale + 0.5f).toInt()

        val popup = PopupWindow(view, size, size)
        //~ popup.setWindowLayoutMode(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        popup.animationStyle = 0

        return popup
    }

    private fun setupPopupTextView() {
        if (!mUsePopup) return

        val labels = checkNotNull(mPopupTextView) { "BUG: popup labels are null!!" }
        labels.forEach {
            it.text = ""
            it.setBackgroundResource(R.drawable.popup_label)
        }
        val activeLabel = when {
            mFlickState.contains(FlickState.NONE) -> {
                labels[0].text = mCurrentPopupLabels[0]
                if (!isCurve(mFlickState)) {
                    labels[1].text = mCurrentPopupLabels[1]
                    labels[2].text = mCurrentPopupLabels[2]
                    labels[3].text = mCurrentPopupLabels[3]
                    labels[4].text = mCurrentPopupLabels[4]
                }
                labels[5].text = mCurrentPopupLabels[5]
                labels[6].text = mCurrentPopupLabels[6]
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_WA) {
                    // 例外：小さい「ゎ」
                    labels[5].text = "小"
                }
                when {
                    isLeftCurve(mFlickState) -> 5
                    isRightCurve(mFlickState) -> 6
                    else -> 0
                }
            }
            mFlickState.contains(FlickState.LEFT) -> {
                if (!isCurve(mFlickState)) {
                    labels[0].text = mCurrentPopupLabels[0]
                }
                labels[1].text = mCurrentPopupLabels[1]
                labels[7].text = mCurrentPopupLabels[5]
                labels[8].text = mCurrentPopupLabels[6]
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_YA) {
                    // 例外：括弧
                    labels[7].text = "「"
                    labels[8].text = "『"
                }
                when {
                    isLeftCurve(mFlickState) -> 7
                    isRightCurve(mFlickState) -> 8
                    else -> 1
                }
            }
            mFlickState.contains(FlickState.UP) -> {
                if (!isCurve(mFlickState)) {
                    labels[0].text = mCurrentPopupLabels[0]
                }
                labels[2].text  = mCurrentPopupLabels[2]
                labels[9].text  = mCurrentPopupLabels[5]
                labels[10].text = mCurrentPopupLabels[6]
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_TA) {
                    // 例外：小さい「っ」
                    labels[9].text = "小"
                }
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_A && !mService.isHiragana) {
                    // 例外：「ヴ」
                    labels[10].text = "゛"
                }
                when {
                    isLeftCurve(mFlickState) -> 9
                    isRightCurve(mFlickState) -> 10
                    else -> 2
                }
            }
            mFlickState.contains(FlickState.RIGHT) -> {
                if (!isCurve(mFlickState)) {
                    labels[0].text = mCurrentPopupLabels[0]
                }
                labels[3].text  = mCurrentPopupLabels[3]
                labels[11].text = mCurrentPopupLabels[5]
                labels[12].text = mCurrentPopupLabels[6]
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_YA) {
                    // 例外：括弧
                    labels[11].text = "」"
                    labels[12].text = "』"
                }
                when {
                    isLeftCurve(mFlickState) -> 11
                    isRightCurve(mFlickState) -> 12
                    else -> 3
                }
            }
            mFlickState.contains(FlickState.DOWN) -> {
                if (!isCurve(mFlickState)) {
                    labels[0].text = mCurrentPopupLabels[0]
                }
                labels[4].text  = mCurrentPopupLabels[4]
                labels[13].text = mCurrentPopupLabels[5]
                labels[14].text = mCurrentPopupLabels[6]
                when {
                    isLeftCurve(mFlickState) -> 13
                    isRightCurve(mFlickState) -> 14
                    else -> 4
                }
            }
            else -> -1
        }
        labels[activeLabel].setBackgroundResource(R.drawable.popup_label_highlighted)
        for (i in 5..14) {
            val size = when (labels[i].text) {
                "゜", "゛" -> 25f // 余白部分をはみ出させて見やすくする
                else -> 12f // "小", "「", "」", "『", "』"
            }
            labels[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
        }
    }

    private fun isLeftCurve(flick: EnumSet<FlickState>): Boolean = flick.contains(FlickState.CURVE_LEFT)
    private fun isRightCurve(flick: EnumSet<FlickState>): Boolean = flick.contains(FlickState.CURVE_RIGHT)
    private fun isCurve(flick: EnumSet<FlickState>): Boolean = isLeftCurve(flick) || isRightCurve(flick)

    override fun onModifiedTouchEvent(me: MotionEvent, possiblePoly: Boolean): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mFlickStartX = me.x
                mFlickStartY = me.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = me.x - mFlickStartX
                val dy = me.y - mFlickStartY

                when {
                    dx * dx + dy * dy < mFlickSensitivitySquared -> {
                        if (mFlickState != EnumSet.of(FlickState.NONE)) {
                            mFlickState = EnumSet.of(FlickState.NONE)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                    mFlickState.contains(FlickState.NONE) -> processFirstFlick(dx, dy)
                    else -> processCurveFlick(dx, dy)
                }

                if (mUsePopup) setupPopupTextView()
                return true
            }
            MotionEvent.ACTION_UP -> release()
        }
        return super.onModifiedTouchEvent(me, possiblePoly)
    }

    private fun diamondAngle(x: Float, y: Float): Float {
        return if (y >= 0) {
            if (x >= 0) y / (x + y) else 1 - x / (-x + y)
        } else {
            if (x < 0) 2 - y / (-x - y) else 3 + x / (x - y)
        }
    }

    private fun processFirstFlick(dx: Float, dy: Float) {
        val dAngle = diamondAngle(dx, dy)
        var hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()
        //小さい「ゎ」は特別処理
        if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_WA) {
            hasLeftCurve = true
        }

        val newState = when (dAngle) {
            in 0.5f..1.5f   -> EnumSet.of(FlickState.DOWN)
            in 1.5f..2.29f  -> EnumSet.of(FlickState.LEFT)
            in 2.29f..2.71f -> when {
                    (hasLeftCurve)  -> EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT)
                    (dAngle < 2.5f) -> EnumSet.of(FlickState.LEFT)
                    else -> EnumSet.of(FlickState.UP)
                }
            in 2.71f..3.29f -> EnumSet.of(FlickState.UP)
            in 3.29f..3.71f -> when {
                    (hasRightCurve) -> EnumSet.of(FlickState.NONE, FlickState.CURVE_RIGHT)
                    (dAngle < 3.5f) -> EnumSet.of(FlickState.UP)
                    else -> EnumSet.of(FlickState.RIGHT)
                }
            else -> EnumSet.of(FlickState.RIGHT)
        }
        if (mFlickState != newState) {
            mFlickState = newState
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun processCurveFlick(dx: Float, dy: Float) {
        var hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        var hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()
        //小さい「っ」は特別処理
        if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_TA
                && mFlickState.contains(FlickState.UP)
        ) {
            hasLeftCurve = true
        }
        //『』は特別処理
        if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_YA
                && (mFlickState.contains(FlickState.LEFT) || mFlickState.contains(FlickState.RIGHT))
        ) {
            hasRightCurve = true
        }
        //「ヴ」は特別処理
        if (!mService.isHiragana
                && mLastPressedKey == KEYCODE_FLICK_JP_CHAR_A
                && mFlickState.contains(FlickState.UP)
        ) {
            hasRightCurve = true
        }

        val newstate = when {
            mFlickState.contains(FlickState.LEFT) -> when (diamondAngle(-dx, -dy)) {
                in 0.45f..2f -> EnumSet.of(FlickState.LEFT, FlickState.CURVE_RIGHT)
                in 2f..3.55f -> EnumSet.of(FlickState.LEFT, FlickState.CURVE_LEFT)
                else -> EnumSet.of(FlickState.LEFT)
            }
            mFlickState.contains(FlickState.UP) -> when (diamondAngle(-dy, dx)) {
                in 0.45f..2f -> EnumSet.of(FlickState.UP, FlickState.CURVE_RIGHT)
                in 2f..3.55f -> EnumSet.of(FlickState.UP, FlickState.CURVE_LEFT)
                else -> EnumSet.of(FlickState.UP)
            }
            mFlickState.contains(FlickState.RIGHT) -> when (diamondAngle(dx, dy)) {
                in 0.45f..2f -> EnumSet.of(FlickState.RIGHT, FlickState.CURVE_RIGHT)
                in 2f..3.55f -> EnumSet.of(FlickState.RIGHT, FlickState.CURVE_LEFT)
                else -> EnumSet.of(FlickState.RIGHT)
            }
            mFlickState.contains(FlickState.DOWN) -> when (diamondAngle(dy, -dx)) {
                in 0.45f..2f -> EnumSet.of(FlickState.DOWN, FlickState.CURVE_RIGHT)
                in 2f..3.55f -> EnumSet.of(FlickState.DOWN, FlickState.CURVE_LEFT)
                else -> EnumSet.of(FlickState.DOWN)
            }
            else -> return
        }
        if (mFlickState != newstate &&
            (!isCurve(newstate) ||
                    (hasLeftCurve && isLeftCurve(newstate)) ||
                    (hasRightCurve && isRightCurve(newstate))
            )
        ) {
            mFlickState = newstate
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun processFlickForLetter(keyCode: Int, flick: EnumSet<FlickState>, isShifted: Boolean) {
        var vowel: Int = 'a'.code
        when {
            flick.contains(FlickState.LEFT) -> vowel = 'i'.code
            flick.contains(FlickState.UP) -> vowel = 'u'.code
            flick.contains(FlickState.RIGHT) -> vowel = 'e'.code
            flick.contains(FlickState.DOWN) -> vowel = 'o'.code
        }

        val consonant: Int
        when (keyCode) {
            KEYCODE_FLICK_JP_CHAR_A -> {
                if (isLeftCurve(flick)) {
                    mService.processKey('x'.code)
                    mService.processKey(vowel)
                } else if (!mService.isHiragana && flick == EnumSet.of(FlickState.UP, FlickState.CURVE_RIGHT)) {
                    mService.processKey('v'.code)
                    mService.processKey('u'.code)
                } else if (isShifted) {
                    mService.processKey(Character.toUpperCase(vowel))
                } else {
                    mService.processKey(vowel)
                }
                return
            }
            KEYCODE_FLICK_JP_CHAR_KA -> consonant = (if (isRightCurve(flick)) 'g' else 'k').code
            KEYCODE_FLICK_JP_CHAR_SA -> consonant = (if (isRightCurve(flick)) 'z' else 's').code
            KEYCODE_FLICK_JP_CHAR_TA -> consonant = (if (isRightCurve(flick)) 'd' else 't').code
            KEYCODE_FLICK_JP_CHAR_NA -> consonant = 'n'.code
            KEYCODE_FLICK_JP_CHAR_HA -> consonant = when {
                isRightCurve(flick) -> 'b'.code
                isLeftCurve(flick)  -> 'p'.code
                else -> 'h'.code
            }
            KEYCODE_FLICK_JP_CHAR_MA -> consonant = 'm'.code
            KEYCODE_FLICK_JP_CHAR_YA -> {
                val yaSymbol = when {
                    flick.contains(FlickState.LEFT) -> when {
                        isCurve(flick) -> '['
                        else -> '('
                    }
                    flick.contains(FlickState.RIGHT) -> when {
                        isCurve(flick) -> ']'
                        else -> ')'
                    }
                    else -> 'y'
                }
                if (yaSymbol != 'y') {
                    if (isRightCurve(flick)) {
                        mService.processKey('z'.code)
                    }
                    mService.processKey(yaSymbol.code)
                    return
                }
                consonant = 'y'.code
            }
            KEYCODE_FLICK_JP_CHAR_RA -> consonant = 'r'.code
            KEYCODE_FLICK_JP_CHAR_WA -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE) -> {
                        if (isShifted) {
                            mService.processKey('W'.code)
                        } else {
                            mService.processKey('w'.code)
                        }
                        mService.processKey('a'.code)
                    }
                    EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT) -> {
                        if (isShifted) {
                            mService.processKey('X'.code)
                        } else {
                            mService.processKey('x'.code)
                        }
                        mService.processKey('w'.code)
                        mService.processKey('a'.code)
                    }
                    EnumSet.of(FlickState.LEFT) -> {
                        mService.processKey('w'.code)
                        mService.processKey('o'.code)
                    }
                    EnumSet.of(FlickState.UP) -> {
                        if (isShifted) {
                            mService.processKey('N'.code)
                        } else {
                            mService.processKey('n'.code)
                        }
                        mService.processKey('n'.code)
                    }
                    EnumSet.of(FlickState.RIGHT) -> mService.processKey('-'.code)
                    EnumSet.of(FlickState.DOWN) -> mService.processKey('~'.code)
                }
                return
            }
            KEYCODE_FLICK_JP_CHAR_TEN -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE)  -> mService.processKey(','.code)
                    EnumSet.of(FlickState.LEFT)  -> mService.processKey('.'.code)
                    EnumSet.of(FlickState.UP)    -> mService.processKey('?'.code)
                    EnumSet.of(FlickState.RIGHT) -> mService.processKey('!'.code)
                    EnumSet.of(FlickState.DOWN) -> {
                        mService.processKey('z'.code)
                        mService.processKey('.'.code)
                    }
                }
                return
            }
            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE)  -> mService.processKeyIn(SKKZenkakuState, ' '.code)
                    EnumSet.of(FlickState.LEFT)  -> mService.processKey('('.code)
                    EnumSet.of(FlickState.UP)    -> mService.processKey('['.code)
                    EnumSet.of(FlickState.RIGHT) -> mService.processKey(')'.code)
                    EnumSet.of(FlickState.DOWN)  -> mService.processKey(']'.code)
                }
                return
            }
            KEYCODE_FLICK_JP_CHAR_TEN_NUM -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE)  -> mService.commitTextSKK(",", 1)
                    EnumSet.of(FlickState.LEFT)  -> mService.commitTextSKK(".", 1)
                    EnumSet.of(FlickState.UP)    -> mService.commitTextSKK("-", 1)
                    EnumSet.of(FlickState.RIGHT) -> mService.commitTextSKK(":", 1)
                    EnumSet.of(FlickState.DOWN)  -> mService.commitTextSKK("/", 1)
                }
                return
            }
            KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE)  -> mService.commitTextSKK("#", 1)
                    EnumSet.of(FlickState.LEFT)  -> mService.commitTextSKK("￥", 1)
                    EnumSet.of(FlickState.UP)    -> mService.commitTextSKK("+", 1)
                    EnumSet.of(FlickState.RIGHT) -> mService.commitTextSKK("$", 1)
                    EnumSet.of(FlickState.DOWN)  -> mService.commitTextSKK("*", 1)
                }
                return
            }
            else -> return
        }

        if (isShifted) {
            mService.processKey(Character.toUpperCase(consonant))
        } else {
            mService.processKey(consonant)
        }
        mService.processKey(vowel)

        if (isLeftCurve(flick)) {
            if (consonant == 't'.code && vowel == 'u'.code
                    || consonant == 'y'.code && (vowel == 'a'.code || vowel == 'u'.code || vowel == 'o'.code)) {
                mService.changeLastChar(SKKEngine.LAST_CONVERSION_SMALL)
            }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean {
        val code = key.codes[0]
        if (code == KEYCODE_FLICK_JP_ENTER) {
            mService.keyDownUp(KeyEvent.KEYCODE_SEARCH)
            return true
        } else if (code == KEYCODE_FLICK_JP_SPACE) {
            mService.sendToMushroom()
            return true
        }

        return super.onLongPress(key)
    }

    override fun onPress(primaryCode: Int) {
        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedKey = primaryCode
        }

        if (mUsePopup) {
            val labels = mFlickGuideLabelList.get(primaryCode)
            if (labels == null) {
                mCurrentPopupLabels = POPUP_LABELS_NULL
                return
            }

            for (i in 0..6) {
                if (mService.isHiragana) {
                    mCurrentPopupLabels[i] = labels[i]
                } else {
                    mCurrentPopupLabels[i] = checkNotNull(
                        hirakana2katakana(labels[i], reversed = true)
                    ) { "BUG: invalid popup label!!"}
                }
            }
            setupPopupTextView()

            if (mFixedPopupPos[0] == 0) calculatePopupPos()

            val popup = checkNotNull(mPopup) { "BUG: popup is null!!" }
            if (mFixedPopup) {
                popup.showAtLocation(
                        this, android.view.Gravity.NO_GRAVITY,
                        mFixedPopupPos[0], mFixedPopupPos[1]
                )
            } else {
                popup.showAtLocation(
                        this, android.view.Gravity.NO_GRAVITY,
                        mFlickStartX.toInt() + mPopupOffset[0],
                        mFlickStartY.toInt() + mPopupOffset[1]
                )
            }
        }
    }

    private fun calculatePopupPos() {
        val scale = context.resources.displayMetrics.density
        val size = (mPopupSize * scale + 0.5f).toInt()

        val offsetInWindow = IntArray(2)
        getLocationInWindow(offsetInWindow)
        val windowLocation = IntArray(2)
        getLocationOnScreen(windowLocation)
        mPopupOffset[0] = -size / 2
        mPopupOffset[1] = -windowLocation[1] + offsetInWindow[1] - size / 2
        mFixedPopupPos[0] = windowLocation[0] + this.width / 2 + mPopupOffset[0]
        mFixedPopupPos[1] = windowLocation[1] - size / 2 + mPopupOffset[1]
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                onSetShifted(isShifted)
            }
            Keyboard.KEYCODE_DELETE -> if (!mService.handleBackspace()) {
                mService.keyDownUp(KeyEvent.KEYCODE_DEL)
            }
            KEYCODE_FLICK_JP_LEFT -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            KEYCODE_FLICK_JP_RIGHT -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            33, 40, 41, 44, 46, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 63, 91, 93 ->
                // ! ( ) , . 0〜9 ? [ ]
                mService.processKey(primaryCode)
            KEYCODE_FLICK_JP_PASTE -> {
                mService.pasteClip()
            }
            KEYCODE_FLICK_JP_GOOGLE -> {
                mService.googleTransliterate()
            }
        }
    }

    private fun release() {
        when (mLastPressedKey) {
            KEYCODE_FLICK_JP_SPACE  -> if (isShifted) {
                mService.startSettings()
            } else {
                mService.processKey(' '.code)
            }
            KEYCODE_FLICK_JP_ENTER  -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_FLICK_JP_KOMOJI -> {
                val smallState = if (skkPrefs.useSoftCancelKey) FlickState.UP else FlickState.NONE
                val cancelState = if (skkPrefs.useSoftCancelKey) FlickState.NONE else FlickState.UP
                when (mFlickState) {
                    EnumSet.of(smallState) ->
                        mService.changeLastChar(
                            if (!skkPrefs.useSoftCancelKey && skkPrefs.useSoftTransKey)
                                SKKEngine.LAST_CONVERSION_TRANS
                            else
                                SKKEngine.LAST_CONVERSION_SMALL
                        )
                    EnumSet.of(FlickState.LEFT) ->
                        mService.changeLastChar(SKKEngine.LAST_CONVERSION_DAKUTEN)
                    EnumSet.of(cancelState) ->
                        mService.handleCancel()
                    EnumSet.of(FlickState.RIGHT) ->
                        mService.changeLastChar(SKKEngine.LAST_CONVERSION_HANDAKUTEN)
                    EnumSet.of(FlickState.DOWN)  -> {
                        mService.changeLastChar(SKKEngine.LAST_CONVERSION_SHIFT)
                        setHiraganaMode()
                    }
                }
            }
            KEYCODE_FLICK_JP_MOJI -> when (mFlickState) {
                EnumSet.of(FlickState.NONE) -> mService.processKey('q'.code)
                EnumSet.of(FlickState.LEFT) -> mService.processKey(':'.code)
                EnumSet.of(FlickState.UP)   -> if (keyboard !== mNumKeyboard) { keyboard = mNumKeyboard }
                EnumSet.of(FlickState.RIGHT)-> mService.processKey('>'.code)
                EnumSet.of(FlickState.DOWN) -> if (keyboard !== mVoiceKeyboard) { keyboard = mVoiceKeyboard }
            }
            KEYCODE_FLICK_JP_TOKANA -> if (keyboard !== mJPKeyboard) { keyboard = mJPKeyboard }
            KEYCODE_FLICK_JP_TOQWERTY -> if (isShifted) {
                mService.processKey('/'.code)
            } else {
                when (mFlickState) {
                    EnumSet.of(FlickState.NONE) -> mService.processKey('l'.code)
                    EnumSet.of(FlickState.UP)   -> {
                        mService.processKey('L'.code)
                    }
                    EnumSet.of(FlickState.DOWN) -> mService.changeSoftKeyboard(SKKASCIIState)
                }
            }
            KEYCODE_FLICK_JP_SPEECH -> mService.recognizeSpeech()
            KEYCODE_FLICK_JP_CHAR_A, KEYCODE_FLICK_JP_CHAR_KA, KEYCODE_FLICK_JP_CHAR_SA,
                    KEYCODE_FLICK_JP_CHAR_TA, KEYCODE_FLICK_JP_CHAR_NA, KEYCODE_FLICK_JP_CHAR_HA,
                    KEYCODE_FLICK_JP_CHAR_MA, KEYCODE_FLICK_JP_CHAR_YA, KEYCODE_FLICK_JP_CHAR_RA,
                    KEYCODE_FLICK_JP_CHAR_WA, KEYCODE_FLICK_JP_CHAR_TEN,
                    KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED,
                    KEYCODE_FLICK_JP_CHAR_TEN_NUM, KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT
                    -> processFlickForLetter(mLastPressedKey, mFlickState, isShifted)
        }

        if (mLastPressedKey != Keyboard.KEYCODE_SHIFT) {
            isShifted = false
            onSetShifted(false)
        }

        mLastPressedKey = KEYCODE_FLICK_JP_NONE
        mFlickState = EnumSet.of(FlickState.NONE)
        mFlickStartX = -1f
        mFlickStartY = -1f
        if (mUsePopup) {
            val popup = checkNotNull(mPopup) { "BUG: popup is null!!" }
            if (popup.isShowing) popup.dismiss()
        }
    }

    override fun onRelease(primaryCode: Int) {}

    override fun onText(text: CharSequence) {}

    override fun swipeRight() {}

    override fun swipeLeft() {}

    override fun swipeDown() {}

    override fun swipeUp() {}

    private val leftSymbol = "【"
    private val rightSymbol = "】"
    fun speechRecognitionResultsList(results: ArrayList<String>) {
        val (prefix, array, suffix) = extractCommon(results)

        val dialogBuilder = AlertDialog.Builder(context, R.style.Theme_SKK).apply {
            setTitle("${prefix}${leftSymbol} ${rightSymbol}${suffix}")
            setItems(array) { _, which ->
                val chosen = array[which].substring(
                    leftSymbol.length,
                    array[which].length - rightSymbol.length
                )
                mService.commitTextSKK(prefix, 1)
                if (chosen.isNotEmpty()) {
                    mService.commitTextSKK(chosen, 1)
                }
                mService.commitTextSKK(suffix, 1)
            }
        }

        val dialog = dialogBuilder.create()
        dialog.window!!.let {
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            it.attributes?.token = this.windowToken
            it.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        }
        dialog.show()
    }
    private fun extractCommon(list: ArrayList<String>): Triple<String, Array<CharSequence>, String> {
        var commonPrefix = list.last()
        var commonSuffix = list.last()
        list.forEach {
            if (commonPrefix.isNotEmpty()) { commonPrefix = it.commonPrefixWith(commonPrefix) }
            if (commonSuffix.isNotEmpty()) { commonSuffix = it.commonSuffixWith(commonSuffix) }
        }

        val array: Array<CharSequence> = list.map {
            leftSymbol + it.substring(
                commonPrefix.length,
                it.length - commonSuffix.length
            ) + rightSymbol
        }.toTypedArray()
        return Triple(commonPrefix, array, commonSuffix)
    }

    companion object {
        private const val KEYCODE_FLICK_JP_CHAR_A = -201
        private const val KEYCODE_FLICK_JP_CHAR_KA = -202
        private const val KEYCODE_FLICK_JP_CHAR_SA = -203
        private const val KEYCODE_FLICK_JP_CHAR_TA = -204
        private const val KEYCODE_FLICK_JP_CHAR_NA = -205
        private const val KEYCODE_FLICK_JP_CHAR_HA = -206
        private const val KEYCODE_FLICK_JP_CHAR_MA = -207
        private const val KEYCODE_FLICK_JP_CHAR_YA = -208
        private const val KEYCODE_FLICK_JP_CHAR_RA = -209
        private const val KEYCODE_FLICK_JP_CHAR_WA = -210
        private const val KEYCODE_FLICK_JP_CHAR_TEN = -211
        private const val KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED = -212
        private const val KEYCODE_FLICK_JP_CHAR_TEN_NUM = -213
        private const val KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT = -214
        private const val KEYCODE_FLICK_JP_NONE = -1000
        private const val KEYCODE_FLICK_JP_LEFT = -1001
        private const val KEYCODE_FLICK_JP_RIGHT = -1002
        private const val KEYCODE_FLICK_JP_TOQWERTY = -1003
        private const val KEYCODE_FLICK_JP_SPACE = -1004
        private const val KEYCODE_FLICK_JP_MOJI = -1005
        private const val KEYCODE_FLICK_JP_KOMOJI = -1006
        private const val KEYCODE_FLICK_JP_ENTER = -1007
//        private const val KEYCODE_FLICK_JP_SEARCH = -1008
//        private const val KEYCODE_FLICK_JP_CANCEL = -1009
        private const val KEYCODE_FLICK_JP_TOKANA = -1010
        private const val KEYCODE_FLICK_JP_PASTE = -1011
        private const val KEYCODE_FLICK_JP_SPEECH = -1012
        private const val KEYCODE_FLICK_JP_GOOGLE = -1013
        private enum class FlickState { NONE, LEFT, UP, RIGHT, DOWN, CURVE_LEFT, CURVE_RIGHT }
        private val POPUP_LABELS_NULL = arrayOf("", "", "", "", "", "", "")
    }
}
