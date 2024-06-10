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
        when {
            pcode == ' '.code -> context.chooseAdjacentCandidate(true)
            pcode == 'x'.code -> context.chooseAdjacentCandidate(false)
            isAlphabet(pcode) -> {
                val composing = context.mComposing
                val pcodeLower = if (Character.isUpperCase(pcode)) {
                    Character.toLowerCase(pcode)
                } else {
                    pcode
                }

                if (composing.length == 1) {
                    val hchr = RomajiConverter.checkSpecialConsonants(composing[0], pcodeLower)
                    if (hchr != null) {
                        mHint.append(hchr)
                        composing.setLength(0)
                        context.narrowCandidates(mHint.toString())
                    }
                }
                composing.append(pcodeLower.toChar())
                val hchr = RomajiConverter.convert(composing.toString())

                if (hchr != null) {
                    mHint.append(hchr)
                    composing.setLength(0)
                    context.narrowCandidates(mHint.toString())
                } else {
                    context.setCurrentCandidateToComposing()
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        if (mHint.isEmpty()) {
            context.conversionStart(context.mKanjiKey)
        } else {
            val composing = context.mComposing
            if (composing.isNotEmpty()) {
                composing.deleteCharAt(composing.length - 1)
                context.setCurrentCandidateToComposing()
            } else {
                mHint.deleteCharAt(mHint.length - 1)
                context.narrowCandidates(mHint.toString())
            }
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.conversionStart(context.mKanjiKey)
        return true
    }
}