package jp.deadend.noname.skk.engine

// ASCIIモード
object SKKASCIIState : SKKState {
    override val isTransient = false
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) = context.changeState(SKKHiraganaState)

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.commitTextSKK(pcode.toChar().toString())
    }

    override fun afterBackspace(context: SKKEngine) {}

    override fun handleCancel(context: SKKEngine) = false
}
