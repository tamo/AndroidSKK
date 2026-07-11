package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.engine.SKKEngine.Companion.ASCII_WORD_MAX_LENGTH
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode

object SKKASCIIState : SKKConfirmingState() {
    override val canComplete = true
    override val isJapanese = false
    override val icon = -1 // 非表示

    // Flickにするのは別キーなので内部だけひらがなに
    override fun handleKanaKey(context: SKKEngine) {
        if (!declineUnregister(context)) context.changeState(SKKHiraganaState)
    }

    override fun handleEnter(context: SKKEngine): Boolean = context.run {
        when {
            declineUnregister(context) -> true

            mRegister.isOngoing -> {
                mRegister.finish()
                true
            }

            else -> false
        }
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (beforeProcessKey(context, keyCode)) return
        val lower = keyCode.lowerCode
        val c = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.commitTextSKK(Char(c).toString())
        context.completeASCII()
    }

    override fun handleBackspace(context: SKKEngine): Boolean {
        if (declineUnregister(context)) return true
        if (!context.handleDelete()) return false
        context.completeASCII()
        return true
    }

    override fun handleForwardDel(context: SKKEngine): Boolean {
        if (declineUnregister(context)) return true
        if (!context.handleDelete(isForward = true)) return false
        context.completeASCII()
        return true
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean = when {
        declineUnregister(context) -> true
        context.mRegister.isOngoing -> context.mRegister.cancel().let { true }
        !reconvert && context.mCandidates.mList.isNullOrEmpty() -> false
        !reconvert -> context.reset().let { true }
        else -> {
            val prefix = getPrefix(context)
            val suffix = getSuffix(context)
            (prefix.isNotEmpty() || suffix.isNotEmpty()) &&
                    context.ic?.deleteSurroundingText(prefix.length, suffix.length) == true
        }
    }

    override fun prepareToMushroom(context: SKKEngine, clip: String): String =
        getPrefix(context).ifEmpty { clip }.also {
            if (!declineUnregister(context)) {
                context.reset()
                context.mRegister.mStack.clear()
            }
        }

    // 元の「ひら/カタ」で FlickJP に
    override fun changeToFlick(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.changeState(context.kanaState, true)
        return true
    }

    private val delimiter = Regex("[^\\p{L}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]")

    internal fun getPrefix(context: SKKEngine): String {
        val ic = context.ic ?: return ""
        val tbc = ic.getTextBeforeCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return ""
        return tbc.split(delimiter).last()
    }

    internal fun getSuffix(context: SKKEngine): String {
        val ic = context.ic ?: return ""
        val tbc = ic.getTextAfterCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return ""
        return tbc.split(delimiter, 2).first()
    }

    internal fun deleteSurroundingText(context: SKKEngine, text: String): Boolean {
        val ic = context.ic ?: return false
        var processed = false
        ic.beginBatchEdit()

        val tbc = ic.getTextBeforeCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return false
            .also { ic.endBatchEdit() }
        val wbc = tbc.dropLast(text.length).split(delimiter).last()
        if (wbc.isNotEmpty() &&
            ic.deleteSurroundingText(wbc.length + text.length, 0) &&
            ic.commitText(text, 1)
        ) processed = true

        val wac = getSuffix(context)
        if (wac.isNotEmpty() && ic.deleteSurroundingText(0, wac.length))
            processed = true

        ic.endBatchEdit()

        SKKLog.d("deleteSurroundingText [${tbc.dropLast(text.length + wbc.length)}[$wbc[$text]$wac]] processed=$processed")
        return processed
    }
}
