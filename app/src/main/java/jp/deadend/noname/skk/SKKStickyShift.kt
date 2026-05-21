package jp.deadend.noname.skk

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent

internal class SKKStickyShift(private val mIs: InputMethodService) {
    private enum class State { STATE_NONE, STATE_ON, STATE_LOCKED }

    private var mState = State.STATE_NONE
    private var isPressed = false
    private var isUsed = false

    init {
        clearState()
    }

    fun clearState() {
        val ic = mIs.currentInputConnection
        ic?.clearMetaKeyStates(MASK_SHIFT_STATES)

        mState = State.STATE_NONE
        isPressed = false
        isUsed = false
    }

    fun press() {
        // send a key press event also to an editor
        mIs.currentInputConnection
            ?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))

        isPressed = true
        isUsed = false
        mState = when (mState) {
            State.STATE_NONE -> State.STATE_ON // off -> on
            State.STATE_ON -> State.STATE_LOCKED // on -> locked
            State.STATE_LOCKED -> State.STATE_NONE // locked -> off
        }
    }

    fun release() {
        // send a key release event also to an editor
        val ic = mIs.currentInputConnection
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))

        isPressed = false
        if (mState == State.STATE_ON && isUsed) {
            mState = State.STATE_NONE
            ic?.clearMetaKeyStates(MASK_SHIFT_STATES)
            // on -> off
        }
    }

    fun useState(): Int = when (mState) {
        State.STATE_LOCKED -> KeyEvent.META_SHIFT_ON

        State.STATE_ON -> {
            if (isPressed) {
                isUsed = true // 押して離す前に使ってしまったので、離した時点でオフにする
            } else {
                mState = State.STATE_NONE // 普通に使ったので、この時点でオフにする
                mIs.currentInputConnection?.clearMetaKeyStates(MASK_SHIFT_STATES)
            }
            KeyEvent.META_SHIFT_ON
        }

        else -> 0
    }

    companion object {
        private const val MASK_SHIFT_STATES =
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
    }
}