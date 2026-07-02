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
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import jp.deadend.noname.skk.engine.convertTo
import java.util.EnumSet
import kotlin.math.ceil

class FlickJPKeyboardView(
    context: Context, attrs: AttributeSet?
) : KeyboardView(context, attrs), OnKeyboardActionListener {
    private var mLastPressedIndex = -1
    private var mProcessedOnKey = false
    private var mIsASCII = false
    private var mFlickState = EnumSet.of(FlickState.NONE)
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private var mArrowPressed: Boolean = false
    private var mArrowFlicked = false
    private var mArrowStartX = -1f
    private var mArrowStartY = -1f
    private var mCurrentPopupLabels = Array(15) { "" }

    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mCoordinates = IntArray(2)

    internal var mJPKeyboard: Keyboard? = null
    internal var mNumKeyboard: Keyboard? = null
    internal var mVoiceKeyboard: Keyboard? = null

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

        mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: emptyMap()
        prepareNewKeyboard(context, mService.mRootWidth, mService.mScreenHeight)
    }

    override fun onDetachedFromWindow() {
        mPopup?.dismiss()
        super.onDetachedFromWindow()
    }

    override fun setKeyState(state: SKKState): FlickJPKeyboardView {
        updateKeyLabels(state)
        return this
    }

    private fun getConfig(index: Int, keyboard: Keyboard? = this.keyboard): FlickKeyConfig? {
        val section = when (keyboard) {
            mNumKeyboard -> SKKFlickRule.SECTION_NUMBER
            mVoiceKeyboard -> SKKFlickRule.SECTION_VOICE
            else if mService.engineState.isJapanese -> SKKFlickRule.SECTION_MAIN
            else -> SKKFlickRule.SECTION_ASCII
        }
        val sectionMap = mFlickRules[section]
            ?: if (section == SKKFlickRule.SECTION_ASCII) mFlickRules[SKKFlickRule.SECTION_MAIN]
            else null
        val pair = sectionMap?.get(index)
        return if (isShifted) pair?.second ?: pair?.first else pair?.first
    }

    private fun FlickKeyConfig?.getAction(actionIndex: Int = 0): FlickAction =
        this?.actions?.getOrNull(actionIndex) ?: FlickAction.EMPTY

    private fun getKeyAction(keyIndex: Int, actionIndex: Int = 0): FlickAction =
        getConfig(keyIndex).getAction(actionIndex)

    private fun getKeyLabel(config: FlickKeyConfig): String {
        if (config.label.isNotEmpty()) return config.label

        val labels = config.labels.map {
            if (it.startsWith(SKKFlickRule.MARKER_KATAKANA_ONLY)) {
                if (mService.isHiragana) "" else it.removePrefix(SKKFlickRule.MARKER_KATAKANA_ONLY)
            } else it
        }
        return "${labels[2]}\n${labels[1]}${labels[0]}${labels[3]}\n${labels[4]}"
    }

    private fun getIcon(marker: String): Drawable? {
        val resId = when (marker) {
            SKKFlickRule.ICON_SHIFT -> R.drawable.ic_keyboard_shift
            SKKFlickRule.ICON_BACKSPACE -> R.drawable.ic_keyboard_backspace
            SKKFlickRule.ICON_DELETE -> R.drawable.ic_keyboard_delete
            SKKFlickRule.ICON_LEFT -> R.drawable.ic_keyboard_arrow_left
            SKKFlickRule.ICON_RIGHT -> R.drawable.ic_keyboard_arrow_right
            SKKFlickRule.ICON_RETURN -> R.drawable.ic_keyboard_return
            SKKFlickRule.ICON_SPACE -> R.drawable.ic_keyboard_space_bar
            else -> return null
        }
        return ContextCompat.getDrawable(context, resId)
    }

    private fun updateKeyLabels(state: SKKState? = null) {
        state?.let {
            mIsASCII = !it.isJapanese
            isHankaku = it is SKKHanKanaState
            isZenkaku = it is SKKZenkakuState
        }

        for (kb in listOfNotNull(mJPKeyboard, mNumKeyboard, mVoiceKeyboard)) {
            kb.keys.forEachIndexed { index, key ->
                val config = getConfig(index, kb) ?: return@forEachIndexed

                key.labels.main = getKeyLabel(config).convertTo(mService.kanaState, reversed = true)

                key.icon = getIcon(config.label)
                if (key.icon != null) key.labels.main = ""

                val action = config.getAction(0)
                when (action.text) {
                    // KEYCODE_SHIFT にしておくと Keyboard でシフト on してくれる
                    // codes を 2 つ入れておかないとダブルタップの判定がスキップされる (2 つ目の中身は関係ない)
                    SKKFlickRule.ACTION_TOGGLE_SHIFT -> key.codes = Keyboard.Key
                        .Codes(main = intArrayOf(Keyboard.KEYCODE_SHIFT, Keyboard.KEYCODE_CAPSLOCK))

                    SKKFlickRule.ACTION_KBD_QWERTY -> key.on = mIsASCII

                    SKKFlickRule.ACTION_KATAKANA -> key.on = !mIsASCII && !mService.isHiragana
                    //else if action.codes.size == 1 && action.codes[0] == skkPrefs.katakanaKey ->
                    //    key.on = !mIsASCII && !mService.isHiragana
                }
            }
            kb.reloadShiftKeys()
        }
        invalidateAllKeys()
    }

    internal fun prepareNewKeyboard(context: Context, widthPixel: Int, heightPixel: Int) {
        mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: emptyMap()
        fun xmlId(section: String) =
            if ((mFlickRules[section]?.keys?.size ?: 0) > 20) R.xml.keys_flick_24
            else R.xml.keys_flick_jp

        mJPKeyboard = Keyboard(
            context, xmlId(SKKFlickRule.SECTION_MAIN),
            mService.mRootWidth, mService.mScreenHeight
        )
        mNumKeyboard = Keyboard(
            context, xmlId(SKKFlickRule.SECTION_NUMBER),
            mService.mRootWidth, mService.mScreenHeight
        )
        mVoiceKeyboard = Keyboard(
            context, xmlId(SKKFlickRule.SECTION_VOICE),
            mService.mRootWidth, mService.mScreenHeight
        )

        mJPKeyboard?.resize(widthPixel, heightPixel)
        mNumKeyboard?.resize(widthPixel, heightPixel)
        mVoiceKeyboard?.resize(widthPixel, heightPixel)
        keyboard = mJPKeyboard!!
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

    private fun executeAction(action: FlickAction) {
        if (action.codes.isEmpty()) return

        var currentAction = action
        if (currentAction.text.startsWith(SKKFlickRule.ACTION_COMMIT_CANDIDATE)) {
            if (mService.engineState.hasCandidates) mService.handleEnter()
            currentAction = currentAction.removePrefix(SKKFlickRule.ACTION_COMMIT_CANDIDATE)
        }

        when (currentAction.text) {
            SKKFlickRule.ACTION_TRANS_SMALL -> mService.changeLastChar(SKKEngine.TRANS_SMALL)
            SKKFlickRule.ACTION_TRANS_DAKUTEN -> mService.changeLastChar(SKKEngine.TRANS_DAKUTEN)
            SKKFlickRule.ACTION_TRANS_HANDAKUTEN -> mService.changeLastChar(SKKEngine.TRANS_HANDAKUTEN)
            SKKFlickRule.ACTION_TRANS_AUTO -> mService.changeLastChar(SKKEngine.TRANS_AUTO)
            SKKFlickRule.ACTION_TRANS_SHIFT -> mService.changeLastChar(SKKEngine.TRANS_SHIFT)

            SKKFlickRule.ACTION_CANCEL -> mService.handleCancel()
            SKKFlickRule.ACTION_EMOJI -> mService.emojiCandidates()
            SKKFlickRule.ACTION_SYMBOL -> mService.symbolCandidates()
            SKKFlickRule.ACTION_GOOGLE -> mService.googleTransliterate()
            SKKFlickRule.ACTION_PASTE -> mService.pasteClip()
            SKKFlickRule.ACTION_SPEECH -> mService.recognizeSpeech()
            SKKFlickRule.ACTION_MUSHROOM -> mService.sendToMushroom()

            SKKFlickRule.ACTION_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            SKKFlickRule.ACTION_BACKSPACE -> if (!mService.handleBackspace()) mService.pressDel()
            SKKFlickRule.ACTION_DELETE -> if (!mService.handleForwardDel()) mService.pressForwardDel()

            SKKFlickRule.ACTION_DPAD_LEFT -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

            SKKFlickRule.ACTION_DPAD_RIGHT -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

            SKKFlickRule.ACTION_DPAD_UP -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_UP))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_UP)

            SKKFlickRule.ACTION_DPAD_DOWN -> if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_DOWN))
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_DOWN)

            SKKFlickRule.ACTION_SETTINGS -> {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            SKKFlickRule.ACTION_KBD_VOICE -> {
                mVoiceKeyboard?.let { keyboard = it }; isHankaku = false
            }

            SKKFlickRule.ACTION_KBD_NUMBER -> {
                mNumKeyboard?.let { keyboard = it }; isHankaku = false
            }

            SKKFlickRule.ACTION_RESET -> // かなキー (toggleKanaKey なら ASCII を意味するので注意)
                if (keyboard == mJPKeyboard) {
                    mService.handleKanaKey()
                } else {
                    mJPKeyboard?.let { keyboard = it }
                }

            SKKFlickRule.ACTION_KATAKANA -> {
                if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                mService.processKey(skkPrefs.katakanaKey)
            }

            SKKFlickRule.ACTION_HANKAKU_KANA -> {
                if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                mService.processKey(skkPrefs.hankakuKanaKey)
            }

            SKKFlickRule.ACTION_KBD_QWERTY ->
                if (mService.engineState.isJapanese) {
                    // カタカナには戻れないので一旦ひらがな経由でキーラベル表示を整える
                    mService.kanaState = SKKHiraganaState
                    mService.processKey(skkPrefs.asciiKey)
                } else {
                    mService.handleKanaKey()
                }

            SKKFlickRule.ACTION_ABBREV ->
                mService.processKey(skkPrefs.abbrevKey)

            SKKFlickRule.ACTION_ZENKAKU ->
                mService.processKey(skkPrefs.zenkakuKey)

            SKKFlickRule.ACTION_CAPS -> {
                isCapsLocked = true; isShifted = true
            }

            SKKFlickRule.ACTION_TOGGLE_SHIFT ->
                if (mInMultiTap && mTapCount == 1) { // ダブルタップで (Caps) と等価
                    isCapsLocked = true; isShifted = true
                } else {
                    isShifted = !isShifted; isCapsLocked = false
                }

            else if currentAction.text.startsWith(SKKFlickRule.ACTION_COMMIT) ->
                mService.commitTextSKK(currentAction.removePrefix(SKKFlickRule.ACTION_COMMIT).text)

            else -> {
                if (currentAction.text.startsWith(SKKFlickRule.ACTION_DEDUPE_N)) {
                    if (!mService.isComposingN) mService.processKey('n')
                    currentAction = currentAction.removePrefix(SKKFlickRule.ACTION_DEDUPE_N)
                }
                val inZenkaku = currentAction.text.startsWith(SKKFlickRule.ACTION_IN_ZENKAKU)
                if (inZenkaku) currentAction =
                    currentAction.removePrefix(SKKFlickRule.ACTION_IN_ZENKAKU)

                mService.suspendCompletion()
                run loop@{
                    currentAction.codes.forEachIndexed { index, low ->
                        if (index == currentAction.codes.lastIndex) mService.resumeCompletion()
                        val (char, code) = if ((index == 0 && isShifted) || low and SHIFT_PRESSED != 0)
                            low.upperChar to low.upper else low.char to low
                        if (inZenkaku) mService.processKeyIn(SKKZenkakuState, char)
                        else mService.processKey(code)
                        // ▼モードになっていたら次で確定してしまうので止まる
                        if (mService.engineState.hasCandidates) return@loop
                    }
                }
                mService.resumeCompletion()
            }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean = when {
        getKeyAction(mLastPressedIndex).text == SKKFlickRule.ACTION_ENTER -> {
            mService.pressSearch()
            true
        }

        skkPrefs.usePopup && !skkPrefs.popupOnPress && showPopup() -> true

        else -> super.onLongPress(key)
    }

    override fun onPress(primaryCode: Int) {
        mArrowFlicked = false

        // val index = -(primaryCode + 1000) はシフトキーが codes 変更するため破綻
        val keyIndex = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        val config = getConfig(keyIndex) ?: return mCurrentPopupLabels.fill("")

        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedIndex = keyIndex
            mArrowPressed = when (config.getAction(0).text) {
                SKKFlickRule.ACTION_DPAD_LEFT, SKKFlickRule.ACTION_DPAD_RIGHT -> true
                else -> false
            }
        }

        config.labels.forEachIndexed { i, label ->
            if (i > 14) return@forEachIndexed
            val l = if (label.contains(SKKFlickRule.MARKER_KATAKANA_ONLY)) {
                if (mService.isHiragana) ""
                else label.replace(SKKFlickRule.MARKER_KATAKANA_ONLY, "")
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
        val isRepeatable = when (getKeyAction(mLastPressedIndex).text) {
            SKKFlickRule.ACTION_BACKSPACE, SKKFlickRule.ACTION_DELETE, SKKFlickRule.ACTION_SPACE,
            SKKFlickRule.ACTION_DPAD_LEFT, SKKFlickRule.ACTION_DPAD_RIGHT,
            SKKFlickRule.ACTION_SETTINGS -> true

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

    // repeatable なキーはここで処理する必要がある
    override fun onKey(primaryCode: Int) {
        val keyIndex = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        mProcessedOnKey = true
        when (getKeyAction(keyIndex).text) {
            SKKFlickRule.ACTION_BACKSPACE ->
                if (!mService.handleBackspace()) mService.pressDel()

            SKKFlickRule.ACTION_DELETE ->
                if (!mService.handleForwardDel()) mService.pressForwardDel()

            SKKFlickRule.ACTION_DPAD_LEFT -> if (!mArrowFlicked)
                if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT))
                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)

            SKKFlickRule.ACTION_DPAD_RIGHT -> if (!mArrowFlicked)
                if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT))
                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)

            SKKFlickRule.ACTION_SETTINGS -> {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            SKKFlickRule.ACTION_SPACE -> if (mFlickState == EnumSet.of(FlickState.NONE))
                mService.processKey(' ')

            else -> mProcessedOnKey = false
        }
    }

    private fun release() {
        val action = getKeyAction(mLastPressedIndex, getFlickIndex(mFlickState))

        if (!mProcessedOnKey) executeAction(action)

        if (action.text != SKKFlickRule.ACTION_TOGGLE_SHIFT &&
            action.text != SKKFlickRule.ACTION_CAPS && !isCapsLocked
        ) isShifted = false

        updateKeyLabels(mService.engineState)

        mArrowPressed = false
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

    fun speechRecognitionResultsList(results: ArrayList<String>) {
        val (prefix, array, suffix) = extractCommon(results)

        val dialog = AlertDialog.Builder(context, R.style.Theme_SKK).apply {
            setTitle("${prefix}${LEFT_SYMBOL} ${RIGHT_SYMBOL}${suffix}")
            setItems(array) { _, which ->
                val chosen = array[which].removeSurrounding(LEFT_SYMBOL, RIGHT_SYMBOL)
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
            LEFT_SYMBOL + it.substring(0, it.length - commonSuffix.length) + RIGHT_SYMBOL
        }.toTypedArray()
        return Triple(commonPrefix, array, commonSuffix)
    }

    companion object {
        private const val LEFT_SYMBOL = "【"
        private const val RIGHT_SYMBOL = "】"

        private enum class FlickState { NONE, LEFT, UP, RIGHT, DOWN, CURVE_LEFT, CURVE_RIGHT }
    }
}
