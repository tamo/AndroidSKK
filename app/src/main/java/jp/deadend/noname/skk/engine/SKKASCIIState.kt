package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey

// ASCIIモード
object SKKASCIIState : SKKConfirmingState {
    override val isTransient = false
    override val icon = 0
    override val isJapanese = false
    override val canSuggest = true
    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        context.changeState(SKKHiraganaState) // Flickにするのは別キーなので内部だけひらがなに
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (beforeProcessKey(context, keyCode)) return
        val (lower, shifted) = decodeKey(keyCode)
        val c = if (shifted) Character.toUpperCase(lower) else lower
        context.commitTextSKK(Char(c).toString())
        context.updateSuggestionsASCII()
    }

    override fun afterBackspace(context: SKKEngine) {
        super.afterBackspace(context)
        context.updateSuggestionsASCII()
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        super.handleCancel(context)
        return false
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true) // 元の「ひら/カタ」で FlickJP に
        return true
    }
}
