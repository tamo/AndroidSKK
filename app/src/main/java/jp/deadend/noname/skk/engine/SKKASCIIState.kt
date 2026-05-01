package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey

// ASCIIモード
object SKKASCIIState : SKKState {
    override val isTransient = false
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState) // Flickにするのは別キーなので内部だけひらがなに
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val (lower, shifted) = decodeKey(keyCode)
        val c = if (shifted) Character.toUpperCase(lower) else lower
        context.commitTextSKK(c.toChar().toString())
        context.updateSuggestionsASCII()
    }

    override fun afterBackspace(context: SKKEngine) {
        context.updateSuggestionsASCII()
    }

    override fun handleCancel(context: SKKEngine) = false

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true) // 元の「ひら/カタ」で FlickJP に
        return true
    }
}
