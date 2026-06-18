package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.SparseArray
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
import jp.deadend.noname.skk.engine.SKKHanKanaState
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKKatakanaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import java.util.EnumSet
import kotlin.math.ceil

class FlickJPKeyboardView(context: Context, attrs: AttributeSet?) : KeyboardView(context, attrs),
    KeyboardView.OnKeyboardActionListener {
    private var mLastPressedKey = KEYCODE_FLICK_JP_NONE
    private var mFlickState = EnumSet.of(FlickState.NONE)
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private val mArrowPressed: Boolean
        get() = (mLastPressedKey == KEYCODE_FLICK_JP_LEFT || mLastPressedKey == KEYCODE_FLICK_JP_RIGHT)
    private var mArrowFlicked = false
    private var mArrowStartX = -1f
    private var mArrowStartY = -1f
    private var mCurrentPopupLabels = Array(7) { "" }

    private var mUsePopup = true
    private var mFixedPopup = false
    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mPopupOffset = intArrayOf(0, 0)
    private val mFixedPopupPos = intArrayOf(0, 0)
    private val mCoordinates = IntArray(2)

    val mJPKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_jp, mService.mRootWidth, mService.mScreenHeight)
    }
    val mNumKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_number, mService.mRootWidth, mService.mScreenHeight)
    }
    val mVoiceKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_voice, mService.mRootWidth, mService.mScreenHeight)
    }

    private var mKutoutenLabel = "？\n． ，！\n…"
    private val mKutoutenKey: Keyboard.Key by lazy {
        checkNotNull(
            findKeyByCode(
                mJPKeyboard,
                KEYCODE_FLICK_JP_CHAR_TEN
            )
        ) { "BUG: no kutouten key" }
    }
    private val mSpaceKey: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_SPACE)) { "BUG: no space key" }
    }
    private val mQwertyKey: Keyboard.Key by lazy {
        checkNotNull(
            findKeyByCode(
                mJPKeyboard,
                KEYCODE_FLICK_JP_TO_QWERTY
            )
        ) { "BUG: no qwerty key" }
    }
    private val mShiftKeyJP: Keyboard.Key by lazy { mJPKeyboard.keys[0] }
    private val mShiftKeyNum: Keyboard.Key by lazy { mNumKeyboard.keys[0] }
    private val mShiftKeyVoice: Keyboard.Key by lazy { mVoiceKeyboard.keys[0] }
    private val mKanaKeyJP: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_MOJI)) { "BUG: no moji key" }
    }
    private val mKanaKeyNum: Keyboard.Key by lazy {
        checkNotNull(
            findKeyByCode(
                mNumKeyboard,
                KEYCODE_FLICK_JP_TO_KANA
            )
        ) { "BUG: no kana key in num" }
    }
    private val mKanaKeyVoice: Keyboard.Key by lazy {
        checkNotNull(
            findKeyByCode(
                mVoiceKeyboard,
                KEYCODE_FLICK_JP_TO_KANA
            )
        ) { "BUG: no kana key in voice" }
    }

    //フリックガイドTextView用
    private val mFlickGuideLabelList = SparseArray<Array<String>>()

    init {
        mapOf(
            KEYCODE_FLICK_JP_CHAR_A to arrayOf("あ", "い", "う", "え", "お", "小", ""),
            KEYCODE_FLICK_JP_CHAR_KA to arrayOf("か", "き", "く", "け", "こ", "", "゛"),
            KEYCODE_FLICK_JP_CHAR_SA to arrayOf("さ", "し", "す", "せ", "そ", "", "゛"),
            KEYCODE_FLICK_JP_CHAR_TA to arrayOf("た", "ち", "つ", "て", "と", "", "゛"),
            KEYCODE_FLICK_JP_CHAR_NA to arrayOf("な", "に", "ぬ", "ね", "の", "", ""),
            KEYCODE_FLICK_JP_CHAR_HA to arrayOf("は", "ひ", "ふ", "へ", "ほ", "゜", "゛"),
            KEYCODE_FLICK_JP_CHAR_MA to arrayOf("ま", "み", "む", "め", "も", "", ""),
            KEYCODE_FLICK_JP_CHAR_YA to arrayOf("や", "（", "ゆ", "）", "よ", "小", ""),
            KEYCODE_FLICK_JP_CHAR_RA to arrayOf("ら", "り", "る", "れ", "ろ", "", ""),
            KEYCODE_FLICK_JP_CHAR_WA to arrayOf("わ", "を", "ん", "ー", "～", "", ""),
            KEYCODE_FLICK_JP_CHAR_TEN to arrayOf("、", "。", "？", "！", "…", "", ""),
            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED to arrayOf("　", "（", "「", "）", "」", "", ""),
            KEYCODE_FLICK_JP_CHAR_TEN_NUM to arrayOf("，", "．", "－", "：", "／", "", ""),
            KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT to arrayOf("＃", "￥", "＋", "＄", "＊", "", ""),
            KEYCODE_FLICK_JP_MOJI to arrayOf("カナ", "：", "10", "＞", "声", "", ""),
            KEYCODE_FLICK_JP_TO_QWERTY to arrayOf("abc", "絵☻", "全角ａ", "記号", "qwerty", "", ""),
            KEYCODE_FLICK_JP_LEFT to arrayOf("＜", "←", "↑", "→", "↓", "", ""),
            KEYCODE_FLICK_JP_RIGHT to arrayOf("＞", "←", "↑", "→", "↓", "", ""),
            KEYCODE_FLICK_JP_SPACE to arrayOf("SPACE", "", "Mush", "", "", "", "")
        ).forEach { (code, labels) -> mFlickGuideLabelList.append(code, labels) }
    }

    override fun setService(service: SKKService) {
        super.setService(service)
        onKeyboardActionListener = this
        isPreviewEnabled = false
        setBackgroundColor(0x00000000)

        keyboard = mJPKeyboard
    }

    override fun onDetachedFromWindow() {
        mPopup?.dismiss()
        super.onDetachedFromWindow()
        //isShifted = false
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
            shiftKey.codes.main[0] = 0
            shiftKey.labels.main = ""
            shiftKey.icon = null
        }
        shiftKeys[0].apply {
            codes.main[0] = Keyboard.KEYCODE_SHIFT
            icon = ResourcesCompat.getDrawable(
                resources, R.drawable.ic_keyboard_shift, null
            )?.also {
                it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
            }
        }

        for (kanaKey in kanaKeys) {
            kanaKey.codes.main[0] = KEYCODE_FLICK_JP_TO_KANA
            kanaKey.labels.main = "\n かな \n"
            kanaKey.icon = null
        }
        kanaKeys[0].codes.main[0] = KEYCODE_FLICK_JP_MOJI

        for (keyboard in arrayOf(mJPKeyboard, mNumKeyboard, mVoiceKeyboard)) {
            keyboard.reloadShiftKeys()
        }
    }

    override fun setKeyState(state: SKKState): FlickJPKeyboardView {
        isHankaku = when (state) {
            SKKHiraganaState, SKKKatakanaState -> false
            SKKHanKanaState -> true
            else -> return this
        }
        mService.kanaState = state
        val labels = mapOf(
            KEYCODE_FLICK_JP_CHAR_A to "あ", KEYCODE_FLICK_JP_CHAR_KA to "か",
            KEYCODE_FLICK_JP_CHAR_SA to "さ", KEYCODE_FLICK_JP_CHAR_TA to "た",
            KEYCODE_FLICK_JP_CHAR_NA to "な", KEYCODE_FLICK_JP_CHAR_HA to "は",
            KEYCODE_FLICK_JP_CHAR_MA to "ま", KEYCODE_FLICK_JP_CHAR_YA to "や",
            KEYCODE_FLICK_JP_CHAR_RA to "ら", KEYCODE_FLICK_JP_CHAR_WA to "ん\nをわー\n〜"
        )
        for (key in keyboard.keys) {
            val label = labels[key.codes.main[0]] ?: continue
            key.labels.main = if (state is SKKHiraganaState) label else hiragana2katakana(label)
        }
        invalidateAllKeys()
        return this
    }

    private fun findKeyByCode(keyboard: Keyboard, code: Int) =
        keyboard.keys.find { it.codes.main[0] == code }

    private fun onSetShifted() {
        val shifted = isShifted
        mKutoutenKey.codes.main[0] =
            if (shifted) KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED else KEYCODE_FLICK_JP_CHAR_TEN
        mKutoutenKey.labels.main = if (shifted) "「\n（□）\n」" else mKutoutenLabel
        mSpaceKey.labels.main = if (shifted) "設定" else ""
        mQwertyKey.labels.main = if (shifted) "全角ａ\n☻略記\nqwerty" else "全角ａ\n☻abc記\nqwerty"

        findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_MOJI)?.let { key ->
            key.labels.main = when {
                shifted -> if (mService.kanaState is SKKHanKanaState) "10\n：カナ>\n声" else "10\n：ｶﾅ>\n声"
                else -> if (mService.kanaState is SKKHiraganaState) "10\n：カナ>\n声" else "10\n：かな>\n声"
            }
        }

        val lr = listOf(
            KEYCODE_FLICK_JP_LEFT to KEYCODE_FLICK_JP_PASTE,
            KEYCODE_FLICK_JP_RIGHT to KEYCODE_FLICK_JP_GOOGLE
        )
        lr.forEach { (normalCode, shiftedCode) ->
            findKeyByCode(mJPKeyboard, if (shifted) normalCode else shiftedCode)?.let { key ->
                key.codes.main[0] = if (shifted) shiftedCode else normalCode
                key.labels.main = if (shifted)
                    (if (shiftedCode == KEYCODE_FLICK_JP_PASTE) "貼付" else "Google")
                else ""
            }
        }
    }

    internal fun prepareNewKeyboard(
        context: Context,
        widthPixel: Int,
        heightPixel: Int
    ) {
        mJPKeyboard.resize(widthPixel, heightPixel)
        mNumKeyboard.resize(widthPixel, heightPixel)
        mVoiceKeyboard.resize(widthPixel, heightPixel)
        keyboard = mJPKeyboard
        invalidateAllKeys()

        readPrefs(context)
    }

    override fun drawCenterLabel(
        lines: List<String>, lineIndex: Int,
        canvas: Canvas, x: Float, y: Float, paint: Paint
    ): Boolean {
        if (lineIndex == 1 && lines.size == 3 && lines[1].length > 2) {
            val currentTypeface = paint.typeface
            paint.typeface = mBoldTypeface

            val line = lines[lineIndex]
            val centerText = line.substring(1, line.length - 1)
            val ascii = centerText.count { it.code < 0x7F }
            val spaces = "　".repeat(1 + ceil((centerText.length - ascii * 0.5)).toInt())
            val sideText = "${line.first()}$spaces${line.last()}"

            canvas.drawText(centerText, x, y, paint)

            paint.typeface = currentTypeface
            paint.textSize *= 0.67f
            canvas.drawText(sideText, x, y, paint)
            return true
        }
        return false
    }

    private fun readPrefs(context: Context) {
        // シフトかな交換
        setShiftPosition()
        onSetShifted()
        // 句読点
        when (skkPrefs.kutoutenType) {
            "en" -> {
                mKutoutenLabel = "？\n．，！\n…"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("，", "．", "？", "！", "…", "", "")
                )
            }

            "jp_en" -> {
                mKutoutenLabel = "？\n。，！\n…"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("，", "。", "？", "！", "…", "", "")
                )
            }

            else -> {
                mKutoutenLabel = "？\n。、！\n…"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_CHAR_TEN, arrayOf("、", "。", "？", "！", "…", "", "")
                )
            }
        }
        mKutoutenKey.labels.main = mKutoutenLabel
        when {
            skkPrefs.useSoftCancelKey -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)
                    ?.labels?.main = "小\n ◻゙CXL◻゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("CXL", "◻゙", "小", "◻゚", "▽", "", "")
                )
            }

            skkPrefs.useSoftTransKey -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)
                    ?.labels?.main = "CXL\n ◻゙□゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("◻゙□゚", "◻゙", "CXL", "◻゚", "▽", "", "")
                )
            }

            else -> {
                findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KOMOJI)
                    ?.labels?.main = "CXL\n ◻゙小◻゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_FLICK_JP_KOMOJI, arrayOf("小", "◻゙", "CXL", "◻゚", "▽", "", "")
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

        //    09 02 10
        // 08 05    06 11
        // 01    00    03
        // 07          12
        //    14 04 13
        val baseIndex = when {
            mFlickState.contains(FlickState.LEFT) -> 1
            mFlickState.contains(FlickState.UP) -> 2
            mFlickState.contains(FlickState.RIGHT) -> 3
            mFlickState.contains(FlickState.DOWN) -> 4
            else -> 0 // NONE
        }

        val activeLabel = when {
            isLeftCurve(mFlickState) -> baseIndex * 2 + 5
            isRightCurve(mFlickState) -> baseIndex * 2 + 6
            else -> baseIndex
        }

        // ラベルのテキスト設定
        if (baseIndex == 0) {
            labels[0].text = mCurrentPopupLabels[0]
            if (!isCurve(mFlickState)) {
                labels[1].text = mCurrentPopupLabels[1]
                labels[2].text = mCurrentPopupLabels[2]
                labels[3].text = mCurrentPopupLabels[3]
                labels[4].text = mCurrentPopupLabels[4]
            }
            labels[5].text =
                if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_WA) "小" else mCurrentPopupLabels[5]
            labels[6].text = mCurrentPopupLabels[6]
        } else {
            if (!isCurve(mFlickState)) labels[0].text = mCurrentPopupLabels[0]
            labels[baseIndex].text = mCurrentPopupLabels[baseIndex]
            val cl = baseIndex * 2 + 5
            val cr = baseIndex * 2 + 6
            labels[cl].text = when (mLastPressedKey) {
                KEYCODE_FLICK_JP_CHAR_YA if baseIndex == 1 -> "「"
                KEYCODE_FLICK_JP_CHAR_TA if baseIndex == 2 -> "小"
                else -> mCurrentPopupLabels[5]
            }
            labels[cr].text = when (mLastPressedKey) {
                KEYCODE_FLICK_JP_CHAR_YA if baseIndex == 1 -> "『"
                KEYCODE_FLICK_JP_CHAR_A if baseIndex == 2 && !mService.isHiragana -> "゛"
                KEYCODE_FLICK_JP_CHAR_YA if baseIndex == 3 -> "』"
                else -> mCurrentPopupLabels[6]
            }
            // YA 右の括弧だけ特殊
            if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_YA && baseIndex == 3) labels[cl].text = "」"
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

    private fun isLeftCurve(flick: EnumSet<FlickState>): Boolean =
        flick.contains(FlickState.CURVE_LEFT)

    private fun isRightCurve(flick: EnumSet<FlickState>): Boolean =
        flick.contains(FlickState.CURVE_RIGHT)

    private fun isCurve(flick: EnumSet<FlickState>): Boolean =
        isLeftCurve(flick) || isRightCurve(flick)

    override fun onModifiedTouchEvent(me: MotionEvent, possiblePoly: Boolean): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mFlickStartX = me.x
                mFlickStartY = me.y
                mArrowStartX = me.x
                mArrowStartY = me.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = me.x - mFlickStartX
                val dy = me.y - mFlickStartY

                when {
                    dx * dx + dy * dy < mFlickSensitivitySquared -> {
                        if (mFlickState != EnumSet.of(FlickState.NONE)) {
                            mFlickState = EnumSet.of(FlickState.NONE)
                            performHapticFeedback(skkPrefs.haptic)
                        }
                    }

                    mArrowPressed -> {
                        val adx = me.x - mArrowStartX
                        val ady = me.y - mArrowStartY
                        val adx2 = adx * adx
                        val ady2 = ady * ady
                        val horizontal = adx2 > ady2
                        val dist2 = if (horizontal) adx2 else ady2

                        if (dist2 > mFlickSensitivitySquared) {
                            val key = if (horizontal) {
                                if (adx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                            } else {
                                if (ady < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                            }
                            if (!mService.handleDpad(key)) mService.keyDownUp(key)
                            mArrowFlicked = true
                            mArrowStartX = me.x
                            mArrowStartY = me.y
                            stopRepeatKey()
                            return true
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

    private fun processFirstFlick(dx: Float, dy: Float) {
        val dAngle = diamondAngle(dx, dy)
        var hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()
        //小さい「ゎ」は特別処理
        if (mLastPressedKey == KEYCODE_FLICK_JP_CHAR_WA) {
            hasLeftCurve = true
        }

        val newState = when (dAngle) {
            in 0.5f..1.5f -> EnumSet.of(FlickState.DOWN)
            in 1.5f..2.29f -> EnumSet.of(FlickState.LEFT)
            in 2.29f..2.71f -> when {
                (hasLeftCurve) -> EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT)
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
            performHapticFeedback(skkPrefs.haptic)
            stopRepeatKey()
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

        val newState = when {
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
        if (mFlickState != newState &&
            (!isCurve(newState) ||
                    (hasLeftCurve && isLeftCurve(newState)) ||
                    (hasRightCurve && isRightCurve(newState))
                    )
        ) {
            mFlickState = newState
            performHapticFeedback(skkPrefs.haptic)
        }
    }

    private fun processFlickForLetter(keyCode: Int, flick: EnumSet<FlickState>) {
        val vowel = when {
            flick.contains(FlickState.LEFT) -> 'i'
            flick.contains(FlickState.UP) -> 'u'
            flick.contains(FlickState.RIGHT) -> 'e'
            flick.contains(FlickState.DOWN) -> 'o'
            else -> 'a'
        }

        val consonant: Char? = when (keyCode) {
            KEYCODE_FLICK_JP_CHAR_A -> {
                if (isLeftCurve(flick)) {
                    if (mService.engineState.hasCandidates) mService.handleEnter()
                    mService.processKey('x')
                    mService.processKey(vowel)
                } else if (!mService.isHiragana &&
                    flick == EnumSet.of(FlickState.UP, FlickState.CURVE_RIGHT)
                ) {
                    mService.processKey('v'); mService.processKey('u')
                } else {
                    mService.processKey(if (isShifted) vowel.uppercaseChar() else vowel)
                }
                return
            }

            KEYCODE_FLICK_JP_CHAR_KA -> if (isRightCurve(flick)) 'g' else 'k'
            KEYCODE_FLICK_JP_CHAR_SA -> if (isRightCurve(flick)) 'z' else 's'
            KEYCODE_FLICK_JP_CHAR_TA -> if (isRightCurve(flick)) 'd' else 't'
            KEYCODE_FLICK_JP_CHAR_NA -> 'n'
            KEYCODE_FLICK_JP_CHAR_HA -> when {
                isRightCurve(flick) -> 'b'
                isLeftCurve(flick) -> 'p'
                else -> 'h'
            }

            KEYCODE_FLICK_JP_CHAR_MA -> 'm'
            KEYCODE_FLICK_JP_CHAR_YA -> {
                val yaSymbol = when {
                    flick.contains(FlickState.LEFT) -> if (isCurve(flick)) '[' else '('
                    flick.contains(FlickState.RIGHT) -> if (isCurve(flick)) ']' else ')'
                    else -> 'y'
                }
                if (yaSymbol != 'y') {
                    if (isRightCurve(flick)) mService.processKey('z')
                    mService.processKey(yaSymbol)
                    return
                }
                'y'
            }

            KEYCODE_FLICK_JP_CHAR_RA -> 'r'
            KEYCODE_FLICK_JP_CHAR_WA -> {
                when (flick) {
                    EnumSet.of(FlickState.NONE) -> {
                        mService.processKey(if (isShifted) 'W' else 'w'); mService.processKey('a')
                    }

                    EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT) -> {
                        if (mService.engineState.hasCandidates) mService.handleEnter()
                        mService.processKey(if (isShifted) 'X' else 'x')
                        mService.processKey('w'); mService.processKey('a')
                    }

                    EnumSet.of(FlickState.LEFT) -> {
                        mService.processKey('w'); mService.processKey('o')
                    }

                    EnumSet.of(FlickState.UP) -> {
                        if (isShifted) mService.processKey('N') else mService.processKey('n')
                        mService.processKey('n')
                    }

                    EnumSet.of(FlickState.RIGHT) -> mService.processKey('-')
                    EnumSet.of(FlickState.DOWN) -> mService.processKey('~')
                }
                return
            }

            KEYCODE_FLICK_JP_CHAR_TEN -> {
                when {
                    flick.contains(FlickState.NONE) -> mService.processKey(',')
                    flick.contains(FlickState.LEFT) -> mService.processKey('.')
                    flick.contains(FlickState.UP) -> mService.processKey('?')
                    flick.contains(FlickState.RIGHT) -> mService.processKey('!')
                    flick.contains(FlickState.DOWN) -> {
                        mService.processKey('z'); mService.processKey('.')
                    }
                }
                return
            }

            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED -> {
                when {
                    flick.contains(FlickState.NONE) -> mService.processKeyIn(SKKZenkakuState, ' ')
                    flick.contains(FlickState.LEFT) -> mService.processKey('(')
                    flick.contains(FlickState.UP) -> mService.processKey('[')
                    flick.contains(FlickState.RIGHT) -> mService.processKey(')')
                    flick.contains(FlickState.DOWN) -> mService.processKey(']')
                }
                return
            }

            KEYCODE_FLICK_JP_CHAR_TEN_NUM -> {
                val s = when {
                    flick.contains(FlickState.NONE) -> ","
                    flick.contains(FlickState.LEFT) -> "."
                    flick.contains(FlickState.UP) -> "-"
                    flick.contains(FlickState.RIGHT) -> ":"
                    flick.contains(FlickState.DOWN) -> "/"
                    else -> ""
                }
                mService.commitTextSKK(s); return
            }

            KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT -> {
                val s = when {
                    flick.contains(FlickState.NONE) -> "#"
                    flick.contains(FlickState.LEFT) -> "￥"
                    flick.contains(FlickState.UP) -> "+"
                    flick.contains(FlickState.RIGHT) -> "$"
                    flick.contains(FlickState.DOWN) -> "*"
                    else -> ""
                }
                mService.commitTextSKK(s); return
            }

            else -> null
        }

        consonant?.let { c ->
            mService.suspendCompletion()
            mService.processKey(if (isShifted) c.uppercaseChar() else c)
            if (isLeftCurve(flick) && (c == 't' && vowel == 'u' || c == 'y' && vowel in "auo")) {
                mService.processKey(vowel)
                mService.resumeCompletion()
                mService.changeLastChar(SKKEngine.LAST_CONVERSION_SMALL)
            } else {
                mService.resumeCompletion()
                mService.processKey(vowel)
            }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean {
        val code = key.codes.main[0]
        if (code == KEYCODE_FLICK_JP_ENTER) {
            mService.pressSearch()
            return true
        }

        if (mUsePopup && !skkPrefs.popupOnPress) {
            if (showPopup()) return true
        }

        return super.onLongPress(key)
    }

    override fun onPress(primaryCode: Int) {
        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedKey = primaryCode
        }

        mArrowFlicked = false

        val labels = mFlickGuideLabelList[primaryCode] ?: run {
            mCurrentPopupLabels.fill("")
            return
        }

        labels.forEachIndexed { i, label ->
            if (i > 6) return@forEachIndexed
            mCurrentPopupLabels[i] = if (mService.isHiragana) label else {
                checkNotNull(
                    hiragana2katakana(
                        label,
                        reversed = true
                    )
                ) { "BUG: invalid popup label!!" }
            }
        }

        if (mUsePopup && skkPrefs.popupOnPress) {
            showPopup()
        }
    }

    private fun showPopup(): Boolean {
        if (mCurrentPopupLabels[0] == "") return false

        // FlickJP は mPopupTextView の内容をロジックに使わない
        setupPopupTextView()

        calculatePopupPos()

        val popup = checkNotNull(mPopup) { "BUG: popup is null!!" }
        val (x, y) = if (mFixedPopup) mFixedPopupPos[0] to mFixedPopupPos[1]
        else mFlickStartX.toInt() + mPopupOffset[0] to
                mFlickStartY.toInt() + mPopupOffset[1]
        popup.showAtLocation(this, android.view.Gravity.NO_GRAVITY, x, y)

        // true だと release して repeat が終わってしまうので
        return !(findKeyByCode(mJPKeyboard, mLastPressedKey)?.repeatable ?: false)
    }

    private fun calculatePopupPos() {
        val scale = context.resources.displayMetrics.density
        val size = (mPopupSize * scale + 0.5f).toInt()
        val fingerOffset = size * skkPrefs.fingerOffset / 100
        getLocationInWindow(mCoordinates)
        mPopupOffset[0] = mCoordinates[0] - size / 2
        mPopupOffset[1] = mCoordinates[1] - size / 2 - fingerOffset
        mFixedPopupPos[0] = mCoordinates[0] + this.width / 2 - size / 2
        mFixedPopupPos[1] = mCoordinates[1] - size - fingerOffset
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            // repeatable
            Keyboard.KEYCODE_DELETE -> if (!mService.handleBackspace()) {
                mService.pressDel()
            }

            KEYCODE_FLICK_JP_LEFT -> if (!mArrowFlicked && !mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
            }

            KEYCODE_FLICK_JP_RIGHT -> if (!mArrowFlicked && !mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
            }

            KEYCODE_FLICK_JP_SPACE -> if (isShifted) {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else if (mFlickState == EnumSet.of(FlickState.NONE)) {
                mService.processKey(' ')
            }
            // 不明: release で処理してもいいのか?
            33, 40, 41, 44, 46, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 63, 91, 93 ->
                // ! ( ) , . 0〜9 ? [ ]
                mService.processKey(primaryCode)
        }
    }

    private fun release() {
        var consumesShift = true
        when (mLastPressedKey) {
            // repeatable のフリック
            KEYCODE_FLICK_JP_SPACE -> if (mFlickState == EnumSet.of(FlickState.UP))
                mService.sendToMushroom()
            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                onSetShifted()
                consumesShift = false
            }

            KEYCODE_FLICK_JP_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_FLICK_JP_KOMOJI -> {
                val isSoftCancel = skkPrefs.useSoftCancelKey
                when (mFlickState) {
                    EnumSet.of(if (isSoftCancel) FlickState.UP else FlickState.NONE) ->
                        mService.changeLastChar(
                            if (!isSoftCancel && skkPrefs.useSoftTransKey) SKKEngine.LAST_CONVERSION_TRANS
                            else SKKEngine.LAST_CONVERSION_SMALL
                        )

                    EnumSet.of(FlickState.LEFT) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_DAKUTEN)
                    EnumSet.of(if (isSoftCancel) FlickState.NONE else FlickState.UP) -> mService.handleCancel()
                    EnumSet.of(FlickState.RIGHT) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_HANDAKUTEN)
                    EnumSet.of(FlickState.DOWN) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_SHIFT)
                }
            }

            KEYCODE_FLICK_JP_MOJI -> when (mFlickState) {
                EnumSet.of(FlickState.NONE) -> mService.processKey(
                    if (isShifted) skkPrefs.hankakuKanaKey else skkPrefs.katakanaKey
                )

                EnumSet.of(FlickState.LEFT) -> mService.processKey(':')
                EnumSet.of(FlickState.UP) -> if (keyboard !== mNumKeyboard) {
                    keyboard = mNumKeyboard; isHankaku = false
                }

                EnumSet.of(FlickState.RIGHT) -> mService.processKey('>')
                EnumSet.of(FlickState.DOWN) -> if (keyboard !== mVoiceKeyboard) {
                    keyboard = mVoiceKeyboard; isHankaku = false
                }
            }

            KEYCODE_FLICK_JP_TO_KANA -> if (keyboard !== mJPKeyboard) {
                keyboard = mJPKeyboard
                isHankaku = mService.kanaState == SKKHanKanaState

                // Godan使用中に入力欄の関係でテンキーになった場合などの復帰
                if (skkPrefs.preferGodan) mService.changeSoftKeyboard(SKKHiraganaState)
            }

            KEYCODE_FLICK_JP_TO_QWERTY -> when (mFlickState) {
                EnumSet.of(FlickState.NONE) -> mService.processKey(
                    if (isShifted) skkPrefs.abbrevKey else skkPrefs.asciiKey
                )

                EnumSet.of(FlickState.LEFT) -> mService.emojiCandidates()
                    .also { consumesShift = false }

                EnumSet.of(FlickState.UP) -> mService.processKey(skkPrefs.zenkakuKey)
                EnumSet.of(FlickState.RIGHT) -> mService.symbolCandidates()
                    .also { consumesShift = false }

                EnumSet.of(FlickState.DOWN) -> mService.changeSoftKeyboard(SKKASCIIState)
            }

            KEYCODE_FLICK_JP_SPEECH -> mService.recognizeSpeech()
            KEYCODE_FLICK_JP_PASTE -> mService.pasteClip()
            KEYCODE_FLICK_JP_GOOGLE -> mService.googleTransliterate()
            KEYCODE_FLICK_JP_CHAR_A, KEYCODE_FLICK_JP_CHAR_KA, KEYCODE_FLICK_JP_CHAR_SA,
            KEYCODE_FLICK_JP_CHAR_TA, KEYCODE_FLICK_JP_CHAR_NA, KEYCODE_FLICK_JP_CHAR_HA,
            KEYCODE_FLICK_JP_CHAR_MA, KEYCODE_FLICK_JP_CHAR_YA, KEYCODE_FLICK_JP_CHAR_RA,
            KEYCODE_FLICK_JP_CHAR_WA, KEYCODE_FLICK_JP_CHAR_TEN,
            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED,
            KEYCODE_FLICK_JP_CHAR_TEN_NUM, KEYCODE_FLICK_JP_CHAR_TEN_NUM_LEFT
                -> processFlickForLetter(mLastPressedKey, mFlickState)
        }

        if (consumesShift) {
            isShifted = false
            onSetShifted()
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

    override fun onRelease(primaryCode: Int) {
        mArrowFlicked = false
    }

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
                val chosen = array[which].removeSurrounding(leftSymbol, rightSymbol)
                mService.commitTextSKK(prefix)
                if (chosen.isNotEmpty()) {
                    mService.commitTextSKK(chosen)
                }
                mService.commitTextSKK(suffix)
            }
        }

        val dialog = dialogBuilder.create()
        dialog.window?.let {
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            it.attributes?.token = this.windowToken
            it.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        }
        dialog.show()
    }

    private fun extractCommon(list: ArrayList<String>): Triple<String, Array<String>, String> {
        var commonPrefix = list.first()
        list.forEach { commonPrefix = it.commonPrefixWith(commonPrefix) }

        val remaining = list.map { it.substring(commonPrefix.length) }
        var commonSuffix = remaining.first()
        remaining.forEach { commonSuffix = it.commonSuffixWith(commonSuffix) }

        val array = remaining.map {
            leftSymbol + it.substring(0, it.length - commonSuffix.length) + rightSymbol
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
        private const val KEYCODE_FLICK_JP_TO_QWERTY = -1003
        private const val KEYCODE_FLICK_JP_SPACE = -1004
        private const val KEYCODE_FLICK_JP_MOJI = -1005
        private const val KEYCODE_FLICK_JP_KOMOJI = -1006
        private const val KEYCODE_FLICK_JP_ENTER = -1007

        //private const val KEYCODE_FLICK_JP_SEARCH = -1008
        //private const val KEYCODE_FLICK_JP_CANCEL = -1009
        private const val KEYCODE_FLICK_JP_TO_KANA = -1010
        private const val KEYCODE_FLICK_JP_PASTE = -1011
        private const val KEYCODE_FLICK_JP_SPEECH = -1012
        private const val KEYCODE_FLICK_JP_GOOGLE = -1013

        private enum class FlickState { NONE, LEFT, UP, RIGHT, DOWN, CURVE_LEFT, CURVE_RIGHT }
    }
}
