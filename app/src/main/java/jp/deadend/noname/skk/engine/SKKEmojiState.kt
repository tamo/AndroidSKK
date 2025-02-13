package jp.deadend.noname.skk.engine

object SKKEmojiState : SKKConfirmingState {
    var isSequential = false
    override val isTransient = false
    override val icon = 0
    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        context.oldState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        if (super.beforeProcessKey(context, pcode)) return
        when (pcode) {
            ' '.code -> context.chooseAdjacentSuggestion(true)
            'x'.code -> context.chooseAdjacentSuggestion(false)
            'X'.code -> context.pickCurrentCandidate(unregister = true)
            ':'.code -> {
                context.changeState(SKKNarrowingState)
                SKKNarrowingState.isSequential = isSequential
            }
            else -> {
                context.oldState.processKey(context, pcode)
                if (context.state === SKKEmojiState) {
                    context.changeState(context.oldState)
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        super.afterBackspace(context)
        context.oldState.afterBackspace(context)
        context.changeState(context.oldState)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        super.handleCancel(context)
        val ret = context.oldState.handleCancel(context)
        context.changeState(context.oldState)
        return ret
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.oldState)
        return context.oldState.changeToFlick(context)
    }
}
