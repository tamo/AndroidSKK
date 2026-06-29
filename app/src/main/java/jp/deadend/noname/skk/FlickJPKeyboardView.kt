package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import jp.deadend.noname.skk.databinding.PopupFlickguideBinding
import jp.deadend.noname.skk.engine.SKKEngine
import jp.deadend.noname.skk.engine.SKKHanKanaState
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKKatakanaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import jp.deadend.noname.skk.engine.convertTo
import java.util.EnumSet
import kotlin.math.ceil

class FlickJPKeyboardView(
    context: Context, attrs: AttributeSet?
) : KeyboardView(context, attrs), OnKeyboardActionListener {
    private var mLastPressedIndex = -1
    private var mLastPressedAction = ""
    private var mProcessedOnKey = false
    private var mFlickState = EnumSet.of(FlickState.NONE)
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private val mArrowPressed: Boolean
        get() = (mLastPressedAction == "(DpadLeft)" || mLastPressedAction == "(DpadRight)")
    private var mArrowFlicked = false
    private var mArrowStartX = -1f
    private var mArrowStartY = -1f
    private var mCurrentPopupLabels = Array(15) { "" }

    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mCoordinates = IntArray(2)

    internal val mJPKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_jp, mService.mRootWidth, mService.mScreenHeight)
    }
    internal val mNumKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_jp, mService.mRootWidth, mService.mScreenHeight)
    }
    internal val mVoiceKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.keys_flick_jp, mService.mRootWidth, mService.mScreenHeight)
    }

    private var mFlickRules: Map<String, Map<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>> =
        emptyMap()

    init {
        mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: emptyMap()
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

    private fun getFlickRule(index: Int, keyboard: Keyboard = this.keyboard): FlickKeyConfig? {
        val section = when (keyboard) {
            mNumKeyboard -> "Number"
            mVoiceKeyboard -> "Voice"
            else -> "JP"
        }
        val pair = mFlickRules[section]?.get(index)
        return if (isShifted) pair?.second ?: pair?.first else pair?.first
    }

    private fun getKeyLabel(config: FlickKeyConfig): String {
        if (config.label.isNotEmpty()) return config.label

        val labels = config.labels.map {
            if (it.startsWith("(K)")) {
                if (mService.isHiragana) "" else it.removePrefix("(K)")
            } else it
        }
        return "${labels[2]}\n${labels[1]}${labels[0]}${labels[3]}\n${labels[4]}"
    }

    private fun getIcon(marker: String): Drawable? {
        val resId = when (marker) {
            "(IconShift)" -> R.drawable.ic_keyboard_shift
            "(IconBackspace)" -> R.drawable.ic_keyboard_backspace
            "(IconLeft)" -> R.drawable.ic_keyboard_arrow_left
            "(IconRight)" -> R.drawable.ic_keyboard_arrow_right
            "(IconReturn)" -> R.drawable.ic_keyboard_return
            "(IconSpace)" -> R.drawable.ic_keyboard_space_bar
            else -> return null
        }
        return ContextCompat.getDrawable(context, resId)
    }

    private fun updateKeyLabels() {
        val kanaState = mService.kanaState

        for (kb in listOf(mJPKeyboard, mNumKeyboard, mVoiceKeyboard)) {
            kb.keys.forEachIndexed { index, key ->
                val config = getFlickRule(index, kb) ?: return@forEachIndexed

                key.labels.main = getKeyLabel(config).convertTo(kanaState, reversed = true)

                key.icon = getIcon(config.label)
                if (key.icon != null) key.labels.main = ""

                if (config.actions.getOrNull(0) == "(ShiftToggle)")
                    key.codes = Keyboard.Key.Codes(main = intArrayOf(Keyboard.KEYCODE_SHIFT))
            }
            kb.reloadShiftKeys()
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

    //    09 02 10
    // 08 05    06 11
    // 01    00    03
    // 07          12
    //    14 04 13
    private fun getFlickIndex(flick: EnumSet<FlickState>): Int {
        val baseIndex = when {
            flick.contains(FlickState.LEFT) -> 1
            flick.contains(FlickState.UP) -> 2
            flick.contains(FlickState.RIGHT) -> 3
            flick.contains(FlickState.DOWN) -> 4
            else -> 0
        }
        return when {
            flick.contains(FlickState.CURVE_LEFT) -> baseIndex * 2 + 5
            flick.contains(FlickState.CURVE_RIGHT) -> baseIndex * 2 + 6
            else -> baseIndex
        }
    }

    private fun setupPopupTextView() {
        if (!skkPrefs.usePopup) return

        val labels = checkNotNull(mPopupTextView) { "BUG: popup labels are null!!" }
        labels.forEach {
            it.text = ""
            it.setBackgroundResource(R.drawable.popup_label)
        }

        val activeLabel = getFlickIndex(mFlickState)
        val baseIndex = if (activeLabel < 5) activeLabel else (activeLabel - 5) / 2

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
        val hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()

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

    private fun executeAction(action: String) {
        if (action.isEmpty()) return

        var currentAction = action
        if (currentAction.startsWith("(N)")) {
            if (mService.engineState.hasCandidates) mService.handleEnter()
            currentAction = currentAction.removePrefix("(N)")
        }

        if (currentAction.startsWith("(Commit)")) {
            mService.commitTextSKK(currentAction.removePrefix("(Commit)"))
        } else when (currentAction) {
            "(SmallLast)" -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_SMALL)
            "(DakutenLast)" -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_DAKUTEN)
            "(HandakutenLast)" -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_HANDAKUTEN)
            "(TransLast)" -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_TRANS)
            "(ShiftLast)" -> mService.changeLastChar(SKKEngine.LAST_CONVERSION_SHIFT)

            "(Cancel)" -> mService.handleCancel()
            "(Emoji)" -> mService.emojiCandidates()
            "(Symbol)" -> mService.symbolCandidates()
            "(Google)" -> mService.googleTransliterate()
            "(Paste)" -> mService.pasteClip()
            "(Speech)" -> mService.recognizeSpeech()
            "(Mushroom)" -> mService.sendToMushroom()

            "(Enter)" -> if (!mService.handleEnter()) mService.pressEnter()
            "(Backspace)" -> if (!mService.handleBackspace()) mService.pressDel()

            "(DpadLeft)" -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

            "(DpadRight)" -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

            "(DpadUp)" -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_UP))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_UP)

            "(DpadDown)" -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_DOWN))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_DOWN)

            "(Settings)" -> {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            "(Voice)" -> {
                keyboard = mVoiceKeyboard; isHankaku = false
            }

            "(Num)" -> {
                keyboard = mNumKeyboard; isHankaku = false
            }

            "(Kana)" -> {
                if (keyboard == mJPKeyboard) {
                    mService.processKey(skkPrefs.katakanaKey)
                } else {
                    keyboard = mJPKeyboard
                }
                isHankaku = mService.kanaState == SKKHanKanaState
            }

            "(HankakuKana)" -> {
                mService.processKey(skkPrefs.hankakuKanaKey)
                isHankaku = mService.kanaState == SKKHanKanaState
            }

            "(Qwerty)" -> mService.processKey(skkPrefs.asciiKey)
            "(Zenkaku)" -> mService.processKey(skkPrefs.zenkakuKey)

            "(Caps)" -> {
                isCapsLocked = true; isShifted = true
                updateKeyLabels()
            }

            "(ShiftToggle)" -> {
                isShifted = !isShifted; isCapsLocked = false
                updateKeyLabels()
            }

            else -> { // Literal
                val isZenkaku = currentAction.startsWith("(Z)")
                if (isZenkaku) currentAction = currentAction.removePrefix("(Z)")

                mService.suspendCompletion()
                currentAction.forEachIndexed { index, char ->
                    val c = if (index == 0 && char.isLowerCase() && isShifted)
                        char.uppercaseChar() else char
                    if (isZenkaku) mService.processKeyIn(SKKZenkakuState, c)
                    else mService.processKey(c)
                }
                mService.resumeCompletion()
            }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean = when {
        getFlickRule(mLastPressedIndex)?.actions?.getOrNull(0) == "(Enter)" -> {
            mService.pressSearch()
            true
        }

        skkPrefs.usePopup && !skkPrefs.popupOnPress && showPopup() -> true

        else -> super.onLongPress(key)
    }

    override fun onPress(primaryCode: Int) {
        // val index = -(primaryCode + 1000) はシフトキーが code 変更するため破綻
        val index = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        val config = getFlickRule(index)
        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedIndex = index
            mLastPressedAction = config?.actions?.get(0) ?: ""
        }

        mArrowFlicked = false

        if (config == null) {
            mCurrentPopupLabels.fill("")
            return
        }
        config.labels.forEachIndexed { i, label ->
            if (i > 14) return@forEachIndexed
            val l = if (label.contains("(K)")) {
                if (mService.isHiragana) "" else label.replace("(K)", "")
            } else label
            mCurrentPopupLabels[i] = l.convertTo(mService.kanaState, reversed = true)
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
        val action = getFlickRule(mLastPressedIndex)?.actions?.get(0) ?: ""
        val isRepeatable = when (action) {
            "(Backspace)", "(DpadLeft)", "(DpadRight)", "(Settings)", "(Space)" -> true
            else -> false
        }
        return !isRepeatable
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

    override fun onKey(primaryCode: Int) {
        val keyIndex = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        val config = getFlickRule(keyIndex)
        val action = config?.actions?.get(0) ?: ""

        // repeatable なキーはここで処理する必要がある
        mProcessedOnKey = true
        when (action) {
            "(Backspace)" ->
                if (!mService.handleBackspace()) mService.pressDel()

            "(DpadLeft)" -> if (!mArrowFlicked)
                if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT))
                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

            "(DpadRight)" -> if (!mArrowFlicked)
                if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT))
                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

            "(Settings)" -> {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            "(Space)" -> if (mFlickState == EnumSet.of(FlickState.NONE))
                mService.processKey(' ')

            else -> mProcessedOnKey = false
        }
    }

    private fun release() {
        val flickIndex = getFlickIndex(mFlickState ?: EnumSet.of(FlickState.NONE))
        val action = getFlickRule(mLastPressedIndex)?.actions?.get(flickIndex) ?: ""

        if (!mProcessedOnKey && action.isNotEmpty()) executeAction(action)

        if (action != "(ShiftToggle)" && action != "(Caps)" && !isCapsLocked) {
            isShifted = false
            updateKeyLabels()
        }

        mLastPressedAction = ""
        mLastPressedIndex = -1
        mProcessedOnKey = false
        mFlickState = EnumSet.of(FlickState.NONE)
        mFlickStartX = -1f
        mFlickStartY = -1f
        mPopup?.dismiss()
    }

    override fun onRelease(primaryCode: Int) {
        mArrowFlicked = false
    }

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
        private enum class FlickState { NONE, LEFT, UP, RIGHT, DOWN, CURVE_LEFT, CURVE_RIGHT }
    }
}
