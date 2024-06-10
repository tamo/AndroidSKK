package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.AttributeSet

class QwertyKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private lateinit var mService: SKKService

    private val mLatinKeyboard = Keyboard(context, R.xml.qwerty)
    private val mSymbolsKeyboard = Keyboard(context, R.xml.symbols)
    private val mSymbolsShiftedKeyboard = Keyboard(context, R.xml.symbols_shift)

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
                        mFlicked = true
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
            }
            Keyboard.KEYCODE_SHIFT -> {
                isShifted = !isShifted
                if (keyboard === mSymbolsKeyboard) {
                    mSymbolsKeyboard.isShifted = true
                    keyboard = mSymbolsShiftedKeyboard
                    mSymbolsShiftedKeyboard.isShifted = true
                } else if (keyboard === mSymbolsShiftedKeyboard) {
                    mSymbolsShiftedKeyboard.isShifted = false
                    keyboard = mSymbolsKeyboard
                    mSymbolsKeyboard.isShifted = false
                }
            }
            KEYCODE_QWERTY_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_QWERTY_TOJP -> mService.handleKanaKey()
            KEYCODE_QWERTY_TOSYM -> keyboard = mSymbolsKeyboard
            KEYCODE_QWERTY_TOLATIN -> keyboard = mLatinKeyboard
            else -> {
                val code = if (keyboard === mLatinKeyboard && (isShifted xor mFlicked)) {
                    Character.toUpperCase(primaryCode)
                } else {
                    primaryCode
                }
                mService.commitTextSKK(code.toChar().toString(), 1)
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