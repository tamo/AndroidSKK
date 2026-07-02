package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.lowerCode

abstract class SKKConfirmingState : SKKState {
    var pendingLambda: (() -> Unit)? = null
    var oldList: List<String>? = null
    var oldIndex: Int = 0

    override fun handleKanaKey(context: SKKEngine) {
        declineUnregister(context)
    }

    override fun handleEnter(context: SKKEngine) =
        declineUnregister(context)

    fun beforeProcessKey(context: SKKEngine, keyCode: Int) =
        answerUnregister(context, keyCode.lowerCode)

    fun declineUnregister(context: SKKEngine) =
        answerUnregister(context, 'n'.code)

    private fun answerUnregister(context: SKKEngine, lowerCode: Int): Boolean {
        context.mCandidates.resumeCompletion()
        pendingLambda?.let {
            SKKLog.d("checkPending: '${Char(lowerCode)}'")
            pendingLambda = null
            if (lowerCode == 'y'.code) {
                it.invoke()
                return true
            }
            context.mCandidates.apply {
                setView(oldList, context.mKanjiKey.toString(), oldIndex)
                if (context.state.hasCandidates) appendTask { updateComposingText() }
                else context.setComposingTextSKK("")
            }
            return true
        }
        return false
    }

    override fun afterBackspace(context: SKKEngine, isComposingDeleted: Boolean) {
        declineUnregister(context)
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean) =
        declineUnregister(context)

    override fun changeToFlick(context: SKKEngine) =
        declineUnregister(context)

    override fun transformLastChar(context: SKKEngine, type: String) =
        declineUnregister(context)

    fun confirmUnregister(context: SKKEngine, entryString: String, onConfirm: () -> Unit) {
        oldList = context.mCandidates.mList
        oldIndex = context.mCandidates.mIndex
        context.setComposingTextSKK("削除? (y/N) $entryString")
        context.mCandidates.suspendCompletion()
        context.mCandidates.setView(listOf("削除?", "× [$entryString]"), "", 0)
        pendingLambda = onConfirm
    }
}
