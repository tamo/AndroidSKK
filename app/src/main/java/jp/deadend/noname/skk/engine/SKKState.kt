package jp.deadend.noname.skk.engine

@Suppress("SameReturnValue")
interface SKKState {
    val isTransient: Boolean
    val icon: Int
    val isJapanese: Boolean get() = true
    val canSuggest: Boolean get() = false
    val hasCandidates: Boolean get() = false
    val prefix: String? get() = null
    fun handleKanaKey(context: SKKEngine)
    fun processKey(context: SKKEngine, keyCode: Int)
    fun afterBackspace(context: SKKEngine)
    fun handleCancel(context: SKKEngine): Boolean
    fun changeToFlick(context: SKKEngine): Boolean // ここで FlickJP に変更されたら true
}
