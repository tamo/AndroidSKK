package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isAlphabet

// 変換候補選択中(▼モード)
object SKKChooseState : SKKState {
    override val isTransient = true
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.pickCurrentCandidate()
        if (context.toggleKanaKey) {
            context.changeState(SKKASCIIState)
        } else {
            context.changeState(SKKHiraganaState)
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        when (pcode) {
            ' '.code -> context.chooseAdjacentCandidate(true)
            'x'.code -> context.chooseAdjacentCandidate(false)
            '>'.code -> {
                // 接尾辞入力
                context.pickCurrentCandidate()
                context.changeState(SKKKanjiState)
                val kanjiKey = context.mKanjiKey
                kanjiKey.append('>')
                context.setComposingTextSKK(kanjiKey, 1)
            }
            'l'.code -> {
                // 暗黙の確定
                context.pickCurrentCandidate()
                context.changeState(SKKASCIIState)
            }
            ':'.code -> context.changeState(SKKNarrowingState)
            else -> {
                // 暗黙の確定
                context.pickCurrentCandidate()
                SKKHiraganaState.processKey(context, pcode)
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        val kanjiKey = context.mKanjiKey
        if (kanjiKey.isEmpty()) {
            context.changeState(SKKHiraganaState)
        } else {
            if (isAlphabet(kanjiKey[0].code)) { // Abbrevモード
                context.changeState(SKKAbbrevState)
            } else { // 漢字変換中
                context.mOkurigana = null
                context.changeState(SKKKanjiState)
                if (isAlphabet(kanjiKey[kanjiKey.length - 1].code)) {
                    kanjiKey.deleteCharAt(kanjiKey.length - 1) // 送りがなのアルファベットを削除
                }
            }
            context.setComposingTextSKK(kanjiKey, 1)
            context.updateSuggestions(kanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        afterBackspace(context)
        return true
    }
}
