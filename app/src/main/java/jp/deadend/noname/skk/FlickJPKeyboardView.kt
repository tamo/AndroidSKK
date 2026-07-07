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
import jp.deadend.noname.skk.engine.SKKASCIIState
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

    private var mFlickRules: FlickRule = FlickRule()
    internal var isEditorMode = false

    interface OnFlickListener {
        fun onFlick(keyIndex: Int, flickIndex: Int)
    }

    internal var onFlickListener: OnFlickListener? = null

    init {
        mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: FlickRule()
    }

    fun setFlickRules(rules: FlickRule) {
        mFlickRules = rules
        invalidateAllKeys()
    }

    override fun setService(service: SKKService) {
        super.setService(service)
        onKeyboardActionListener = this
        isPreviewEnabled = false
        setBackgroundColor(0x00000000)

        mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: FlickRule()
        if (isEditorMode) return
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
        val sectionName = when (keyboard) {
            mNumKeyboard -> SKKFlickRule.SECTION_NUMBER
            mVoiceKeyboard -> SKKFlickRule.SECTION_VOICE
            else -> if (mIsASCII) SKKFlickRule.SECTION_ASCII else SKKFlickRule.SECTION_MAIN
        }
        val section = mFlickRules.sections[sectionName]
            ?: if (sectionName == SKKFlickRule.SECTION_ASCII) mFlickRules.sections[SKKFlickRule.SECTION_MAIN]
            else null
        val entry = section?.entries?.get(index)
        return if (isShifted) entry?.shifted ?: entry?.normal else entry?.normal
    }

    private fun FlickKeyConfig?.getAction(actionIndex: Int = 0): String =
        this?.actions?.getOrNull(actionIndex) ?: ""

    private fun getKeyAction(keyIndex: Int, actionIndex: Int = 0): String =
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

    internal fun updateKeyLabels(state: SKKState? = null) {
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

                val actionText = config.getAction()
                when (actionText) {
                    // KEYCODE_SHIFT にしておくと Keyboard でシフト on してくれる
                    // codes を 2 つ入れておかないとダブルタップの判定がスキップされる (2 つ目の中身は関係ない)
                    SKKFlickRule.ACTION_TOGGLE_SHIFT -> key.codes = Keyboard.Key
                        .Codes(main = intArrayOf(Keyboard.KEYCODE_SHIFT, Keyboard.KEYCODE_CAPSLOCK))

                    // "(KbdQwerty)" "(Katakana)" で判定するので "l" "q" には反応しない
                    // あるいは "()(KbdQwerty)" などとして key.on を回避することもできる
                    SKKFlickRule.ACTION_KBD_QWERTY -> key.on = mIsASCII
                    SKKFlickRule.ACTION_KATAKANA -> key.on = !mIsASCII && !mService.isHiragana
                }
            }
            kb.reloadShiftKeys()
        }
        invalidateAllKeys()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cw = w - paddingLeft - paddingRight
        val ch = h - paddingTop - paddingBottom
        mJPKeyboard?.resize(cw, ch)
        mNumKeyboard?.resize(cw, ch)
        mVoiceKeyboard?.resize(cw, ch)
        if (w > 0 && h > 0) invalidateAllKeys()
    }

    internal fun prepareNewKeyboard(context: Context, widthPixel: Int, heightPixel: Int) {
        if (mFlickRules.sections.isEmpty()) {
            mFlickRules = SKKFlickRule.loadFromInternalStorage(context) ?: FlickRule()
        }

        fun has24(sect: String) = (mFlickRules.sections[sect]?.entries?.keys?.maxOrNull() ?: 0) > 19
        fun xmlId(sect: String) = if (has24(sect)) R.xml.keys_flick_24 else R.xml.keys_flick_jp
        val jpXmlId = if (has24(SKKFlickRule.SECTION_MAIN) || has24(SKKFlickRule.SECTION_ASCII))
            R.xml.keys_flick_24 else R.xml.keys_flick_jp // Main と ASCII で共通させる必要がある

        SKKLog.d("prepareNewKeyboard($widthPixel, $heightPixel) kbd=(${mService.keyboardWidth()}, ${mService.keyboardHeight()}) self=($width, $height)")
        val m = maxOf(mService.maxWidth, mService.mScreenHeight, widthPixel, heightPixel)
        val w = (widthPixel.takeIf { it > 0 }
            ?: width.takeIf { it > 0 } ?: mService.keyboardWidth()) - paddingLeft - paddingRight
        val h = (heightPixel.takeIf { it > 0 }
            ?: height.takeIf { it > 0 } ?: mService.keyboardHeight()) - paddingTop - paddingBottom

        mJPKeyboard = Keyboard(context, jpXmlId, m, m)
        mNumKeyboard = Keyboard(context, xmlId(SKKFlickRule.SECTION_NUMBER), m, m)
        mVoiceKeyboard = Keyboard(context, xmlId(SKKFlickRule.SECTION_VOICE), m, m)

        mJPKeyboard?.resize(w, h)
        mNumKeyboard?.resize(w, h)
        mVoiceKeyboard?.resize(w, h)

        keyboard = mJPKeyboard ?: return
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
                super.onModifiedTouchEvent(me, possiblePoly)
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
                super.onModifiedTouchEvent(me, possiblePoly)
            }

            MotionEvent.ACTION_UP -> {
                release()
                super.onModifiedTouchEvent(me, possiblePoly)
            }

            else -> super.onModifiedTouchEvent(me, possiblePoly)
        }
        return true
    }

    private fun processFirstFlick(dx: Float, dy: Float) {
        val dAngle = diamondAngle(dx, dy)
        val hasLeftCurve = isEditorMode || mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = isEditorMode || mCurrentPopupLabels[6].isNotEmpty()

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
        val hasLeftCurve = isEditorMode || mCurrentPopupLabels[baseIndex * 2 + 5].isNotEmpty()
        val hasRightCurve = isEditorMode || mCurrentPopupLabels[baseIndex * 2 + 6].isNotEmpty()

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

    internal fun executeAction(actionText: String) {
        if (actionText.isEmpty()) return

        val text = StringBuilder(actionText)
        var meta = 0
        var inZenkaku = false
        var isCommit = false
        val hadCandidates = mService.engineState.hasCandidates

        mService.suspendCompletion()
        while (text.isNotEmpty()) {
            val char = text.first()

            if (char == '(') SKKFlickRule.ACTIONS.find { text.startsWith(it) }?.let {
                text.delete(0, it.length)

                if (text.isEmpty()) mService.resumeCompletion()
                when (it) {
                    SKKFlickRule.ACTION_NOOP -> continue
                    SKKFlickRule.ACTION_MOD_CTRL -> meta = meta or CTRL_PRESSED
                    SKKFlickRule.ACTION_MOD_ALT -> meta = meta or ALT_PRESSED
                    SKKFlickRule.ACTION_MOD_META -> meta = meta or META_PRESSED
                    SKKFlickRule.ACTION_IN_ZENKAKU -> inZenkaku = !inZenkaku
                    SKKFlickRule.ACTION_COMMIT -> isCommit = !isCommit
                    else -> executeSingleAction(it, meta).also { meta = 0 }
                }
                continue
            }

            text.deleteCharAt(0)
            val code = encodeKey(char.code, meta).let {
                if (isShifted) it.upper.also { isShifted = isCapsLocked } else it
            }

            if (text.isEmpty()) mService.resumeCompletion()
            when {
                isCommit -> mService.commitTextSKK(code.char.toString())
                inZenkaku -> mService.processKeyIn(SKKZenkakuState, code.char)
                else -> mService.processKey(code)
            }
            meta = 0

            // processKey のせいで ▼モードになった場合は次で誤って確定しないようにここで終了
            if (!hadCandidates && mService.engineState.hasCandidates && text.isNotEmpty() &&
                text.first() == '(' && SKKFlickRule.ACTIONS.find { text.startsWith(it) } == null
            ) break
        }
        mService.resumeCompletion()
    }

    private fun executeSingleAction(action: String, meta: Int = 0) {
        when (action) {
            SKKFlickRule.ACTION_COMMIT_CANDIDATE -> if (mService.engineState.hasCandidates)
                mService.handleEnter()

            SKKFlickRule.ACTION_DEDUPE_N -> if (!mService.isComposingN)
                mService.processKey(encodeKey('n'.code, meta))

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

            SKKFlickRule.ACTION_DPAD_LEFT -> {
                val code = encodeKey(KeyEvent.KEYCODE_DPAD_LEFT, meta or RAW_KEYCODE)
                if (!mService.handleDpad(code.lowerCode)) mService.keyDownUp(code)
            }

            SKKFlickRule.ACTION_DPAD_RIGHT -> {
                val code = encodeKey(KeyEvent.KEYCODE_DPAD_RIGHT, meta or RAW_KEYCODE)
                if (!mService.handleDpad(code.lowerCode)) mService.keyDownUp(code)
            }

            SKKFlickRule.ACTION_DPAD_UP -> {
                val code = encodeKey(KeyEvent.KEYCODE_DPAD_UP, meta or RAW_KEYCODE)
                if (!mService.handleDpad(code.lowerCode)) mService.keyDownUp(code)
            }

            SKKFlickRule.ACTION_DPAD_DOWN -> {
                val code = encodeKey(KeyEvent.KEYCODE_DPAD_DOWN, meta or RAW_KEYCODE)
                if (!mService.handleDpad(code.lowerCode)) mService.keyDownUp(code)
            }

            SKKFlickRule.ACTION_SETTINGS -> {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            SKKFlickRule.ACTION_KBD_VOICE ->
                mVoiceKeyboard?.let { keyboard = it }.also { isHankaku = false }

            SKKFlickRule.ACTION_KBD_NUMBER ->
                mNumKeyboard?.let { keyboard = it }.also { isHankaku = false }

            SKKFlickRule.ACTION_RESET -> // かなキー (toggleKanaKey なら ASCII を意味するので注意)
                if (keyboard == mJPKeyboard) mService.handleKanaKey()
                else mJPKeyboard?.let { keyboard = it }

            SKKFlickRule.ACTION_KATAKANA -> {
                if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                mService.processKey(skkPrefs.katakanaKey)
            }

            SKKFlickRule.ACTION_HANKAKU_KANA -> {
                if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                mService.processKey(skkPrefs.hankakuKanaKey)
            }

            SKKFlickRule.ACTION_KBD_QWERTY ->
                mService.changeSoftKeyboard(SKKASCIIState)

            SKKFlickRule.ACTION_ASCII ->
                if (mService.engineState.isJapanese) mService.run {
                    kanaState = SKKHiraganaState // キーラベル表示を整える
                    processKey(skkPrefs.asciiKey)
                } else mService.handleKanaKey()

            SKKFlickRule.ACTION_ABBREV ->
                mService.processKey(skkPrefs.abbrevKey)

            SKKFlickRule.ACTION_ZENKAKU ->
                mService.processKey(skkPrefs.zenkakuKey)

            SKKFlickRule.ACTION_CAPS ->
                isCapsLocked = true.also { isShifted = true }

            SKKFlickRule.ACTION_TOGGLE_SHIFT ->
                if (mInMultiTap && mTapCount == 1) // ダブルタップで (Caps) と等価
                    isCapsLocked = true.also { isShifted = true }
                else isShifted = !isShifted.also { isCapsLocked = false }
        }
    }

    override fun onLongPress(key: Keyboard.Key): Boolean =
        when (val action = getKeyAction(mLastPressedIndex)) {
            SKKFlickRule.ACTION_ENTER -> mService.pressSearch().let { true }
            else -> {
                if (!skkPrefs.popupOnPress) showPopup()
                when (action) {
                    // repeatable 扱いするため false を返して release を回避
                    SKKFlickRule.ACTION_BACKSPACE, SKKFlickRule.ACTION_DELETE,
                    SKKFlickRule.ACTION_SPACE, SKKFlickRule.ACTION_SETTINGS,
                    SKKFlickRule.ACTION_DPAD_LEFT, SKKFlickRule.ACTION_DPAD_RIGHT -> false

                    else -> true
                }
            }
        }

    override fun onPress(primaryCode: Int) {
        mArrowFlicked = false

        // val index = -(primaryCode + 1000) はシフトキーが codes 変更するため破綻
        val keyIndex = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        val config = getConfig(keyIndex) ?: if (isEditorMode) FlickKeyConfig.createEmpty()
        else return mCurrentPopupLabels.fill("")

        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedIndex = keyIndex
            mArrowPressed = when (config.getAction()) {
                SKKFlickRule.ACTION_DPAD_LEFT, SKKFlickRule.ACTION_DPAD_RIGHT -> true
                else -> false
            }
        }

        config.labels.forEachIndexed { i, label ->
            if (i > 14) return@forEachIndexed
            val l = if (label.startsWith(SKKFlickRule.MARKER_KATAKANA_ONLY)) {
                if (mService.isHiragana) ""
                else label.removePrefix(SKKFlickRule.MARKER_KATAKANA_ONLY)
            } else label
            mCurrentPopupLabels[i] = l.replace(SKKFlickRule.ACTION_NOOP, "")
                .convertTo(mService.kanaState, reversed = true)
        }

        if (skkPrefs.popupOnPress) showPopup()
    }

    private fun showPopup() {
        if (!skkPrefs.usePopup || !isEditorMode && mCurrentPopupLabels[0] == "") return

        // FlickJP は mPopupTextView の内容をロジックに使わない
        setupPopupTextView()

        val (x, y) = calculatePopupPos()
        mPopup?.showAtLocation(this, android.view.Gravity.NO_GRAVITY, x, y)
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

    // repeatable なキーは release を通らないので、ここで処理する必要がある
    override fun onKey(primaryCode: Int) {
        val keyIndex = keyboard.keys.indexOfFirst { it.codes.main[0] == primaryCode }
        mProcessedOnKey = true
        when (getKeyAction(keyIndex)) {
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

            SKKFlickRule.ACTION_SPACE -> if (mFlickState == EnumSet.of(FlickState.NONE))
                mService.processKey(' ')

            // これは release に書いてもいいかも
            SKKFlickRule.ACTION_SETTINGS -> context.startActivity(
                Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            else -> mProcessedOnKey = false
        }
    }

    private fun release() {
        val flickIndex = getFlickIndex(mFlickState)
        val action = getKeyAction(mLastPressedIndex, flickIndex)

        if (isEditorMode) {
            if (mLastPressedIndex != -1) onFlickListener?.onFlick(mLastPressedIndex, flickIndex)
        } else {
            if (!mProcessedOnKey) executeAction(action)

            // onKey で処理されたキーもここでシフトを消費するので executeAction に関係なく実行
            if (!isCapsLocked &&
                action != SKKFlickRule.ACTION_CAPS &&
                action != SKKFlickRule.ACTION_TOGGLE_SHIFT
            ) isShifted = false
        }

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
