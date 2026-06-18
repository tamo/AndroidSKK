package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.lowerCode

interface SKKConfirmingState : SKKState {
    var pendingLambda: (() -> Unit)?
    var oldComposingText: String

    override fun handleKanaKey(context: SKKEngine) {
        pendingLambda = null
    }

    fun beforeProcessKey(context: SKKEngine, keyCode: Int): Boolean {
        val lowerCode = keyCode.lowerCode
        return checkPending(context, lowerCode)
    }

    fun handleEnterConfirming(context: SKKEngine): Boolean {
        return checkPending(context, 'y'.code)
    }

    private fun checkPending(context: SKKEngine, lowerCode: Int): Boolean {
        pendingLambda?.let {
            SKKLog.d("checkPending: '${Char(lowerCode)}' (oldComposingText=$oldComposingText)")
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
