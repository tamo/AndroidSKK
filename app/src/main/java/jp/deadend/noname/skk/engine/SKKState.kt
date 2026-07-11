package jp.deadend.noname.skk.engine

@Suppress("SameReturnValue")
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
    val setComposingText: ((SKKEngine, StringBuilder) -> Unit)? get() = null

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

    fun handleKanaKey(context: SKKEngine)

    fun handleKatakanaKey(context: SKKEngine): Boolean =
        context.changeState(
            if (context.kanaState is SKKHiraganaState) SKKKatakanaState
            else SKKHiraganaState
        ).let { true }

    fun handleASCIIKey(context: SKKEngine): Boolean =
        context.changeState(SKKASCIIState, true).let { true }

    fun handleZenkakuKey(context: SKKEngine): Boolean =
        context.changeState(SKKZenkakuState).let { true }

    fun handleAbbrevKey(context: SKKEngine): Boolean =
        if (context.mComposing.isEmpty()) context.changeState(SKKAbbrevState).let { true }
        else false

    fun handleHankakuKanaKey(context: SKKEngine): Boolean =
        context.changeState(SKKHanKanaState).let { true }

    fun handleEnter(context: SKKEngine): Boolean = false
    fun handleBackspace(context: SKKEngine): Boolean = context.handleDelete()
    fun handleForwardDel(context: SKKEngine): Boolean = context.handleDelete(true)
    fun processKey(context: SKKEngine, keyCode: Int)
    fun handleDpad(context: SKKEngine, keyCode: Int): Boolean = context.run {
        when {
            state.isTransient -> handleDpadTransient(keyCode, mKanjiKey)
            mRegister.isOngoing -> mRegister.handleDpad(keyCode)
            else -> return false
        }
        return true
    }

    fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean

    fun pickCandidatesViewManually(
        context: SKKEngine, index: Int, unregister: Boolean = false, sequential: Boolean = false
    ) {
        when {
            this is SKKConfirmingState && pendingLambda != null -> acceptUnregister(context)

            hasCandidates -> context.mCandidates.apply {
                isSequential = sequential
                pickCandidate(index, unregister = unregister)
            }

            canComplete -> context.mCandidates.apply {
                isSequential = sequential
                pickCompletion(index, unregister)
            }

            else -> throw RuntimeException("cannot pick candidate in ${this.name}")
        }
    }

    fun prepareToMushroom(context: SKKEngine, clip: String): String =
        (if (canComplete) context.mKanjiKey.toString() else clip).also {
            if (isTransient)
                context.changeState(context.kanaState)
            else context.run {
                reset()
                mRegister.mStack.clear()
            }
        }

    fun changeToFlick(context: SKKEngine): Boolean // ここで FlickJP に変更されたら true
    fun transformLastChar(context: SKKEngine, type: String): Boolean = false
}
