package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.util.AttributeSet

class AbbrevKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    private lateinit var mService: SKKService
    private val mKeyboard = Keyboard(context, R.xml.abbrev)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    init {
        keyboard = mKeyboard
        onKeyboardActionListener = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isShifted = false
    }

    fun setService(listener: SKKService) {
        mService = listener
    }

    override fun onKey(primaryCode: Int) {
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> isShifted = !isShifted
            Keyboard.KEYCODE_DELETE -> {
                if (!mService.handleBackspace()) mService.keyDownUp(KeyEvent.KEYCODE_DEL)
            }
            KEYCODE_ABBREV_ENTER -> if (!mService.handleEnter()) mService.pressEnter()
            KEYCODE_ABBREV_CANCEL -> mService.handleCancel()
            KEYCODE_ABBREV_ZENKAKU -> mService.processKey(primaryCode)
            else -> {
                val code = if (isShifted) Character.toUpperCase(primaryCode) else primaryCode
                mService.processKey(code)
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