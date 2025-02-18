package jp.deadend.noname.skk.engine

interface SKKState {
    val isTransient: Boolean
    val icon: Int
    fun handleKanaKey(context: SKKEngine)
    fun processKey(context: SKKEngine, keyCode: Int)
    fun afterBackspace(context: SKKEngine)
    fun handleCancel(context: SKKEngine): Boolean
    fun changeToFlick(context: SKKEngine): Boolean // ここで FlickJP に変更されたら true
}
