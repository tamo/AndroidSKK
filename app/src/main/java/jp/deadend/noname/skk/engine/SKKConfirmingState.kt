package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey

interface SKKConfirmingState : SKKState {
    var pendingLambda: (() -> Unit)?
    var oldComposingText: String

    override fun handleKanaKey(context: SKKEngine) {
        pendingLambda = null
    }

    fun beforeProcessKey(context: SKKEngine, keyCode: Int): Boolean {
        val (lowerCode, _) = decodeKey(keyCode)
        pendingLambda?.let {
            context.setComposingTextSKK(oldComposingText)
            return (if (lowerCode == 'y'.code) {
                it.invoke()
                true
            } else false).also { pendingLambda = null }
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

    fun confirmUnregister(context: SKKEngine, entryString: String, onConfirm: () -> Unit) {
        oldComposingText = context.mComposingText.toString()
        context.setComposingTextSKK("削除? (y/N) $entryString")
        context.mCandidates.setView(listOf("削除?", "× [$entryString]"), "")
        pendingLambda = onConfirm
    }
}
