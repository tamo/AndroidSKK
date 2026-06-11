package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.engine.SKKEngine.Companion.ASCII_WORD_MAX_LENGTH
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode

object SKKASCIIState : SKKConfirmingState {
    override val isTransient = false
    override val canComplete = true
    override val isJapanese = false
    override val icon = 0

    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        context.changeState(SKKHiraganaState) // Flickにするのは別キーなので内部だけひらがなに
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (beforeProcessKey(context, keyCode)) return
        val lower = keyCode.lowerCode
        val c = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.commitTextSKK(Char(c).toString())
        context.completeASCII()
    }

    override fun afterBackspace(context: SKKEngine) {
        super.afterBackspace(context)
        context.completeASCII()
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        super.handleCancel(context, reconvert)
        if (!reconvert) {
            if (context.mCandidates.mList?.isEmpty() ?: true) return false
            context.reset()
            return true
        }

        val prefix = getPrefix(context)
        val suffix = getSuffix(context)
        return ((prefix.isNotEmpty() || suffix.isNotEmpty()) &&
                context.ic?.deleteSurroundingText(prefix.length, suffix.length) == true)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true) // 元の「ひら/カタ」で FlickJP に
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
