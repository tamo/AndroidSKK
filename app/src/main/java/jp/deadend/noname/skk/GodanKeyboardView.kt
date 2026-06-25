package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import jp.deadend.noname.skk.databinding.PopupFlickguideBinding
import jp.deadend.noname.skk.engine.RomajiConverter.getVowel
import jp.deadend.noname.skk.engine.SKKEngine
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import jp.deadend.noname.skk.engine.convertTo
import java.util.EnumSet
import kotlin.math.ceil

class GodanKeyboardView(context: Context, attrs: AttributeSet?) : KeyboardView(context, attrs),
    KeyboardView.OnKeyboardActionListener {
    private var mLastPressedKey = KEYCODE_GODAN_NONE
    private var mFlickState = EnumSet.of(FlickState.NONE)
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private val mArrowPressed: Boolean
        get() = (mLastPressedKey == KEYCODE_GODAN_LEFT || mLastPressedKey == KEYCODE_GODAN_RIGHT)
    private var mArrowFlicked = false
    private var mArrowStartX = -1f
    private var mArrowStartY = -1f
    private var mCurrentPopupLabels = Array(15) { "" }

    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mCoordinates = IntArray(2)

    // シンプル切り替え用
    private var mIsASCII = false

    //フリックガイドTextView用
    private val mFlickGuideLabelList = SparseArray<Array<String>>()

    init {
        val t = POPUP_LABELS_NULL
        mapOf(
            KEYCODE_GODAN_CANCEL to (arrayOf("CXL", "：", "貼", "＞", "G検") + t),
            KEYCODE_GODAN_CHAR_A to (arrayOf("A", "あっ", "あん", "ゃ", "1") + t),
            KEYCODE_GODAN_CHAR_K to (arrayOf("K", "＠", "Q", "G", "2", "", "", "□", "＾") + t),
            KEYCODE_GODAN_CHAR_H to (arrayOf("H", "P", "F", "B", "3") + t),
            KEYCODE_GODAN_CHAR_I to (arrayOf("I", "いっ", "いん", "ぃ", "4") + t),
            KEYCODE_GODAN_CHAR_S to (arrayOf("S", "＆", "J", "Z", "5", "", "", "％", "＄") + t),
            KEYCODE_GODAN_CHAR_M to
                    (arrayOf("M", "／", "L", "ー", "6", "", "", "￥", "＼", "", "", "〜", "＿") + t),
            KEYCODE_GODAN_CHAR_U to (arrayOf("U", "うっ", "うん", "ゅ", "7") + t),
            KEYCODE_GODAN_CHAR_T to (arrayOf("T", "＋", "C", "D", "8", "", "", "＃", "＊") + t),
            KEYCODE_GODAN_CHAR_Y to
                    (arrayOf("Y", "（", "X", "）", "9", "＜", "＞", "［", "｛", "", "", "］", "｝") + t),
            KEYCODE_GODAN_CHAR_Q to (arrayOf("Q", "絵☻", "^J", "記号", "ｶﾅ") + t),
            KEYCODE_GODAN_CHAR_E to (arrayOf("E", "えっ", "えん", "ぇ", "00") + t),
            KEYCODE_GODAN_CHAR_N to
                    (arrayOf("N", "：", "ん", "・", "0", "", "", "；", "＜", "", "", "｜", "＞") + t),
            KEYCODE_GODAN_CHAR_R to
                    arrayOf("R", "。", "？", "！", "、", "", "", "…", "〜", "", "", "↑", "↓", "→", "←"),
            KEYCODE_GODAN_CHAR_O to (arrayOf("O", "おっ", "おん", "ょ", "＇", "′", "`") + t),
            KEYCODE_GODAN_CHAR_W to
                    arrayOf("W", "「", "V", "」", "＂", "", "", "『", "【", "", "", "』", "】", "”", "“"),
            KEYCODE_GODAN_SPACE to (arrayOf("SPACE", "", "Mush") + t),
            Keyboard.KEYCODE_SHIFT to (arrayOf("SHIFT", "", "CAPSLOCK") + t)
        ).forEach { (code, labels) -> mFlickGuideLabelList.append(code, labels) }
    }

    override fun setService(service: SKKService) {
        super.setService(service)
        onKeyboardActionListener = this
        isPreviewEnabled = false
        setBackgroundColor(0x00000000)

        keyboard = Keyboard(
            context,
            if (!mIsASCII && skkPrefs.simpleGodan) R.xml.keys_godan_simple else R.xml.keys_godan,
            mService.mRootWidth,
            mService.mScreenHeight
        )
        setKeyState(mService.engineState)
    }

    override fun onDetachedFromWindow() {
        mPopup?.dismiss()
        super.onDetachedFromWindow()
        isShifted = false
        isCapsLocked = false
    }

    private fun setShiftPosition() {
        val defaultShiftKey = keyboard.keys[0]
        val defaultCancelKey =
            checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CANCEL)) { "BUG: no cancel key" }
        val (shiftKey, cancelKey) = if (!skkPrefs.changeShift) {
            defaultShiftKey to defaultCancelKey
        } else {
            defaultCancelKey to defaultShiftKey
        }

        shiftKey.codes.main[0] = Keyboard.KEYCODE_SHIFT
        shiftKey.codes.main[1] = Keyboard.KEYCODE_CAPSLOCK
        shiftKey.labels.main = ""
        shiftKey.icon = ResourcesCompat.getDrawable(
            resources, R.drawable.ic_keyboard_shift, null
        )?.also {
            it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
        }

        cancelKey.codes.main[0] = KEYCODE_GODAN_CANCEL
        cancelKey.codes.main[1] = KEYCODE_GODAN_NONE
        cancelKey.labels.main = /* if (!mIsASCII && skkPrefs.simpleGodan) "cxl" else */
            "貼付\n：cxl＞\ngoogle"
        cancelKey.icon = null

        keyboard.reloadShiftKeys()
    }

    private fun setCancelPosition() {
        val oldCancelKey =
            checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CANCEL)) { "BUG: no cancel key" }
        val oldQKey =
            checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CHAR_Q)) { "BUG: no Q key" }

        val (cancelKey, qKey) = if (skkPrefs.swapQCxl) {
            oldQKey to oldCancelKey
        } else {
            oldCancelKey to oldQKey
        }

        cancelKey.codes.main[0] = KEYCODE_GODAN_CANCEL
        cancelKey.labels.main = /* if (!mIsASCII && skkPrefs.simpleGodan) "cxl" else */
            "貼付\n：cxl＞\ngoogle"

        qKey.codes.main[0] = KEYCODE_GODAN_CHAR_Q
        qKey.labels.main = /* if (!mIsASCII && skkPrefs.simpleGodan) "Q" else */ "^J\n☻Q記\n半ｶﾅ"
    }

    override fun setKeyState(state: SKKState): GodanKeyboardView {
        val wasASCII = mIsASCII
        mIsASCII = !state.isJapanese
        if (wasASCII != mIsASCII) {
            prepareNewKeyboard(context, width, height)
        }

        val qKey = checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CHAR_Q)) { "BUG: no Q key" }
        qKey.on = (state.isJapanese && !mService.isHiragana)
            .also {
                listOf(
                    KEYCODE_GODAN_CHAR_A, KEYCODE_GODAN_CHAR_I, KEYCODE_GODAN_CHAR_U,
                    KEYCODE_GODAN_CHAR_E, KEYCODE_GODAN_CHAR_O, KEYCODE_GODAN_CHAR_N,
                ).forEach { keyCode ->
                    val key =
                        checkNotNull(findKeyByCode(keyboard, keyCode)) { "BUG: no $keyCode key" }
                    val label = key.labels.main
                    key.labels.main = label.convertTo(mService.kanaState)
                }
            }

        val lKey = checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CHAR_L)) { "BUG: no L key" }
        lKey.on = mIsASCII

        isZenkaku = (state is SKKZenkakuState)

        invalidateAllKeys()
        return this
    }

    private fun findKeyByCode(keyboard: Keyboard, code: Int) =
        keyboard.keys.find { it.codes.main[0] == code }

    private fun onSetShifted(isShifted: Boolean) {
        val spaceKey =
            checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_SPACE)) { "BUG: no space key" }
        spaceKey.labels.main = if (isShifted) "設定" else ""
    }

    internal fun prepareNewKeyboard(
        context: Context,
        widthPixel: Int,
        heightPixel: Int
    ) {
        keyboard = Keyboard(
            context,
            if (!mIsASCII && skkPrefs.simpleGodan) R.xml.keys_godan_simple else R.xml.keys_godan,
            mService.mRootWidth,
            mService.mScreenHeight
        )
        keyboard.resize(widthPixel, heightPixel)
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
        setShiftPosition() // まず skkPrefs.changeShift
        setCancelPosition() // その後で skkPrefs.swapQCxl を反映

        val (label, guide) = when {
            skkPrefs.useSoftCancelKey -> "小\n ◻゙cxl◻゚ \n▽" to
                    arrayOf("CXL", "◻゙", "小", "◻゚", "▽") + POPUP_LABELS_NULL

            skkPrefs.useSoftTransKey -> "cxl\n ◻゙□゚ \n▽" to
                    arrayOf("◻゙□゚", "◻゙", "CXL", "◻゚", "▽") + POPUP_LABELS_NULL

            else -> "cxl\n ◻゙小◻゚ \n▽" to
                    arrayOf("小", "◻゙", "CXL", "◻゚", "▽") + POPUP_LABELS_NULL
        }
        findKeyByCode(keyboard, KEYCODE_GODAN_KOMOJI)?.labels?.main = label
        mFlickGuideLabelList.put(KEYCODE_GODAN_KOMOJI, guide)

        // ポップアップは表示するしないにかかわらず作成しロジックに使用する
        if (mPopup == null) {
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

        val scale = context.resources.displayMetrics.density
        val size = (mPopupSize * scale + 0.5f).toInt()

        val popup = PopupWindow(view, size, size)
        //~ popup.setWindowLayoutMode(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        popup.animationStyle = 0

        return popup
    }

    private fun setupPopupTextView() {
        val labels = checkNotNull(mPopupTextView) { "BUG: popup labels are null!!" }
        labels.forEach {
            it.text = ""
            it.setBackgroundResource(R.drawable.popup_label)
        }

        // 基本配置
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
                val dx2 = dx * dx
                val dy2 = dy * dy

                when {
                    dx2 + dy2 < mFlickSensitivitySquared -> {
                        if (mFlickState != EnumSet.of(FlickState.NONE)) {
                            mFlickState = EnumSet.of(FlickState.NONE)
                            performHapticFeedback(skkPrefs.haptic)
                        }
                        mArrowFlicked = false
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
        val hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()

        val newState = when (val dAngle = diamondAngle(dx, dy)) {
            in 0.5f..1.5f -> EnumSet.of(FlickState.DOWN)
            in 1.5f..2.29f -> EnumSet.of(FlickState.LEFT)
            in 2.29f..2.71f -> when {
                hasLeftCurve -> EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT)
                dAngle < 2.5f -> EnumSet.of(FlickState.LEFT)
                else -> EnumSet.of(FlickState.UP)
            }

            in 2.71f..3.29f -> EnumSet.of(FlickState.UP)
            in 3.29f..3.71f -> when {
                hasRightCurve -> EnumSet.of(FlickState.NONE, FlickState.CURVE_RIGHT)
                dAngle < 3.5f -> EnumSet.of(FlickState.UP)
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
        val left = when {
            mFlickState.contains(FlickState.NONE) -> 0
            mFlickState.contains(FlickState.LEFT) -> 1
            mFlickState.contains(FlickState.UP) -> 2
            mFlickState.contains(FlickState.RIGHT) -> 3
            mFlickState.contains(FlickState.DOWN) -> 4
            else -> throw IllegalStateException("mFlickState is $mFlickState")
        } * 2 + 5
        val hasLeftCurve = mCurrentPopupLabels[left + 0].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[left + 1].isNotEmpty()

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

    override fun onLongPress(key: Keyboard.Key): Boolean = when {
        key.codes.main[0] == KEYCODE_GODAN_ENTER -> {
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
            mCurrentPopupLabels[i] = label.convertTo(mService.kanaState)
        }
        // Godan は各キー動作が mPopupTextView に依存するので無条件に実行
        setupPopupTextView()

        if (skkPrefs.usePopup && skkPrefs.popupOnPress) {
            showPopup()
        }
    }

    private fun showPopup(): Boolean {
        if (mCurrentPopupLabels[0] == "") return false

        val (x, y) = calculatePopupPos()
        mPopup?.showAtLocation(this, android.view.Gravity.NO_GRAVITY, x, y)

        // true だと release して repeat が終わってしまうので
        return !(findKeyByCode(keyboard, mLastPressedKey)?.repeatable ?: false)
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
        // repeatable
        Keyboard.KEYCODE_DELETE
            if !mService.handleBackspace() -> {
            if (!isCapsLocked) isShifted = false
            mService.pressDel()
        }

        KEYCODE_GODAN_LEFT if !mArrowFlicked &&
                !mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT) ->
            mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

        KEYCODE_GODAN_RIGHT if !mArrowFlicked &&
                !mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT) ->
            mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

        KEYCODE_GODAN_SPACE if isShifted -> {
            val intent = Intent(context, SKKSettingsActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        KEYCODE_GODAN_SPACE if mFlickState == EnumSet.of(FlickState.NONE) ->
            mService.processKey(' ')

        // 直接入力するASCIIキー: release で処理してもいいかも
        in 32..126 ->
            mService.processKey(primaryCode).also {
                if (!isCapsLocked) isShifted = false
            }

        // codes[0] 以外
        Keyboard.KEYCODE_CAPSLOCK -> {
            isShifted = true
            isCapsLocked = true
        }

        else -> {}
    }

    private fun release() {
        var consumesShift = true
        when (mLastPressedKey) {
            // onKey で消費済み
            Keyboard.KEYCODE_DELETE, Keyboard.KEYCODE_CAPSLOCK -> {}
            // repeatable のフリック
            KEYCODE_GODAN_SPACE -> if (mFlickState == EnumSet.of(FlickState.UP))
                mService.sendToMushroom()
            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> {
                if (mFlickState == EnumSet.of(FlickState.UP) || mInMultiTap && mTapCount != -1) {
                    isCapsLocked = true; isShifted = true
                } else {
                    isShifted = !isShifted; isCapsLocked = false
                }
                onSetShifted(isShifted)
                consumesShift = false
            }

            KEYCODE_GODAN_ENTER -> if (!mService.handleEnter())
                mService.pressEnter()

            KEYCODE_GODAN_CANCEL -> when (mFlickState) {
                EnumSet.of(FlickState.NONE) -> mService.handleCancel()
                EnumSet.of(FlickState.LEFT) -> mService.processKey(':')
                EnumSet.of(FlickState.UP) -> mService.pasteClip()
                EnumSet.of(FlickState.RIGHT) -> mService.processKey('>')
                EnumSet.of(FlickState.DOWN) -> mService.googleTransliterate()
            }

            KEYCODE_GODAN_KOMOJI -> skkPrefs.useSoftCancelKey.let { isSoftCancel ->
                when (mFlickState) {
                    EnumSet.of(if (isSoftCancel) FlickState.UP else FlickState.NONE) ->
                        mService.changeLastChar(if (!isSoftCancel && skkPrefs.useSoftTransKey) SKKEngine.LAST_CONVERSION_TRANS else SKKEngine.LAST_CONVERSION_SMALL)

                    EnumSet.of(FlickState.LEFT) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_DAKUTEN)
                    EnumSet.of(if (isSoftCancel) FlickState.NONE else FlickState.UP) -> mService.handleCancel()
                    EnumSet.of(FlickState.RIGHT) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_HANDAKUTEN)
                    EnumSet.of(FlickState.DOWN) -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_SHIFT)
                }
            }

            KEYCODE_GODAN_PASTE -> mService.pasteClip()
            KEYCODE_GODAN_GOOGLE -> mService.googleTransliterate()
            KEYCODE_GODAN_CHAR_L -> when {
                !mService.engineState.isJapanese -> mService.handleKanaKey()
                else -> mService.processKey(if (isShifted) skkPrefs.zenkakuKey else skkPrefs.asciiKey)
            }

            in KEYCODE_GODAN_CHAR_A downTo KEYCODE_GODAN_CHAR_W,
            KEYCODE_GODAN_CHAR_Q -> {
                val base = when {
                    mFlickState.contains(FlickState.NONE) -> 0
                    mFlickState.contains(FlickState.LEFT) -> 1
                    mFlickState.contains(FlickState.UP) -> 2
                    mFlickState.contains(FlickState.RIGHT) -> 3
                    mFlickState.contains(FlickState.DOWN) -> 4
                    else -> return
                }
                val flickIndex = when {
                    isLeftCurve(mFlickState) -> base * 2 + 5
                    isRightCurve(mFlickState) -> base * 2 + 6
                    else -> base
                }
                val popupText = mPopupTextView?.getOrNull(flickIndex)?.text?.toString().orEmpty()
                if (popupText.length == 1) {
                    val code = when (val guidedCode = popupText[0].code) {
                        in 32..126 -> guidedCode
                        in (32 - 0x20 + 0xFF00)..(126 - 0x20 + 0xFF00) ->
                            guidedCode - 0xFF00 + 0x20

                        '…'.code -> {
                            mService.processKey('z')
                            '.'.code
                        }

                        '□'.code -> ' '.code
                        'ー'.code -> '-'.code
                        '〜'.code -> '~'.code
                        '『'.code -> {
                            mService.processKey('z')
                            '['.code
                        }

                        '』'.code -> {
                            mService.processKey('z')
                            ']'.code
                        }

                        '、'.code -> ','.code
                        '。'.code -> '.'.code

                        '←'.code, '↑'.code, '→'.code, '↓'.code -> {
                            mService.processKey('z')
                            when (Char(guidedCode)) {
                                '←' -> 'h'.code; '↓' -> 'j'.code; '↑' -> 'k'.code; '→' -> 'l'.code
                                else -> 0
                            }
                        }

                        '￥'.code -> if (mService.engineState.isTransient) '\\'.code // 無効だけど
                        else 0.also { mService.commitTextSKK("￥") } // 全角

                        'ゃ'.code, 'ぃ'.code, 'ゅ'.code, 'ぇ'.code, 'ょ'.code,
                        'ャ'.code, 'ィ'.code, 'ュ'.code, 'ェ'.code, 'ョ'.code -> {
                            mService.processKey('y')
                            getVowel(Char(guidedCode).toString())?.code ?: 0
                        }

                        'ん'.code, 'ン'.code -> {
                            if (!mService.isComposingN) mService.processKey('n')
                            'n'.code
                        }

                        else -> 0.also { mService.commitTextSKK(Char(guidedCode).toString()) }
                    }
                    if (code != 0) mService.processKey(
                        encodeKey(
                            if (!isShifted) Character.toLowerCase(code) else code
                        )
                    )
                } else when (popupText) {
                    "^J" -> mService.handleKanaKey()
                    "ｶﾅ" -> {
                        if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                        mService.processKey(skkPrefs.hankakuKanaKey)
                    }

                    "記号" -> mService.symbolCandidates().also { consumesShift = false }
                    "絵☻" -> mService.emojiCandidates().also { consumesShift = false }

                    else -> if (popupText.length == 2 && popupText[0] in "あいうえおアイウエオ") {
                        mPopupTextView?.getOrNull(0)?.text?.let { vowelStr ->
                            val vowel = vowelStr.first()
                            if (vowel in "AIUEO") {
                                val c = if (!isShifted) vowel.lowercaseChar().code else vowel.code
                                mService.apply {
                                    suspendCompletion()
                                    processKey(encodeKey(c))
                                    resumeCompletion()
                                    // 前の processKey で▼モードになっていたら次で確定してしまうので止まる
                                    if (!engineState.hasCandidates) when (popupText[1]) {
                                        'っ', 'ッ' -> "xtu"
                                        'ん', 'ン' -> "nn"
                                        else -> ""
                                    }.forEach { processKey(it) }
                                }
                            }
                        }
                    } else popupText.forEach { mService.processKey(it) }
                }
            }
        }

        if (consumesShift && !isCapsLocked) {
            isShifted = false
            onSetShifted(isShifted)
        }
        setKeyState(mService.engineState)

        mLastPressedKey = KEYCODE_GODAN_NONE
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

    companion object {
        private const val KEYCODE_GODAN_CHAR_A = -201
        private const val KEYCODE_GODAN_CHAR_K = -202
        private const val KEYCODE_GODAN_CHAR_H = -203
        private const val KEYCODE_GODAN_CHAR_I = -204
        private const val KEYCODE_GODAN_CHAR_S = -205
        private const val KEYCODE_GODAN_CHAR_M = -206
        private const val KEYCODE_GODAN_CHAR_U = -207
        private const val KEYCODE_GODAN_CHAR_T = -208
        private const val KEYCODE_GODAN_CHAR_Y = -209
        private const val KEYCODE_GODAN_CHAR_E = -210
        private const val KEYCODE_GODAN_CHAR_N = -211
        private const val KEYCODE_GODAN_CHAR_R = -212
        private const val KEYCODE_GODAN_CHAR_O = -213
        private const val KEYCODE_GODAN_CHAR_W = -214
        private const val KEYCODE_GODAN_NONE = -1000
        private const val KEYCODE_GODAN_LEFT = -1001
        private const val KEYCODE_GODAN_RIGHT = -1002
        private const val KEYCODE_GODAN_CHAR_L = -1003
        private const val KEYCODE_GODAN_SPACE = -1004
        private const val KEYCODE_GODAN_CHAR_Q = -1005
        private const val KEYCODE_GODAN_KOMOJI = -1006
        private const val KEYCODE_GODAN_ENTER = -1007

        //private const val KEYCODE_GODAN_SEARCH = -1008
        private const val KEYCODE_GODAN_CANCEL = -1009

        //private const val KEYCODE_GODAN_TO_KANA = -1010
        private const val KEYCODE_GODAN_PASTE = -1011

        //private const val KEYCODE_GODAN_SPEECH = -1012
        private const val KEYCODE_GODAN_GOOGLE = -1013

        private enum class FlickState { NONE, LEFT, UP, RIGHT, DOWN, CURVE_LEFT, CURVE_RIGHT }

        private val POPUP_LABELS_NULL = (0..14).map { "" }
    }
}
