package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode

object SKKEmojiState : SKKConfirmingState() {
    override val isTransient = true
    override val canComplete = false
    override val hasCandidates = true
    override val isJapanese = true
    override val icon = 0
    override var isSequential = false

    override fun handleKanaKey(context: SKKEngine) {
        if (declineUnregister(context)) return
        super.handleKanaKey(context)
        context.oldState.handleKanaKey(context)
    }

    override fun handleEnter(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.pickCurrentCandidate()
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (super.beforeProcessKey(context, keyCode)) return
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        when (charCode) {
            ' '.code -> context.moveCandidateCursor(true)
            'x'.code -> context.moveCandidateCursor(false)
            'X'.code -> context.pickCurrentCandidate(unregister = true)
            ':'.code -> {
                context.changeState(SKKNarrowingState)
                SKKNarrowingState.isSequential = isSequential
            }

            else -> {
                context.pickCurrentCandidate()
                if (context.state !is SKKEmojiState) {
                    context.processKey(keyCode)
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        if (declineUnregister(context)) return
        context.oldState.afterBackspace(context)
        context.changeState(context.oldState)
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        if (declineUnregister(context)) return true
        val ret = context.oldState.handleCancel(context, reconvert)
        context.changeState(context.oldState)
        return ret
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        if (declineUnregister(context)) return true
        context.changeState(context.oldState)
        return context.oldState.changeToFlick(context)
    }
}
