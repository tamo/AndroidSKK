package jp.deadend.noname.skk.engine

object SKKEmojiState : SKKState {
    override val isTransient = false
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.oldState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        when (pcode) {
            ' '.code -> context.chooseAdjacentSuggestion(true)
            'x'.code -> context.chooseAdjacentSuggestion(false)
            else -> {
                context.oldState.processKey(context, pcode)
                if (context.state === SKKEmojiState) {
                    context.changeState(context.oldState)
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.oldState.afterBackspace(context)
        context.changeState(context.oldState)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        val ret = context.oldState.handleCancel(context)
        context.changeState(context.oldState)
        return ret
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.oldState)
        return context.oldState.changeToFlick(context)
    }
}
