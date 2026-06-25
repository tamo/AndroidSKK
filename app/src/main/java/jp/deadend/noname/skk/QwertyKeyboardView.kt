package jp.deadend.noname.skk

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState

class QwertyKeyboardView : KeyboardView, KeyboardView.OnKeyboardActionListener {
    val mLatinKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.qwerty, mService.mRootWidth, mService.mScreenHeight)
    }
    val mSymbolsKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.symbols, mService.mRootWidth, mService.mScreenHeight)
    }

    private var mSpacePressed = false
    private var mSpaceFlicked = false

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

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

    override fun handleBack(): Boolean {
        mService.clearCandidatesView()
        return super.handleBack()
    }

    override fun onLongPress(key: Keyboard.Key): Boolean {
        if (key.codes.main[0] == KEYCODE_QWERTY_ENTER) {
            mService.pressSearch()
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

                        dx2 > dy2 && dx2 > mFlickSensitivitySquared -> {
                            isFlicked = if (dx < 0) FLICK_LEFT else FLICK_RIGHT
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
            // repeatable
            Keyboard.KEYCODE_DELETE -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleBackspace()) mService.pressDel()
            }
            // codes[0] 以外
            Keyboard.KEYCODE_CAPSLOCK -> {
                isShifted = true
                isCapsLocked = true
            }
        }
    }

    override fun onRelease(primaryCode: Int) {
        mSpacePressed = false
        mService.resumeCompletion()

        // シフトで up と none が交換される
        val flickNone = if (isShifted) FLICK_UP else FLICK_NONE
        val flickUp = if (isShifted) FLICK_NONE else FLICK_UP
        // 横フリックは FLICK_NONE とほぼ等価 (上記 flickNone ではない)
        val flick = when (isFlicked) {
            FLICK_LEFT, FLICK_RIGHT -> FLICK_NONE
            else -> isFlicked
        }

        if (!mMiniKeyboardOnScreen) when (primaryCode) {
            // onKey で消費済み
            Keyboard.KEYCODE_DELETE, Keyboard.KEYCODE_CAPSLOCK -> {}

            // repeatable 以外
            Keyboard.KEYCODE_SHIFT -> when (flick) {
                FLICK_NONE -> {
                    isShifted = !isShifted
                    isCapsLocked = false
                }

                else -> {
                    isShifted = true
                    isCapsLocked = true
                }
            }

            KEYCODE_QWERTY_ENTER -> {
                if (!isCapsLocked) isShifted = false
                if (!mService.handleEnter()) mService.pressEnter()
            }

            KEYCODE_QWERTY_TO_JP -> when (flick) {
                flickUp -> mService.pasteClip()
                if (skkPrefs.preferFlick) flickNone else FLICK_DOWN -> mService.changeToFlick()
                else -> mService.handleKanaKey()
            }

            KEYCODE_QWERTY_TO_SYM -> {
                if (!isCapsLocked) isShifted = false
                when (flick) {
                    flickNone -> {
                        keyboard = mSymbolsKeyboard
                        isShifted = keyboard.isShifted
                        isCapsLocked = keyboard.isCapsLocked
                        // 記号は capslock にならない気がするが一応
                    }

                    flickUp -> mService.handleCancel()
                    FLICK_DOWN -> mService.googleTransliterate()
                }
            }

            KEYCODE_QWERTY_TO_LATIN -> when (flick) {
                FLICK_NONE, FLICK_UP -> {
                    keyboard = mLatinKeyboard

                    // 単純 shift を capslock として扱うので状態を残す
                    isShifted = keyboard.isShifted
                    isCapsLocked = keyboard.isCapsLocked
                    // latin に capslock が残っている場合がある
                }

                FLICK_DOWN -> {
                    mService.handleCancel()
                    if (!isCapsLocked) isShifted = false
                }
            }

            else -> {
                if (primaryCode == ' '.code && mSpaceFlicked) {
                    mService.completeASCII()
                    return
                }

                val primaryKey = findKeyByCode(primaryCode)
                val code = if (primaryKey == null) primaryCode else when (flick) {
                    FLICK_DOWN ->
                        if (primaryKey.codes.down > 0) primaryKey.codes.down else primaryCode

                    flickUp ->
                        if (primaryKey.codes.shifted > 0) primaryKey.codes.shifted else primaryCode

                    else -> primaryCode
                }
                val meta = when (isFlicked) {
                    FLICK_LEFT if code > 0 -> CTRL_PRESSED
                    FLICK_RIGHT if code > 0 -> ALT_PRESSED
                    else -> 0
                }
                mService.processKey(encodeKey(code, meta))
            }
        }
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT, KEYCODE_QWERTY_TO_SYM, KEYCODE_QWERTY_TO_LATIN -> {}
            else -> if (keyboard === mLatinKeyboard && !isCapsLocked) isShifted = false
            // 記号モードでは普通の shift も capslock として扱う (対応する { と } 等に便利だろうから)
        }
        setKeyState(mService.engineState)
    }

    private fun findKeyByCode(code: Int) =
        keyboard.keys.find { it.codes.main[0] == code }

    override fun setKeyState(state: SKKState): QwertyKeyboardView {
        findKeyByCode(KEYCODE_QWERTY_TO_JP)?.let { kanaKey ->
            val kanaLabel = if (state.isTransient) "確定" else "かな"
            val flickLabel = if (skkPrefs.preferGodan) "Godan" else "Flick"
            kanaKey.labels.main = if (skkPrefs.preferFlick) flickLabel else kanaLabel
            kanaKey.labels.down = if (skkPrefs.preferFlick) kanaLabel else flickLabel
            kanaKey.on = state is SKKHiraganaState // Kanji とか Choose とかで消えるのがイヤなら以下にする
            // kanaKey.on = (state.isJapanese && mService.isHiragana)
        }

        val qKey = findKeyByCode('q'.code)
        qKey?.on = (state.isJapanese && !mService.isHiragana)

        val lKey = findKeyByCode('l'.code)
        lKey?.on = (state is SKKASCIIState)

        isZenkaku = (state is SKKZenkakuState)

        invalidateAllKeys()
        return this
    }

    override fun onPress(primaryCode: Int) {
        mSpacePressed = (primaryCode == ' '.code)
        mSpaceFlicked = false
        if (mSpacePressed) {
            mService.suspendCompletion()
        }
    }

    override fun onText(text: CharSequence) {}

    override fun swipeRight() {}

    override fun swipeLeft() {}

    override fun swipeDown() {}

    override fun swipeUp() {}

    companion object {
        private const val KEYCODE_QWERTY_TO_JP = -1008
        private const val KEYCODE_QWERTY_TO_SYM = -1009
        private const val KEYCODE_QWERTY_TO_LATIN = -1010
        private const val KEYCODE_QWERTY_ENTER = -1011
    }

}