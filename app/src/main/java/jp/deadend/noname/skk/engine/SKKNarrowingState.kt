package jp.deadend.noname.skk.engine

object SKKNarrowingState : SKKState {
    var isSequential = false
    override val isTransient = true
    override val icon = 0

    internal val mHint = StringBuilder()
    internal var mOriginalCandidates: List<String>? = null
    internal var mSpaceUsed = false // xを前候補にするためのフラグ

    override fun handleKanaKey(context: SKKEngine) {
        SKKChooseState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.apply {
            when (pcode) {
                ' '.code -> {
                    mSpaceUsed = true
                    chooseAdjacentCandidate(true)
                }

                'l'.code, 'L'.code, '/'.code -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(pcode)
                }

                else -> if (mSpaceUsed && pcode == 'x'.code) {
                    chooseAdjacentCandidate(false)
                } else {
                    SKKHiraganaState
                        .processKana(this, Character.toLowerCase(pcode)) { _, hchr ->
                            mHint.append(hchr)
                            mComposing.setLength(0)
                            narrowCandidates(mHint.toString())
                        }
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            if (mHint.isEmpty()) {
                conversionStart(context.mKanjiKey)
            } else {
                if (mComposing.isNotEmpty()) {
                    mComposing.deleteCharAt(mComposing.lastIndex)
                    setCurrentCandidateToComposing()
                } else {
                    mHint.deleteCharAt(mHint.lastIndex)
                    narrowCandidates(mHint.toString())
                }
            }
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.conversionStart(context.mKanjiKey)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}