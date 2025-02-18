package jp.deadend.noname.skk.engine

interface SKKConfirmingState : SKKState {
    var pendingLambda: (() -> Unit)?
    var oldComposingText: String

    override fun handleKanaKey(context: SKKEngine) {
        pendingLambda = null
    }

    fun beforeProcessKey(context: SKKEngine, keyCode: Int): Boolean {
        pendingLambda?.let {
            context.setComposingTextSKK(oldComposingText)
            when (keyCode) {
                'y'.code, 'Y'.code -> {
                    pendingLambda!!.invoke()
                    pendingLambda = null
                    return true
                }

                else -> pendingLambda = null
            }
        }
        return false
    }

    override fun afterBackspace(context: SKKEngine) {
        pendingLambda = null
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        pendingLambda = null
        return false
    }
}