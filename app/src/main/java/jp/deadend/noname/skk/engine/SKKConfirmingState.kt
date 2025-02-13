package jp.deadend.noname.skk.engine

interface SKKConfirmingState : SKKState {
    var pendingLambda: (() -> Unit)?
    var oldComposingText: String

    override fun handleKanaKey(context: SKKEngine) {
        pendingLambda = null
    }

    fun beforeProcessKey(context: SKKEngine, pcode: Int): Boolean {
        pendingLambda?.let {
            context.setComposingTextSKK(oldComposingText)
            when (pcode) {
                'y'.code, 'Y'.code -> {
                    pendingLambda!!.invoke()
                    return true
                }
                else -> pendingLambda = null
            }
        }
        return false
    }

    override fun afterBackspace(context: SKKEngine) {
        SKKChooseState.pendingLambda = null
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        SKKChooseState.pendingLambda = null
        return false
    }
}