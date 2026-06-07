package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.dLog
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
            dLog("SKKConfirmingState.pendingLambda: $lowerCode (oldComposingText=$oldComposingText)")
            pendingLambda = null
            if (lowerCode == 'y'.code) {
                it.invoke()
            }
            context.mCandidates.setView(null, "", 0)
            context.mCandidates.resumeCompletion()
            context.setComposingTextSKK(oldComposingText)
            return true
        }
        return false
    }

    override fun afterBackspace(context: SKKEngine) {
        pendingLambda = null
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        pendingLambda = null
        return false
    }

    fun confirmUnregister(context: SKKEngine, entryString: String, onConfirm: () -> Unit) {
        oldComposingText = context.mComposingText.toString()
        context.setComposingTextSKK("削除? (y/N) $entryString")
        context.mCandidates.suspendCompletion()
        context.mCandidates.setView(listOf("削除?", "× [$entryString]"), "", 0)
        pendingLambda = onConfirm
    }
}
