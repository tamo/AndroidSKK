package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.AttributeSet
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState

class QwertyKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    val mLatinKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.qwerty, mService.mScreenWidth, mService.mScreenHeight)
    }
    val mSymbolsKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.symbols, mService.mScreenWidth, mService.mScreenHeight)
    }

    private var mFlickSensitivitySquared = 100

    private var mSpacePressed = false
    private var mSpaceFlicked = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    override fun setService(service: SKKService) {
        super.setService(service)
        keyboard = mLatinKeyboard
        onKeyboardActionListener = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isShifted = false
        isCapsLocked = false
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
                isFlicked = FLICK_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = me.x - flickStartX
                val dy = me.y - flickStartY
                val dx2 = dx * dx
                val dy2 = dy * dy
                if (dx2 + dy2 > mFlickSensitivitySquared) {
                    when {
                        mSpacePressed -> {
                            if (dx2 > dy2 && dx2 > mFlickSensitivitySquared) {
                                if (dx < 0) {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT)
                                } else {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT)
                                }
                                mSpaceFlicked = true
                                flickStartX = me.x
                                flickStartY = me.y
                            } else if (dx2 < dy2 && dy2 > mFlickSensitivitySquared) {
                                if (dy < 0) {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_UP)
                                } else {
                                    mService.keyDownUp(KeyEvent.KEYCODE_DPAD_DOWN)
                                }
                                mSpaceFlicked = true
                                flickStartY = me.y
                                flickStartX = me.x
                            }
                            return true
                        }
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
                            // 左右に外れて別のキーになるかもしれないので return しない
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
            KEYCODE_QWERTY_ENTER -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleEnter()) mService.pressEnter()
            }
            KEYCODE_QWERTY_TOJP -> {
                when (isFlicked) {
                    if (skkPrefs.preferFlick) FLICK_NONE else FLICK_DOWN -> mService.changeToFlick()
                    if (skkPrefs.preferFlick) FLICK_DOWN else FLICK_NONE -> mService.handleKanaKey()
                    FLICK_UP -> mService.pasteClip()
                }
            }
            KEYCODE_QWERTY_TOSYM -> {
                when (isFlicked) {
                    FLICK_NONE -> {
                        keyboard = mSymbolsKeyboard
                        isShifted = keyboard.isShifted
                        isCapsLocked = keyboard.isCapsLocked
                    }
                    FLICK_UP -> mService.googleTransliterate()
                    FLICK_DOWN -> mService.handleCancel()
                }
            }
            KEYCODE_QWERTY_TOLATIN -> {
                when (isFlicked) {
                    FLICK_NONE -> {
                        keyboard = mLatinKeyboard
                        isShifted = keyboard.isShifted
                        isCapsLocked = keyboard.isCapsLocked
                    }
                    FLICK_DOWN -> mService.handleCancel()
                }
            }
            else -> {
                if (primaryCode == ' '.code && mSpaceFlicked) return

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
                if (keyboard === mLatinKeyboard && !isCapsLocked) {
                    isShifted = false
                }
            }
        }
        setKeyState(mService.engineState)
    }

    private fun findKeyByCode(code: Int) =
        keyboard.keys.find { it.codes[0] == code }

    fun setKeyState(state: SKKState): QwertyKeyboardView {
        val kanaKey = findKeyByCode(KEYCODE_QWERTY_TOJP)
        val kanaLabel = if (state.isTransient) "確定" else "かな"
        kanaKey?.label = if (skkPrefs.preferFlick) "Flick" else kanaLabel
        kanaKey?.downLabel = if (skkPrefs.preferFlick) kanaLabel else "Flick"
        kanaKey?.on = state === SKKHiraganaState // Kanji とか Choose とかで消えるのがイヤなら以下にする
        // kanaKey?.on = (state !in listOf(SKKASCIIState, SKKZenkakuState) && mService.isHiragana)

        val qKey = findKeyByCode('q'.code)
        qKey?.on = (state !in listOf(SKKASCIIState, SKKZenkakuState) && !mService.isHiragana)

        val lKey = findKeyByCode('l'.code)
        lKey?.on = (state === SKKASCIIState)

        isZenkaku = (state === SKKZenkakuState)

        invalidateAllKeys()
        return this
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
        private const val FLICK_UP = 1
        private const val FLICK_NONE = 0
        private const val FLICK_DOWN = -1
    }

}