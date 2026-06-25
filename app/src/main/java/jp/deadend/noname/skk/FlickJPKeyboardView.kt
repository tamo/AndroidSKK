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
import jp.deadend.noname.skk.engine.convertTo
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
    private var mCurrentPopupLabels = Array(15) { "" }

    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
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

    private val mKutoutenKey: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_KUTOUTEN))
        { "BUG: no kutouten key" }
    }
    private val mShiftKeyJP: Keyboard.Key by lazy { mJPKeyboard.keys[0] }
    private val mShiftKeyNum: Keyboard.Key by lazy { mNumKeyboard.keys[0] }
    private val mShiftKeyVoice: Keyboard.Key by lazy { mVoiceKeyboard.keys[0] }
    private val mKanaKeyJP: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mJPKeyboard, KEYCODE_FLICK_JP_MOJI)) { "BUG: no moji key" }
    }
    private val mKanaKeyNum: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mNumKeyboard, KEYCODE_FLICK_JP_TO_KANA))
        { "BUG: no kana key in num" }
    }
    private val mKanaKeyVoice: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mVoiceKeyboard, KEYCODE_FLICK_JP_TO_KANA))
        { "BUG: no kana key in voice" }
    }

    private val mLSymKeyNum: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mNumKeyboard, KEYCODE_NUM_SYM_L))
        { "BUG: no left sym key in num" }
    }
    private val mRSymKeyNum: Keyboard.Key by lazy {
        checkNotNull(findKeyByCode(mNumKeyboard, KEYCODE_NUM_SYM_R))
        { "BUG: no right sym key in num" }
    }

    //フリックガイドTextView用
    private val mFlickGuideLabelList = SparseArray<Array<String>>()

    init {
        mapOf(
            Keyboard.KEYCODE_SHIFT to flickLabels("SHIFT", "", "CAPSLOCK"),
            KEYCODE_FLICK_JP_CHAR_A to expandLabels("あいうえお小.小.小゛小.小."),
            KEYCODE_FLICK_JP_CHAR_KA to expandLabels("かきくけこ.゛.゛.゛.゛.゛"),
            KEYCODE_FLICK_JP_CHAR_SA to expandLabels("さしすせそ.゛.゛.゛.゛.゛"),
            KEYCODE_FLICK_JP_CHAR_TA to expandLabels("たちつてと.゛.゛小゛.゛.゛"),
            KEYCODE_FLICK_JP_CHAR_NA to expandLabels("なにぬねの"),
            KEYCODE_FLICK_JP_CHAR_HA to expandLabels("はひふへほ゜゛゜゛゜゛゜゛゜゛"),
            KEYCODE_FLICK_JP_CHAR_MA to expandLabels("まみむめも"),
            KEYCODE_FLICK_JP_CHAR_YA to expandLabels("や（ゆ）よ小.「『小.」』小."),
            KEYCODE_FLICK_JP_CHAR_RA to expandLabels("らりるれろ"),
            KEYCODE_FLICK_JP_CHAR_WA to expandLabels("わをんー～小"),
            KEYCODE_FLICK_JP_KUTOUTEN to expandLabels("、。？！…"),
            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED to expandLabels("　（「）」"),
            KEYCODE_NUM_SYM_L to expandLabels("＃￥＋＄＊....（）"),
            KEYCODE_NUM_SYM_L_SHIFTED to expandLabels("＃\\＋＄×....（）"),
            KEYCODE_NUM_SYM_R to expandLabels("，．－：／....｛｝"),
            KEYCODE_NUM_SYM_R_SHIFTED to expandLabels("，．－：÷....｛｝"),
            KEYCODE_FLICK_JP_MOJI to flickLabels("カナ", "：", "10", "＞", "声"),
            KEYCODE_FLICK_JP_TO_QWERTY to flickLabels("abc", "絵☻", "全角ａ", "記号", "qwerty"),
            KEYCODE_FLICK_JP_LEFT to expandLabels("＜←↑→↓"),
            KEYCODE_FLICK_JP_RIGHT to expandLabels("＞←↑→↓"),
            KEYCODE_FLICK_JP_SPACE to flickLabels("SPACE", "", "Mush"),
            KEYCODE_FLICK_JP_TO_KANA to flickLabels("かな"),
            KEYCODE_FLICK_JP_PASTE to flickLabels("貼付"),
            KEYCODE_FLICK_JP_GOOGLE to flickLabels("Google")
        ).forEach { (code, labels) -> mFlickGuideLabelList.append(code, labels) }
    }

    private fun expandLabels(labels: String): Array<String> =
        flickLabels(*labels.map { if (it == '.') "" else it.toString() }.toTypedArray())

    private fun flickLabels(vararg labels: String): Array<String> {
        val arr = Array(15) { "" }
        labels.forEachIndexed { i, s -> if (i < 15) arr[i] = s }
        return arr
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

        shiftKeys.forEachIndexed { index, shiftKey ->
            shiftKey.apply {
                labels.main = ""
                if (index == 2) { // voice
                    codes.main = intArrayOf(0, 0)
                    icon = null
                } else {
                    codes.main = intArrayOf(Keyboard.KEYCODE_SHIFT, Keyboard.KEYCODE_CAPSLOCK)
                    icon = ResourcesCompat
                        .getDrawable(resources, R.drawable.ic_keyboard_shift, null)?.also {
                            it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
                        }
                }
            }
        }

        kanaKeys.forEachIndexed { i, kanaKey ->
            val code = if (i == 0) KEYCODE_FLICK_JP_MOJI else KEYCODE_FLICK_JP_TO_KANA
            kanaKey.codes.main = intArrayOf(code) // size==2 になると文字が大きくなってしまう
            kanaKey.icon = null
        }

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
        updateKeyLabels()
        return this
    }

    private fun findKeyByCode(keyboard: Keyboard, code: Int) =
        keyboard.keys.find { it.codes.main[0] == code }

    private fun getFlickLabel(code: Int): String {
        val labels = mFlickGuideLabelList[code] ?: return ""
        if (labels.size < 5 || (labels[1].isEmpty() && labels[2].isEmpty() && labels[3].isEmpty() && labels[4].isEmpty())) {
            return labels[0]
        }
        return "${labels[2]}\n${labels[1]}${labels[0]}${labels[3]}\n${labels[4]}"
    }

    private fun updateKeyLabels() {
        val kanaState = mService.kanaState

        // 以前の実装では onSetShifted という名前だった部分
        mKutoutenKey.codes.main[0] =
            if (isShifted) KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED else KEYCODE_FLICK_JP_KUTOUTEN
        mLSymKeyNum.codes.main[0] = if (isShifted) KEYCODE_NUM_SYM_L_SHIFTED else KEYCODE_NUM_SYM_L
        mRSymKeyNum.codes.main[0] = if (isShifted) KEYCODE_NUM_SYM_R_SHIFTED else KEYCODE_NUM_SYM_R

        mJPKeyboard.keys.forEach { key ->
            when (key.codes.main[0]) {
                KEYCODE_FLICK_JP_LEFT, KEYCODE_FLICK_JP_PASTE ->
                    key.codes.main[0] =
                        if (isShifted) KEYCODE_FLICK_JP_PASTE else KEYCODE_FLICK_JP_LEFT

                KEYCODE_FLICK_JP_RIGHT, KEYCODE_FLICK_JP_GOOGLE ->
                    key.codes.main[0] =
                        if (isShifted) KEYCODE_FLICK_JP_GOOGLE else KEYCODE_FLICK_JP_RIGHT
            }
        }

        // 文字キーは現在の state ではなく、押したときの変化先 state を示す
        mFlickGuideLabelList[KEYCODE_FLICK_JP_MOJI][0] = when {
            isShifted -> if (kanaState is SKKHanKanaState) "カナ" else "ｶﾅ"
            else -> if (kanaState is SKKHiraganaState) "カナ" else "かな"
        }

        // ここで全キーボードの全キーラベルを設定
        for (kb in listOf(mJPKeyboard, mNumKeyboard, mVoiceKeyboard)) {
            for (key in kb.keys) {
                val code = key.codes.main[0]
                val labels = mFlickGuideLabelList[code] ?: continue

                // Get the main label
                val label = when (code) {
                    // シフトで変化する例外
                    KEYCODE_FLICK_JP_TO_QWERTY -> if (isShifted) "全角ａ\n☻略記\nqwerty" else "全角ａ\n☻abc記\nqwerty"
                    // 1行だけになる例外
                    KEYCODE_FLICK_JP_TO_KANA, KEYCODE_FLICK_JP_PASTE, KEYCODE_FLICK_JP_GOOGLE,
                    in KEYCODE_FLICK_JP_CHAR_A downTo KEYCODE_FLICK_JP_CHAR_RA -> labels[0]

                    // if でガードされた例外条件を先に記述
                    KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED if isShifted -> "「\n（□）\n」"
                    KEYCODE_FLICK_JP_SPACE if isShifted -> "設定"
                    KEYCODE_FLICK_JP_KOMOJI if !skkPrefs.useSoftCancelKey && skkPrefs.useSoftTransKey -> "CXL\n ◻゙□゚ \n▽"
                    // その後で一般的な処理
                    KEYCODE_FLICK_JP_CHAR_WA, KEYCODE_FLICK_JP_MOJI, KEYCODE_FLICK_JP_KOMOJI,
                    KEYCODE_FLICK_JP_KUTOUTEN, KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED,
                    KEYCODE_NUM_SYM_L, KEYCODE_NUM_SYM_L_SHIFTED,
                    KEYCODE_NUM_SYM_R, KEYCODE_NUM_SYM_R_SHIFTED -> getFlickLabel(code)

                    else -> ""
                }

                // 現在の state で表示するキーは調整が必要
                key.labels.main = when (code) {
                    in KEYCODE_FLICK_JP_CHAR_A downTo KEYCODE_FLICK_JP_CHAR_WA,
                    KEYCODE_FLICK_JP_TO_KANA -> label.convertTo(kanaState)

                    else -> label
                }
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
        setShiftPosition() // skkPrefs.changeShift

        mFlickGuideLabelList.put(
            KEYCODE_FLICK_JP_KUTOUTEN, when (skkPrefs.kutoutenType) {
                "en" -> flickLabels("，", "．", "？", "！", "…")
                "jp_en" -> flickLabels("，", "。", "？", "！", "…")
                else -> flickLabels("、", "。", "？", "！", "…")
            }
        )

        mFlickGuideLabelList.put(
            KEYCODE_FLICK_JP_KOMOJI, when {
                skkPrefs.useSoftCancelKey -> flickLabels("CXL", " ◻゙", "小", "◻゚ ", "▽")
                skkPrefs.useSoftTransKey -> flickLabels("◻゙□゚", " ◻゙", "CXL", "◻゚ ", "▽")
                else -> flickLabels("小", " ◻゙", "CXL", "◻゚ ", "▽")
            }
        )

        updateKeyLabels()

        if (skkPrefs.usePopup && mPopup == null) {
            mPopup = createPopupGuide(context).also { popup ->
                mPopupTextView = PopupFlickguideBinding.bind(popup.contentView).run {
                    arrayOf(
                        labelA, labelI, labelU, labelE, labelO,
                        labelLeftA, labelRightA, labelLeftI, labelRightI, labelLeftU,
                        labelRightU, labelLeftE, labelRightE, labelLeftO, labelRightO
                    )
                }
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
        if (!skkPrefs.usePopup) return

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
                (1..4).forEach { labels[it].text = mCurrentPopupLabels[it] }
            }
            labels[5].text = mCurrentPopupLabels[5]
            labels[6].text = mCurrentPopupLabels[6]
        } else {
            if (!isCurve(mFlickState)) labels[0].text = mCurrentPopupLabels[0]
            labels[baseIndex].text = mCurrentPopupLabels[baseIndex]
            labels[baseIndex * 2 + 5].text = mCurrentPopupLabels[baseIndex * 2 + 5]
            labels[baseIndex * 2 + 6].text = mCurrentPopupLabels[baseIndex * 2 + 6]
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
                val keyIndex = getKeyIndex(me.x.toInt() - paddingLeft, me.y.toInt() - paddingTop)
                checkMultiTap(me.eventTime, keyIndex)
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

                if (skkPrefs.usePopup) setupPopupTextView()
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
        val baseIndex = when {
            mFlickState.contains(FlickState.LEFT) -> 1
            mFlickState.contains(FlickState.UP) -> 2
            mFlickState.contains(FlickState.RIGHT) -> 3
            mFlickState.contains(FlickState.DOWN) -> 4
            else -> 0
        }
        val hasLeftCurve = mCurrentPopupLabels[baseIndex * 2 + 5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[baseIndex * 2 + 6].isNotEmpty()

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
        if (mFlickState != newState && (!isCurve(newState) ||
                    (hasLeftCurve && isLeftCurve(newState)) ||
                    (hasRightCurve && isRightCurve(newState)))
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
            KEYCODE_FLICK_JP_CHAR_A -> return when {
                isLeftCurve(flick) -> {
                    if (mService.engineState.hasCandidates) mService.handleEnter()
                    mService.processKey('x')
                    mService.processKey(vowel)
                }

                flick == EnumSet.of(FlickState.UP, FlickState.CURVE_RIGHT)
                        && !mService.isHiragana -> {
                    mService.processKey('v')
                    mService.processKey('u')
                }

                else -> mService.processKey(if (isShifted) vowel.uppercaseChar() else vowel)
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
                val isLeft = flick.contains(FlickState.LEFT)
                val isRight = flick.contains(FlickState.RIGHT)
                if (isLeft || isRight) {
                    if (isRightCurve(flick)) mService.processKey('z')
                    mService.processKey(
                        if (isLeft) if (isCurve(flick)) '[' else '('
                        else /*isRight*/ if (isCurve(flick)) ']' else ')'
                    )
                    return
                } else 'y'
            }

            KEYCODE_FLICK_JP_CHAR_RA -> 'r'
            KEYCODE_FLICK_JP_CHAR_WA -> return when (flick) {
                EnumSet.of(FlickState.NONE) -> {
                    mService.processKey(if (isShifted) 'W' else 'w')
                    mService.processKey('a')
                }

                EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT) -> {
                    if (mService.engineState.hasCandidates) mService.handleEnter()
                    mService.processKey(if (isShifted) 'X' else 'x')
                    mService.processKey('w'); mService.processKey('a')
                }

                EnumSet.of(FlickState.LEFT) -> {
                    mService.processKey('w')
                    mService.processKey('o')
                }

                EnumSet.of(FlickState.UP) -> {
                    mService.processKey(if (isShifted) 'N' else 'n')
                    mService.processKey('n')
                }

                EnumSet.of(FlickState.RIGHT) -> mService.processKey('-')
                EnumSet.of(FlickState.DOWN) -> mService.processKey('~')
                else -> throw (IllegalStateException())
            }

            KEYCODE_FLICK_JP_KUTOUTEN -> return when {
                flick.contains(FlickState.NONE) -> mService.processKey(',')
                flick.contains(FlickState.LEFT) -> mService.processKey('.')
                flick.contains(FlickState.UP) -> mService.processKey('?')
                flick.contains(FlickState.RIGHT) -> mService.processKey('!')
                flick.contains(FlickState.DOWN) -> {
                    mService.processKey('z')
                    mService.processKey('.')
                }

                else -> throw (IllegalStateException())
            }

            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED -> return when {
                flick.contains(FlickState.NONE) -> mService.processKeyIn(SKKZenkakuState, ' ')
                flick.contains(FlickState.LEFT) -> mService.processKey('(')
                flick.contains(FlickState.UP) -> mService.processKey('[')
                flick.contains(FlickState.RIGHT) -> mService.processKey(')')
                flick.contains(FlickState.DOWN) -> mService.processKey(']')
                else -> throw (IllegalStateException())
            }

            KEYCODE_NUM_SYM_L, KEYCODE_NUM_SYM_L_SHIFTED -> return mService.commitTextSKK(
                when {
                    flick.contains(FlickState.NONE) -> if (isShifted) "＃" else "#"
                    flick.contains(FlickState.LEFT) -> if (isShifted) "\\" else "￥"
                    flick.contains(FlickState.UP) -> when {
                        isLeftCurve(flick) -> if (isShifted) "（" else "("
                        isRightCurve(flick) -> if (isShifted) "）" else ")"
                        else -> if (isShifted) "＋" else "+"
                    }

                    flick.contains(FlickState.RIGHT) -> if (isShifted) "＄" else "$"
                    flick.contains(FlickState.DOWN) -> if (isShifted) "×" else "*"
                    else -> ""
                }
            )

            KEYCODE_NUM_SYM_R, KEYCODE_NUM_SYM_R_SHIFTED -> return mService.commitTextSKK(
                when {
                    flick.contains(FlickState.NONE) -> if (isShifted) "，" else ","
                    flick.contains(FlickState.LEFT) -> if (isShifted) "．" else "."
                    flick.contains(FlickState.UP) -> when {
                        isLeftCurve(flick) -> if (isShifted) "｛" else "{"
                        isRightCurve(flick) -> if (isShifted) "｝" else "}"
                        else -> if (isShifted) "－" else "-"
                    }

                    flick.contains(FlickState.RIGHT) -> if (isShifted) "：" else ":"
                    flick.contains(FlickState.DOWN) -> if (isShifted) "÷" else "/"
                    else -> ""
                }
            )

            else -> null
        }

        consonant?.let { c ->
            mService.apply {
                suspendCompletion()
                processKey(if (isShifted) c.uppercaseChar() else c)
                if (isLeftCurve(flick) && (c == 't' && vowel == 'u' || c == 'y' && vowel in "auo")) {
                    processKey(vowel)
                    resumeCompletion()
                    changeLastChar(SKKEngine.LAST_CONVERSION_SMALL)
                } else {
                    resumeCompletion()
                    processKey(vowel)
                }
            }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean = when {
        key.codes.main[0] == KEYCODE_FLICK_JP_ENTER -> {
            mService.pressSearch()
            true
        }

        skkPrefs.usePopup && !skkPrefs.popupOnPress && showPopup() -> true

        else -> super.onLongPress(key)
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
            if (i > 14) return@forEachIndexed
            mCurrentPopupLabels[i] = when {
                // 「う」には濁点を付けず「ウ」にだけ付ける
                primaryCode == KEYCODE_FLICK_JP_CHAR_A && i == 10 && mService.isHiragana -> ""
                else -> label.convertTo(mService.kanaState, reversed = true)
            }
        }

        if (skkPrefs.usePopup && skkPrefs.popupOnPress) {
            showPopup()
        }
    }

    private fun showPopup(): Boolean {
        if (mCurrentPopupLabels[0] == "") return false

        // FlickJP は mPopupTextView の内容をロジックに使わない
        setupPopupTextView()

        val (x, y) = calculatePopupPos()
        mPopup?.showAtLocation(this, android.view.Gravity.NO_GRAVITY, x, y)

        // true だと release して repeat が終わってしまうので
        return !(findKeyByCode(mJPKeyboard, mLastPressedKey)?.repeatable ?: false)
    }

    private fun calculatePopupPos(): Pair<Int, Int> {
        val scale = context.resources.displayMetrics.density
        val size = (mPopupSize * scale + 0.5f).toInt()
        val fingerOffset = size * skkPrefs.fingerOffset / 100
        getLocationInWindow(mCoordinates)
        return if (skkPrefs.useFixedPopup)
            mCoordinates[0] + this.width / 2 - size / 2 to
                    mCoordinates[1] - size - fingerOffset
        else mFlickStartX.toInt() + mCoordinates[0] - size / 2 to
                mFlickStartY.toInt() + mCoordinates[1] - size / 2 - fingerOffset
    }

    override fun onKey(primaryCode: Int) = when (primaryCode) {
        // repeatable なキーはここで処理する必要がある
        Keyboard.KEYCODE_DELETE
            if !mService.handleBackspace() -> mService.pressDel()

        KEYCODE_FLICK_JP_LEFT if !mArrowFlicked &&
                !mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT) ->
            mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

        KEYCODE_FLICK_JP_RIGHT if !mArrowFlicked &&
                !mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT) ->
            mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

        KEYCODE_FLICK_JP_SPACE if isShifted -> {
            val intent = Intent(context, SKKSettingsActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        KEYCODE_FLICK_JP_SPACE if mFlickState == EnumSet.of(FlickState.NONE) ->
            mService.processKey(' ')

        // Num等、直接入力するASCIIキー: release で処理してもいいかも
        in 32..126 ->
            mService.processKey(primaryCode)

        else -> {}
    }

    private fun release() {
        var consumesShift = true
        when (mLastPressedKey) {
            // repeatable のフリック
            KEYCODE_FLICK_JP_SPACE -> if (mFlickState == EnumSet.of(FlickState.UP))
                mService.sendToMushroom()

            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> {
                if (mFlickState == EnumSet.of(FlickState.UP) || mInMultiTap && mTapCount != -1) {
                    isCapsLocked = true; isShifted = true
                } else {
                    isShifted = !isShifted; isCapsLocked = false
                }
                updateKeyLabels()
                consumesShift = false
            }

            KEYCODE_FLICK_JP_ENTER -> if (!mService.handleEnter())
                mService.pressEnter()

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
            KEYCODE_FLICK_JP_CHAR_WA, KEYCODE_FLICK_JP_KUTOUTEN,
            KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED,
            KEYCODE_NUM_SYM_L, KEYCODE_NUM_SYM_L_SHIFTED,
            KEYCODE_NUM_SYM_R, KEYCODE_NUM_SYM_R_SHIFTED
                -> processFlickForLetter(mLastPressedKey, mFlickState)
        }

        if (consumesShift && !isCapsLocked) {
            isShifted = false
            updateKeyLabels()
        }

        mLastPressedKey = KEYCODE_FLICK_JP_NONE
        mFlickState = EnumSet.of(FlickState.NONE)
        mFlickStartX = -1f
        mFlickStartY = -1f
        mPopup?.dismiss()
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

        val dialog = AlertDialog.Builder(context, R.style.Theme_SKK).apply {
            setTitle("${prefix}${leftSymbol} ${rightSymbol}${suffix}")
            setItems(array) { _, which ->
                val chosen = array[which].removeSurrounding(leftSymbol, rightSymbol)
                mService.commitTextSKK(prefix)
                if (chosen.isNotEmpty()) {
                    mService.commitTextSKK(chosen)
                }
                mService.commitTextSKK(suffix)
            }
        }.create()

        dialog.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            attributes?.token = this@FlickJPKeyboardView.windowToken
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
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
        private const val KEYCODE_FLICK_JP_KUTOUTEN = -211
        private const val KEYCODE_FLICK_JP_CHAR_TEN_SHIFTED = -212
        private const val KEYCODE_NUM_SYM_L = -213
        private const val KEYCODE_NUM_SYM_R = -214
        private const val KEYCODE_NUM_SYM_L_SHIFTED = -215
        private const val KEYCODE_NUM_SYM_R_SHIFTED = -216
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
