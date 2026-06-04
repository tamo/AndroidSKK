package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey

object SKKEmojiState : SKKConfirmingState {
    override val isTransient = true
    override val canComplete = false
    override val hasCandidates = true
    override val isJapanese = true
    override val icon = 0
    override var isSequential = false

    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        context.oldState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (super.beforeProcessKey(context, keyCode)) return
        val (lower, shifted) = decodeKey(keyCode)
        val charCode = if (shifted) Character.toUpperCase(lower) else lower
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
        super.afterBackspace(context)
        context.oldState.afterBackspace(context)
        context.changeState(context.oldState)
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        super.handleCancel(context, reconvert)
        val ret = context.oldState.handleCancel(context, reconvert)
        context.changeState(context.oldState)
        return ret
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.oldState)
        return context.oldState.changeToFlick(context)
    }
}
