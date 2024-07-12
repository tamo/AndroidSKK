package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.AttributeSet
import kotlin.math.ceil

class QwertyKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private lateinit var mService: SKKService

    val mLatinKeyboard = Keyboard(context, R.xml.qwerty)
    val mSymbolsKeyboard = Keyboard(context, R.xml.symbols)

    private var mFlickSensitivitySquared = 100

    private var mSpacePressed = false
    private var mSpaceFlicked = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    init {
        keyboard = mLatinKeyboard
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

    override fun onLongPress(key: Keyboard.Key): Boolean {
        if (key.codes[0] == KEYCODE_QWERTY_ENTER) {
            mService.keyDownUp(KeyEvent.KEYCODE_SEARCH)
            return true
        }

        return super.onLongPress(key)
    }

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
                        mSpacePressed -> {
                            repeat(ceil(dx2 / 1500).toInt()) {
                                if (dx < 0) {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
                                } else {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
                                }
                                mSpaceFlicked = true
                            }
                            flickStartX = me.x
                            return true
                        }
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
                mService.updateSuggestionsASCII()
            }
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                isCapsLocked = false
            }
            Keyboard.KEYCODE_CAPSLOCK -> {
                isShifted = true
                isCapsLocked = true
            }
            KEYCODE_QWERTY_ENTER -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleEnter()) mService.pressEnter()
                mService.updateSuggestionsASCII()
            }
            KEYCODE_QWERTY_TOJP -> mService.handleKanaKey()
            KEYCODE_QWERTY_TOSYM -> {
                keyboard = mSymbolsKeyboard
                isShifted = keyboard.isShifted
                isCapsLocked = keyboard.isCapsLocked
            }
            KEYCODE_QWERTY_TOLATIN -> {
                keyboard = mLatinKeyboard
                isShifted = keyboard.isShifted
                isCapsLocked = keyboard.isCapsLocked
            }
            else -> {
                if (primaryCode == ' '.code && mSpaceFlicked) {
                    mService.updateSuggestionsASCII()
                    return
                }
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
                mService.updateSuggestionsASCII()
                if (keyboard === mLatinKeyboard && !isCapsLocked) {
                    isShifted = false
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        mSpacePressed = (primaryCode == ' '.code)
        mSpaceFlicked = false
    }

    override fun onRelease(primaryCode: Int) {
        mSpacePressed = false
    }

    override fun onText(text: CharSequence) {}

    override fun swipeRight() {}

    override fun swipeLeft() {}

    override fun swipeDown() {}

    override fun swipeUp() {}

    companion object {
        private const val KEYCODE_QWERTY_TOJP    = -1008
        private const val KEYCODE_QWERTY_TOSYM   = -1009
        private const val KEYCODE_QWERTY_TOLATIN = -1010
        private const val KEYCODE_QWERTY_ENTER   = -1011
    }

}