package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import jp.deadend.noname.skk.databinding.PopupFlickguideBinding
import jp.deadend.noname.skk.engine.RomajiConverter.getVowel
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKAbbrevState
import jp.deadend.noname.skk.engine.SKKChooseState
import jp.deadend.noname.skk.engine.SKKEmojiState
import jp.deadend.noname.skk.engine.SKKEngine
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import java.util.EnumSet

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
    private var mCurrentPopupLabels = (0..14).map { "" }.toTypedArray()

    private var mUsePopup = true
    private var mFixedPopup = false
    private var mPopup: PopupWindow? = null
    private var mPopupTextView: Array<TextView>? = null
    private val mPopupSize = 120
    private val mPopupOffset = intArrayOf(0, 0)
    private val mFixedPopupPos = intArrayOf(0, 0)

    // シンプル切り替え用
    private var mIsASCII = false

    //フリックガイドTextView用
    private val mFlickGuideLabelList = SparseArray<Array<String>>()

    init {
        val a = mFlickGuideLabelList
        val t = POPUP_LABELS_NULL
        a.append(KEYCODE_GODAN_CANCEL, arrayOf("CXL", "：", "貼", "＞", "G検") + t)
        a.append(KEYCODE_GODAN_CHAR_A, arrayOf("A", "あっ", "あん", "ゃ", "1") + t)
        a.append(KEYCODE_GODAN_CHAR_K, arrayOf("K", "＠", "Q", "G", "2", "", "", "□", "＾") + t)
        a.append(KEYCODE_GODAN_CHAR_H, arrayOf("H", "P", "F", "B", "3") + t)
        a.append(KEYCODE_GODAN_CHAR_I, arrayOf("I", "いっ", "いん", "ぃ", "4") + t)
        a.append(KEYCODE_GODAN_CHAR_S, arrayOf("S", "＆", "J", "Z", "5", "", "", "％", "＄") + t)
        a.append(
            KEYCODE_GODAN_CHAR_M,
            arrayOf("M", "／", "L", "ー", "6", "", "", "￥", "＼", "", "", "〜", "＿") + t
        )
        a.append(KEYCODE_GODAN_CHAR_U, arrayOf("U", "うっ", "うん", "ゅ", "7") + t)
        a.append(KEYCODE_GODAN_CHAR_T, arrayOf("T", "＋", "C", "D", "8", "", "", "＃", "＊") + t)
        a.append(
            KEYCODE_GODAN_CHAR_Y,
            arrayOf("Y", "（", "X", "）", "9", "＜", "＞", "［", "｛", "", "", "］", "｝") + t
        )
        a.append(KEYCODE_GODAN_CHAR_Q, arrayOf("Q", "絵☻", "^J", "記号", "ｶﾅ") + t)
        a.append(KEYCODE_GODAN_CHAR_E, arrayOf("E", "えっ", "えん", "ぇ", "00") + t)
        a.append(
            KEYCODE_GODAN_CHAR_N,
            arrayOf("N", "：", "ん", "・", "0", "", "", "；", "＜", "", "", "｜", "＞") + t
        )
        a.append(
            KEYCODE_GODAN_CHAR_R,
            arrayOf("R", "。", "？", "！", "、", "", "", "…", "〜", "", "", "↑", "↓", "→", "←")
        )
        a.append(KEYCODE_GODAN_CHAR_O, arrayOf("O", "おっ", "おん", "ょ", "＇", "′", "`") + t)
        a.append(
            KEYCODE_GODAN_CHAR_W,
            arrayOf("W", "「", "V", "」", "＂", "", "", "『", "【", "", "", "』", "】", "”", "“")
        )
        a.append(KEYCODE_GODAN_SPACE, arrayOf("SPACE", "", "Mush") + t)
        a.append(Keyboard.KEYCODE_SHIFT, arrayOf("SHIFT", "", "CAPSLOCK") + t)
    }

    override fun setService(service: SKKService) {
        super.setService(service)
        onKeyboardActionListener = this
        isPreviewEnabled = false
        setBackgroundColor(0x00000000)

        keyboard = Keyboard(
            context,
            if (!mIsASCII && skkPrefs.simpleGodan) R.xml.keys_godan_simple else R.xml.keys_godan,
            mService.mScreenWidth,
            mService.mScreenHeight
        )
        setKeyState(mService.engineState)
    }

    override fun onDetachedFromWindow() {
        if (mPopup?.isShowing == true) mPopup!!.dismiss()
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

        shiftKey.codes[0] = Keyboard.KEYCODE_SHIFT
        shiftKey.codes[1] = Keyboard.KEYCODE_CAPSLOCK
        shiftKey.label = ""
        shiftKey.icon = ResourcesCompat.getDrawable(
            resources, R.drawable.ic_keyboard_shift, null
        )?.also {
            it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
        }

        cancelKey.codes[0] = KEYCODE_GODAN_CANCEL
        cancelKey.codes[1] = KEYCODE_GODAN_NONE
        cancelKey.label = /* if (!mIsASCII && skkPrefs.simpleGodan) "cxl" else */
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

        cancelKey.codes[0] = KEYCODE_GODAN_CANCEL
        cancelKey.label = /* if (!mIsASCII && skkPrefs.simpleGodan) "cxl" else */
            "貼付\n：cxl＞\ngoogle"

        qKey.codes[0] = KEYCODE_GODAN_CHAR_Q
        qKey.label = /* if (!mIsASCII && skkPrefs.simpleGodan) "Q" else */ "^J\n☻Q記\n半ｶﾅ"
    }

    override fun setKeyState(state: SKKState): GodanKeyboardView {
        val wasASCII = mIsASCII
        mIsASCII = (state in listOf(SKKAbbrevState, SKKASCIIState, SKKZenkakuState))
        if (wasASCII != mIsASCII) {
            prepareNewKeyboard(context, width, height, skkPrefs.keyPaddingBottom)
        }

        val qKey = checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CHAR_Q)) { "BUG: no Q key" }
        qKey.on = (state !in listOf(
            SKKASCIIState,
            SKKEmojiState,
            SKKZenkakuState
        ) && !mService.isHiragana)
            .also { isKatakana ->
                listOf(
                    KEYCODE_GODAN_CHAR_A, KEYCODE_GODAN_CHAR_I, KEYCODE_GODAN_CHAR_U,
                    KEYCODE_GODAN_CHAR_E, KEYCODE_GODAN_CHAR_O, KEYCODE_GODAN_CHAR_N,
                ).forEach { keyCode ->
                    val key =
                        checkNotNull(findKeyByCode(keyboard, keyCode)) { "BUG: no $keyCode key" }
                    key.label = if (isKatakana)
                        hiragana2katakana(key.label.toString()) ?: ""
                    else katakana2hiragana(key.label.toString()) ?: ""
                }
            }

        val lKey = checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_CHAR_L)) { "BUG: no L key" }
        lKey.on = mIsASCII

        isZenkaku = (state === SKKZenkakuState)

        invalidateAllKeys()
        return this
    }

    private fun findKeyByCode(keyboard: Keyboard, code: Int) =
        keyboard.keys.find { it.codes[0] == code }

    private fun onSetShifted(isShifted: Boolean) {
        val spaceKey =
            checkNotNull(findKeyByCode(keyboard, KEYCODE_GODAN_SPACE)) { "BUG: no space key" }
        spaceKey.label = if (isShifted) "設定" else ""
    }

    internal fun prepareNewKeyboard(
        context: Context,
        widthPixel: Int,
        heightPixel: Int,
        bottomPercent: Int
    ) {
        keyboard = Keyboard(
            context,
            if (!mIsASCII && skkPrefs.simpleGodan) R.xml.keys_godan_simple else R.xml.keys_godan,
            mService.mScreenWidth,
            mService.mScreenHeight
        )
        keyboard.isShifted = isShifted
        keyboard.isCapsLocked = isCapsLocked
        keyboard.resize(widthPixel, heightPixel, bottomPercent)
        invalidateAllKeys()

        readPrefs(context)
    }

    private fun readPrefs(context: Context) {
        // シフトかな交換
        setShiftPosition()
        // その後でキャンセル Q 交換
        setCancelPosition()

        when {
            skkPrefs.useSoftCancelKey -> {
                findKeyByCode(keyboard, KEYCODE_GODAN_KOMOJI)?.label = "小\n ◻゙cxl◻゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_GODAN_KOMOJI,
                    arrayOf("CXL", "◻゙", "小", "◻゚", "▽") + POPUP_LABELS_NULL
                )
            }

            skkPrefs.useSoftTransKey -> {
                findKeyByCode(keyboard, KEYCODE_GODAN_KOMOJI)?.label = "cxl\n ◻゙□゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_GODAN_KOMOJI,
                    arrayOf("◻゙□゚", "◻゙", "CXL", "◻゚", "▽") + POPUP_LABELS_NULL
                )
            }

            else -> {
                findKeyByCode(keyboard, KEYCODE_GODAN_KOMOJI)?.label = "cxl\n ◻゙小◻゚ \n▽"
                mFlickGuideLabelList.put(
                    KEYCODE_GODAN_KOMOJI,
                    arrayOf("小", "◻゙", "CXL", "◻゚", "▽") + POPUP_LABELS_NULL
                )
            }
        }
        // ポップアップ
        mUsePopup = skkPrefs.usePopup
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
                labels[7].text = mCurrentPopupLabels[7]
                labels[8].text = mCurrentPopupLabels[8]
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
                labels[2].text = mCurrentPopupLabels[2]
                labels[9].text = mCurrentPopupLabels[9]
                labels[10].text = mCurrentPopupLabels[10]
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
                labels[3].text = mCurrentPopupLabels[3]
                labels[11].text = mCurrentPopupLabels[11]
                labels[12].text = mCurrentPopupLabels[12]
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
                labels[4].text = mCurrentPopupLabels[4]
                labels[13].text = mCurrentPopupLabels[13]
                labels[14].text = mCurrentPopupLabels[14]
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
                        when {
                            adx2 > ady2 && adx2 > mFlickSensitivitySquared -> {
                                if (adx < 0) {
                                    if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT)) {
                                        mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
                                    }
                                } else if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT)) {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
                                }
                                mArrowFlicked = true
                                mArrowStartX = me.x
                                mArrowStartY = me.y
                                stopRepeatKey()
                                return true
                            }

                            adx2 < ady2 && ady2 > mFlickSensitivitySquared -> {
                                if (ady < 0) {
                                    if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_UP)) {
                                        mService.keyDownUp(KeyEvent.KEYCODE_DPAD_UP)
                                    }
                                } else if (!mService.handleDpad(KeyEvent.KEYCODE_DPAD_DOWN)) {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_DOWN)
                                }
                                mArrowFlicked = true
                                mArrowStartY = me.y
                                mArrowStartX = me.x
                                stopRepeatKey()
                                return true
                            }
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
        val hasLeftCurve = mCurrentPopupLabels[5].isNotEmpty()
        val hasRightCurve = mCurrentPopupLabels[6].isNotEmpty()

        val newState = when (val dAngle = diamondAngle(dx, dy)) {
            in 0.5f..1.5f -> EnumSet.of(FlickState.DOWN)
            in 1.5f..2.5f -> EnumSet.of(FlickState.LEFT)
            in 2.29f..2.71f -> when {
                (hasLeftCurve) -> EnumSet.of(FlickState.NONE, FlickState.CURVE_LEFT)
                (dAngle < 2.5f) -> EnumSet.of(FlickState.LEFT)
                else -> EnumSet.of(FlickState.UP)
            }

            in 2.5f..3.5f -> EnumSet.of(FlickState.UP)
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

    override fun onLongPress(key: Keyboard.Key): Boolean {
        val code = key.codes[0]
        if (code == KEYCODE_GODAN_ENTER) {
            mService.keyDownUp(KeyEvent.KEYCODE_SEARCH)
            return true
        }

        return super.onLongPress(key)
    }

    override fun onPress(primaryCode: Int) {
        if (mFlickState == EnumSet.of(FlickState.NONE)) {
            mLastPressedKey = primaryCode
        }

        mArrowFlicked = false

        val labels = mFlickGuideLabelList.get(primaryCode)
        if (labels == null) {
            mCurrentPopupLabels = (0..14).map { "" }.toTypedArray()
            return
        }

        for (i in 0..14) {
            if (mService.isHiragana) {
                mCurrentPopupLabels[i] = labels[i]
            } else {
                mCurrentPopupLabels[i] = checkNotNull(
                    hiragana2katakana(labels[i], reversed = true)
                ) { "BUG: invalid popup label!!" }
            }
        }
        setupPopupTextView()

        if (mFixedPopupPos[0] == 0) calculatePopupPos()

        if (mUsePopup) {
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
            // repeatable
            Keyboard.KEYCODE_DELETE -> if (!mService.handleBackspace()) {
                if (!isCapsLocked) isShifted = false
                mService.keyDownUp(KeyEvent.KEYCODE_DEL)
            }

            KEYCODE_GODAN_LEFT -> if (!mArrowFlicked && !mService.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
            }

            KEYCODE_GODAN_RIGHT -> if (!mArrowFlicked && !mService.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT)) {
                mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
            }

            KEYCODE_GODAN_SPACE -> if (isShifted) {
                val intent = Intent(context, SKKSettingsActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else if (mFlickState == EnumSet.of(FlickState.NONE)) {
                mService.processKey(' '.code)
            }
            // 不明: release で処理してもいいのか?
            33, 40, 41, 44, 46, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 63, 91, 93 -> {
                // ! ( ) , . 0〜9 ? [ ]
                mService.processKey(primaryCode)
                if (!isCapsLocked) isShifted = false
            }
            // codes[0] 以外
            Keyboard.KEYCODE_CAPSLOCK -> {
                isShifted = true
                isCapsLocked = true
            }
        }
    }

    private fun release() {
        when (mLastPressedKey) {
            // onKey で消費済み
            Keyboard.KEYCODE_DELETE, Keyboard.KEYCODE_CAPSLOCK -> {}
            // repeatable のフリック
            KEYCODE_GODAN_SPACE -> if (mFlickState == EnumSet.of(FlickState.UP))
                mService.sendToMushroom()
            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> {
                when (mFlickState) {
                    EnumSet.of(FlickState.NONE) -> {
                        isShifted = !isShifted
                        isCapsLocked = false
                    }

                    EnumSet.of(FlickState.UP) -> {
                        isShifted = true
                        isCapsLocked = true
                    }
                }
                onSetShifted(isShifted)
            }

            KEYCODE_GODAN_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_GODAN_CANCEL -> {
                when (mFlickState) {
                    EnumSet.of(FlickState.NONE) -> mService.handleCancel()
                    EnumSet.of(FlickState.LEFT) -> mService.processKey(':'.code)
                    EnumSet.of(FlickState.UP) -> mService.pasteClip()
                    EnumSet.of(FlickState.RIGHT) -> mService.processKey('>'.code)
                    EnumSet.of(FlickState.DOWN) -> mService.googleTransliterate()
                }
            }

            KEYCODE_GODAN_KOMOJI -> {
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

                    EnumSet.of(FlickState.DOWN) -> {
                        mService.changeLastChar(SKKEngine.LAST_CONVERSION_SHIFT)
                    }
                }
            }

            KEYCODE_GODAN_PASTE -> mService.pasteClip()
            KEYCODE_GODAN_GOOGLE -> mService.googleTransliterate()
            KEYCODE_GODAN_CHAR_L -> {
                when (mService.engineState) {
                    SKKAbbrevState -> mService.processKey(-1010) // かな入力(KanjiState)
                    SKKASCIIState, SKKZenkakuState -> mService.handleKanaKey()
                    else -> mService.processKey(if (isShifted) 'L'.code else 'l'.code)
                }
            }

            KEYCODE_GODAN_CHAR_A,
            KEYCODE_GODAN_CHAR_K,
            KEYCODE_GODAN_CHAR_H,
            KEYCODE_GODAN_CHAR_I,
            KEYCODE_GODAN_CHAR_S,
            KEYCODE_GODAN_CHAR_M,
            KEYCODE_GODAN_CHAR_U,
            KEYCODE_GODAN_CHAR_T,
            KEYCODE_GODAN_CHAR_Y,
            KEYCODE_GODAN_CHAR_Q, // ←注意
            KEYCODE_GODAN_CHAR_E,
            KEYCODE_GODAN_CHAR_N,
            KEYCODE_GODAN_CHAR_R,
            KEYCODE_GODAN_CHAR_O,
            KEYCODE_GODAN_CHAR_W -> {
                val flickIndex = when {
                    mFlickState.contains(FlickState.NONE) -> 0
                    mFlickState.contains(FlickState.LEFT) -> 1
                    mFlickState.contains(FlickState.UP) -> 2
                    mFlickState.contains(FlickState.RIGHT) -> 3
                    mFlickState.contains(FlickState.DOWN) -> 4
                    else -> throw IllegalStateException("mFlickState is $mFlickState")
                }.let {
                    when {
                        isLeftCurve(mFlickState) -> it * 2 + 5
                        isRightCurve(mFlickState) -> it * 2 + 6
                        else -> it
                    }
                }
                val popupText = mPopupTextView?.getOrNull(flickIndex)?.text ?: ""
                if (popupText.length == 1) {
                    val code = when (val guidedCode = popupText[0].code) {
                        in 32..126 -> guidedCode
                        in (32 - 0x20 + 0xFF00)..(126 - 0x20 + 0xFF00) -> guidedCode - 0xFF00 + 0x20
                        '…'.code -> {
                            mService.processKey('z'.code)
                            '.'.code
                        }

                        '□'.code -> ' '.code
                        'ー'.code -> '-'.code
                        '〜'.code -> '~'.code
                        '『'.code -> {
                            mService.processKey('z'.code)
                            '['.code
                        }

                        '』'.code -> {
                            mService.processKey('z'.code)
                            ']'.code
                        }

                        '、'.code -> ','.code
                        '。'.code -> '.'.code
                        '←'.code, '↑'.code, '→'.code, '↓'.code -> {
                            mService.processKey('z'.code)
                            when (Char(guidedCode)) {
                                '←' -> 'h'.code
                                '↑' -> 'k'.code
                                '→' -> 'l'.code
                                '↓' -> 'j'.code
                                else -> throw IllegalArgumentException(
                                    "Char($guidedCode) is not an arrow: ${
                                        Char(
                                            guidedCode
                                        )
                                    }"
                                )
                            }
                        }

                        '￥'.code -> {
                            if (mService.engineState.isTransient) {
                                '\\'.code // 無効だけど
                            } else {
                                mService.commitTextSKK("￥") // 全角
                                0
                            }
                        }

                        'ゃ'.code, 'ぃ'.code, 'ゅ'.code, 'ぇ'.code, 'ょ'.code,
                        'ャ'.code, 'ィ'.code, 'ュ'.code, 'ェ'.code, 'ョ'.code -> {
                            mService.processKey('y'.code)
                            getVowel(Char(guidedCode).toString())?.code ?: 0
                        }

                        'ん'.code, 'ン'.code -> {
                            if (!mService.isComposingN) mService.processKey('n'.code)
                            'n'.code
                        }

                        else -> {
                            mService.commitTextSKK(Char(guidedCode).toString())
                            0
                        }
                    }
                    if (code != 0) {
                        mService.processKey(if (!isShifted) Character.toLowerCase(code) else code)
                    }
                } else when (popupText) {
                    "^J" -> mService.handleKanaKey()
                    "ｶﾅ" -> {
                        if (mIsASCII) mService.handleKanaKey() // ひらがなを経由
                        mService.processKey(17)
                    }

                    "記号" -> mService.symbolCandidates(isShifted)
                    "絵☻" -> mService.emojiCandidates(isShifted)
                    else -> if (
                        popupText.length == 2 &&
                        popupText[0] in listOf(
                            'あ', 'い', 'う', 'え', 'お',
                            'ア', 'イ', 'ウ', 'エ', 'オ'
                        )
                    ) {
                        mPopupTextView?.getOrNull(0)?.text?.let { vowelStr ->
                            when (val vowel = vowelStr.first()) {
                                'A', 'I', 'U', 'E', 'O' -> {
                                    mService.suspendSuggestions()
                                    mService.processKey(
                                        if (!isShifted) {
                                            Character.toLowerCase(vowel.code)
                                        } else {
                                            vowel.code
                                        }
                                    )
                                    mService.resumeSuggestions()
                                    // 前の processKey で▼モードになっていたら次で確定してしまうので止まる
                                    if (mService.engineState !== SKKChooseState) when (popupText[1]) {
                                        'っ', 'ッ' -> "xtu"
                                        'ん', 'ン' -> "nn"
                                        else -> throw RuntimeException("mPopupTextView is $mPopupTextView")
                                    }.forEach {
                                        mService.processKey(it.code)
                                    }
                                }
                            }
                        }
                    } else {
                        // 00
                        popupText.forEach { mService.processKey(it.code) }
                    }
                }
            }
        }

        if (mLastPressedKey != Keyboard.KEYCODE_SHIFT) {
            if (!isCapsLocked) isShifted = false
            onSetShifted(isShifted)
        }
        setKeyState(mService.engineState)

        mLastPressedKey = KEYCODE_GODAN_NONE
        mFlickState = EnumSet.of(FlickState.NONE)
        mFlickStartX = -1f
        mFlickStartY = -1f
        val popup = checkNotNull(mPopup) { "BUG: popup is null!!" }
        if (popup.isShowing) popup.dismiss()
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
