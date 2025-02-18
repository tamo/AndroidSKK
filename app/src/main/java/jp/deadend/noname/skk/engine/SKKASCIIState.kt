package jp.deadend.noname.skk.engine

// ASCIIモード
object SKKASCIIState : SKKState {
    override val isTransient = false
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState) // Flickにするのは別キーなので内部だけひらがなに
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        context.commitTextSKK(keyCode.toChar().toString())
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
