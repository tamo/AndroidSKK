package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.AttributeSet

class QwertyKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private lateinit var mService: SKKService

    private val mLatinKeyboard = Keyboard(context, R.xml.qwerty)
    private val mSymbolsKeyboard = Keyboard(context, R.xml.symbols)

    private var mFlickSensitivitySquared = 100
    private var mFlickStartX = -1f
    private var mFlickStartY = -1f
    private var mFlicked = false

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mFlickStartX = event.rawX
                mFlickStartY = event.rawY
                mFlicked = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - mFlickStartX
                val dy = event.rawY - mFlickStartY
                val dx2 = dx * dx
                val dy2 = dy * dy
                if (dx2 + dy2 > mFlickSensitivitySquared) {
                    if (dy < 0 && dx2 < dy2) {
                        val oldMFlicked = mFlicked
                        mFlicked = true
                        if (!oldMFlicked) { switchCaseInPreview() }
                        return true
                    }
                }
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
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
                if (!mService.handleEnter()) mService.pressEnter()
                mService.updateSuggestionsASCII()
            }
            KEYCODE_QWERTY_TOJP -> mService.handleKanaKey()
            KEYCODE_QWERTY_TOSYM -> keyboard = mSymbolsKeyboard
            KEYCODE_QWERTY_TOLATIN -> {
                keyboard = mLatinKeyboard
                isShifted = keyboard.isShifted
                isCapsLocked = keyboard.isCapsLocked
            }
            else -> {
                val shiftedCode = keyboard.shiftedCodes[primaryCode] ?: 0
                val code = when {
                    isShifted xor mFlicked -> when {
                        shiftedCode > 0 -> shiftedCode
                        keyboard === mLatinKeyboard -> Character.toUpperCase(primaryCode)
                        else -> primaryCode
                    }
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

    override fun onPress(primaryCode: Int) {}

    override fun onRelease(primaryCode: Int) {}

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