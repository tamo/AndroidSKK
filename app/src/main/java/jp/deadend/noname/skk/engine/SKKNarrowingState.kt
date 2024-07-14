package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isAlphabet

object SKKNarrowingState : SKKState {
    override val isTransient = true
    override val icon = 0

    internal val mHint = StringBuilder()
    internal var mOriginalCandidates: List<String>? = null

    override fun handleKanaKey(context: SKKEngine) {
        SKKChooseState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            when {
                pcode == ' '.code -> chooseAdjacentCandidate(true)
                pcode == 'x'.code -> chooseAdjacentCandidate(false)
                pcode == 'l'.code || pcode == 'L'.code || pcode == '/'.code -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(pcode)
                }

                isAlphabet(pcode) -> {
                    val pcodeLower = if (Character.isUpperCase(pcode)) {
                        Character.toLowerCase(pcode)
                    } else {
                        pcode
                    }

                    if (mComposing.length == 1) {
                        val hchr = RomajiConverter.checkSpecialConsonants(mComposing[0], pcodeLower)
                        if (hchr != null) {
                            mHint.append(hchr)
                            mComposing.setLength(0)
                            narrowCandidates(mHint.toString())
                        }
                    }
                    mComposing.append(pcodeLower.toChar())
                    val hchr = RomajiConverter.convert(mComposing.toString())

                    if (hchr != null) {
                        mHint.append(hchr)
                        mComposing.setLength(0)
                        narrowCandidates(mHint.toString())
                    } else {
                        if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                            mComposing.setLength(0) // これまでの composing は typo とみなす
                            if (canRetry) return processKey(context, pcode) // 「ca」などもあるので再突入
                        }
                        setCurrentCandidateToComposing()
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