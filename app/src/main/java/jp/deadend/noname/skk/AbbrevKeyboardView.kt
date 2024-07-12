package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.util.AttributeSet
import android.view.MotionEvent

class AbbrevKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private lateinit var mService: SKKService
    private val mKeyboard = Keyboard(context, R.xml.abbrev)
    private var mFlickSensitivitySquared = 100

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    init {
        keyboard = mKeyboard
        onKeyboardActionListener = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isShifted = false
        isCapsLocked = false
    }

    fun setService(listener: SKKService) {
        mService = listener
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
                isFlicked = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = me.x - flickStartX
                val dy = me.y - flickStartY
                val dx2 = dx * dx
                val dy2 = dy * dy
                if (dx2 + dy2 > mFlickSensitivitySquared) {
                    when {
                        dy < 0 && dx2 < dy2 -> {
                            isFlicked = 1
                            return true // 上フリック
                        }
                        dy > 0 && dx2 < dy2 -> {
                            isFlicked = -1
                            return true // 下フリック
                        }
                        else -> {
                            isFlicked = 0
                            // 左右に外れたので別のキーになるかもしれない
                        }
                    }
                } else {
                    isFlicked = 0
                    return true // フリックなし (に戻す)
                }
            }
        }
        return super.onModifiedTouchEvent(me, possiblePoly)
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleBackspace()) mService.keyDownUp(KeyEvent.KEYCODE_DEL)
            }
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                isCapsLocked = false
            }
            Keyboard.KEYCODE_CAPSLOCK -> {
                isShifted = true
                isCapsLocked = true
            }
            KEYCODE_ABBREV_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_ABBREV_CANCEL -> mService.handleCancel()
            KEYCODE_ABBREV_ZENKAKU -> mService.processKey(primaryCode)
            else -> {
                val shiftedCode = keyboard.shiftedCodes[primaryCode] ?: 0
                val downCode = keyboard.downCodes[primaryCode] ?: 0
                val code = when {
                    isFlicked == -1 ->
                        if (downCode > 0) downCode else primaryCode
                    isShifted xor (isFlicked == 1) ->
                        if (shiftedCode > 0) shiftedCode else primaryCode
                    else -> primaryCode
                }
                mService.processKey(code)
                if (!isCapsLocked) {
                    isShifted = false
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {}

    override fun onRelease(primaryCode: Int) {}

    override fun onText(text: CharSequence) {}

    override fun swipeRight() {}

    override fun swipeLeft() {}

    override fun swipeDown() {}

    override fun swipeUp() {}

    companion object {
        private const val KEYCODE_ABBREV_CANCEL   = -1009
        private const val KEYCODE_ABBREV_ZENKAKU  = -1010
        private const val KEYCODE_ABBREV_ENTER    = -1011
    }

}