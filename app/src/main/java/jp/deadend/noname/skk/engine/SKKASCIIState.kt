package jp.deadend.noname.skk.engine

// ASCIIモード
object SKKASCIIState : SKKState {
    override val isTransient = false
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState, false) // Flickにするのは別キーなので内部だけひらがなに
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.commitTextSKK(pcode.toChar().toString(), 1)
    }

    override fun afterBackspace(context: SKKEngine) {}

    override fun handleCancel(context: SKKEngine) = false

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, change = true) // 元の「ひら/カタ」で FlickJP に
        return true
    }
}
