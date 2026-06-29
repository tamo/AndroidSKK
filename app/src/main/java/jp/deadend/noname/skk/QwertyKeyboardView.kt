package jp.deadend.noname.skk

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import jp.deadend.noname.skk.engine.SKKASCIIState
import jp.deadend.noname.skk.engine.SKKHiraganaState
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState

class QwertyKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : KeyboardView(context, attrs, defStyleAttr) {
    val mLatinKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.qwerty, mService.mRootWidth, mService.mScreenHeight)
    }
    val mSymbolsKeyboard: Keyboard by lazy {
        Keyboard(context, R.xml.symbols, mService.mRootWidth, mService.mScreenHeight)
    }

    private var mSpacePressed = false
    private var mSpaceFlicked = false

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
                isFlicked = if (dx2 + dy2 > mFlickSensitivitySquared) when {
                    mSpacePressed -> return true.also {
                        when {
                            dx2 > dy2 && dx2 > mFlickSensitivitySquared ->
                                if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT
                                else KeyEvent.KEYCODE_DPAD_RIGHT

                            dx2 < dy2 && dy2 > mFlickSensitivitySquared ->
                                if (dy < 0) KeyEvent.KEYCODE_DPAD_UP
                                else KeyEvent.KEYCODE_DPAD_DOWN

                            else -> null
                        }?.let { dpad ->
                            mService.keyDownUp(dpad)
                            mSpaceFlicked = true
                            flickStartX = me.x
                            flickStartY = me.y
                        }
                    }

                    dx2 > dy2 && dx2 > mFlickSensitivitySquared ->
                        if (dx < 0) FLICK_LEFT else FLICK_RIGHT

                    dy < 0 && dx2 < dy2 -> FLICK_UP
                    dy > 0 && dx2 < dy2 -> FLICK_DOWN
                    else -> FLICK_NONE
                } else FLICK_NONE // フリックなし (に戻す)

                return true
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
                if (skkPrefs.softKeyboardType != "qwerty") flickNone else FLICK_DOWN -> mService.changeToFlick()
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

                val code = findKeyByCode(primaryCode)?.let { key ->
                    when (flick) {
                        FLICK_DOWN if key.codes.down > 0 -> key.codes.down
                        flickUp if key.codes.shifted > 0 -> key.codes.shifted
                        else -> primaryCode
                    }
                } ?: primaryCode
                val meta = when (isFlicked) {
                    FLICK_LEFT if code > 0 -> CTRL_PRESSED
                    FLICK_RIGHT if code > 0 -> ALT_PRESSED
                    else -> 0
                }
                mService.handleKey(encKey = encodeKey(code, meta))
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
            val flickLabel = "Flick"
            kanaKey.labels.main =
                if (skkPrefs.softKeyboardType != "qwerty") flickLabel else kanaLabel
            kanaKey.labels.down =
                if (skkPrefs.softKeyboardType != "qwerty") kanaLabel else flickLabel
            kanaKey.on = state is SKKHiraganaState // Kanji とか Choose とかで消えるのがイヤなら以下にする
            // kanaKey.on = (state.isJapanese && mService.isHiragana)
        }

        findKeyByCode('q'.code)?.on = (state.isJapanese && !mService.isHiragana)
        findKeyByCode('l'.code)?.on = (state is SKKASCIIState)
        isZenkaku = (state is SKKZenkakuState)

        invalidateAllKeys()
        return this
    }

    override fun onPress(primaryCode: Int) {
        mSpacePressed = (primaryCode == ' '.code)
        mSpaceFlicked = false
        if (mSpacePressed) mService.suspendCompletion()
    }

    companion object {
        private const val KEYCODE_QWERTY_TO_JP = -1008
        private const val KEYCODE_QWERTY_TO_SYM = -1009
        private const val KEYCODE_QWERTY_TO_LATIN = -1010
        private const val KEYCODE_QWERTY_ENTER = -1011
    }

}