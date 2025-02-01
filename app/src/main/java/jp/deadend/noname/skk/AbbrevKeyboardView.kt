package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.util.AttributeSet
import android.view.MotionEvent

class AbbrevKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private var mFlickSensitivitySquared = 100

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    override fun setService(service: SKKService) {
        super.setService(service)
        keyboard = Keyboard(context, R.xml.abbrev, mService.mScreenWidth, mService.mScreenHeight)
        onKeyboardActionListener = this
        setKeyState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isShifted = false
        isCapsLocked = false
    }

    fun setFlickSensitivity(sensitivity: Int) {
        mFlickSensitivitySquared = sensitivity * sensitivity
    }

//    override fun onLongPress(key: Keyboard.Key): Boolean {
//        if (key.codes[0] == KEYCODE_ABBREV_ENTER) {
//            mService.keyDownUp(KeyEvent.KEYCODE_SEARCH)
//            return true
//        }
//
//        return super.onLongPress(key)
//    }

    override fun onModifiedTouchEvent(me: MotionEvent, possiblePoly: Boolean): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                flickStartX = me.x
                flickStartY = me.y
                isFlicked = FLICK_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = me.x - flickStartX
                val dy = me.y - flickStartY
                val dx2 = dx * dx
                val dy2 = dy * dy
                if (dx2 + dy2 > mFlickSensitivitySquared) {
                    when {
                        dy < 0 && dx2 < dy2 -> {
                            isFlicked = FLICK_UP
                            return true
                        }
                        dy > 0 && dx2 < dy2 -> {
                            isFlicked = FLICK_DOWN
                            return true
                        }
                        else -> {
                            isFlicked = FLICK_NONE
                            // 左右に外れたので別のキーになるかもしれないので return しない
                        }
                    }
                } else {
                    isFlicked = FLICK_NONE
                    return true // フリックなし (に戻す)
                }
            }
        }
        return super.onModifiedTouchEvent(me, possiblePoly)
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            // repeatable
            Keyboard.KEYCODE_DELETE -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleBackspace()) mService.keyDownUp(KeyEvent.KEYCODE_DEL)
            }
            // codes[0] 以外
            Keyboard.KEYCODE_CAPSLOCK -> {
                isShifted = true
                isCapsLocked = true
            }
        }
    }

    override fun onRelease(primaryCode: Int) {
        when (primaryCode) {
            // onKey で消費済み
            Keyboard.KEYCODE_DELETE -> {}
            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                isCapsLocked = false
            }
            KEYCODE_ABBREV_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_ABBREV_TOJP -> {
                when (isFlicked) {
                    if (skkPrefs.preferFlick) FLICK_NONE else FLICK_DOWN -> mService.changeToFlick()
                    if (skkPrefs.preferFlick) FLICK_DOWN else FLICK_NONE -> mService.handleKanaKey()
                }
            }
            KEYCODE_ABBREV_ZENKAKU -> {
                when (isFlicked) {
                    FLICK_NONE -> mService.processKey(primaryCode)
                    FLICK_DOWN -> mService.handleCancel()
                }
            }
            else -> {
                val shiftedCode = keyboard.shiftedCodes[primaryCode] ?: 0
                val downCode = keyboard.downCodes[primaryCode] ?: 0
                val code = when {
                    isFlicked == FLICK_DOWN ->
                        if (downCode > 0) downCode else primaryCode
                    isShifted xor (isFlicked == FLICK_UP) ->
                        if (shiftedCode > 0) shiftedCode else primaryCode
                    else -> primaryCode
                }
                mService.processKey(code)
            }
        }
        if (primaryCode != Keyboard.KEYCODE_SHIFT) {
            if (!isCapsLocked) isShifted = false
        }
    }

    fun setKeyState(): AbbrevKeyboardView {
        val kanaKey = keyboard.keys.find { it.codes[0] == KEYCODE_ABBREV_TOJP }
        kanaKey?.label = if (skkPrefs.preferFlick) "Flick" else "かな"
        kanaKey?.downLabel = if (skkPrefs.preferFlick) "かな" else "Flick"

        invalidateAllKeys()
        return this
    }

    override fun onPress(primaryCode: Int) {}

    override fun onText(text: CharSequence) {}

    override fun swipeRight() {}

    override fun swipeLeft() {}

    override fun swipeDown() {}

    override fun swipeUp() {}

    companion object {
        private const val KEYCODE_ABBREV_TOJP     = -1008
        // private const val KEYCODE_ABBREV_CANCEL   = -1009
        private const val KEYCODE_ABBREV_ZENKAKU  = -1010
        private const val KEYCODE_ABBREV_ENTER    = -1011
        private const val FLICK_UP = 1
        private const val FLICK_NONE = 0
        private const val FLICK_DOWN = -1
    }

}