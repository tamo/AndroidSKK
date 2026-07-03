package jp.deadend.noname.skk.engine

@Suppress("SameReturnValue", "EmptyMethod")
interface SKKState {
    val name: String get() = javaClass.simpleName
    val isTransient: Boolean get() = false // ▽ や ▼ (直接入力ではないのでカーソル管理が必要)
    val canComplete: Boolean get() = false // ASCII と ▽
    val hasCandidates: Boolean get() = false // ▼
    val prefix: String? get() = null // composing 表示に ▽ や ▼ を入れるとき
    val isJapanese: Boolean get() = true // 表示キーボードの判別用
    val isPreedit: Boolean get() = false // reset() しないで遷移する state
    val isTemporaryQwerty: Boolean get() = false // メインじゃないキーボードを使用
    val icon: Int get() = 0 // 0 は変更せず、-1 は消す

    fun onEnter(context: SKKEngine, oldState: SKKState) {
        if (icon != 0) context.updateStatusIcon(icon)
        if (!isTransient && isJapanese) context.updateKanaState(this)
    }

    fun onExit(context: SKKEngine, newState: SKKState) {
        if (canComplete) context.mCandidates.run {
            suspendCompletion()
            resumeCompletion()
        } // 遅れた補完が悪さをしないように

        if (!newState.isTransient) when {
            isTransient && !context.mCandidates.isSpecial ->
                context.commitComposing() // ▽▼終了時に暗黙の確定 (ただし絵文字や記号は除外)
            else -> context.reset()
        }
    }

    fun setComposingText(context: SKKEngine, ct: StringBuilder) {}
    fun handleKanaKey(context: SKKEngine)
    fun handleEnter(context: SKKEngine): Boolean = false
    fun processKey(context: SKKEngine, keyCode: Int)
    fun afterBackspace(context: SKKEngine, isComposingDeleted: Boolean = false)
    fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean
    fun changeToFlick(context: SKKEngine): Boolean // ここで FlickJP に変更されたら true
    fun transformLastChar(context: SKKEngine, type: String): Boolean = false
}
