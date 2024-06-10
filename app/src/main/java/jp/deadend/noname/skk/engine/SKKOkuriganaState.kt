package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.createTrimmedBuilder

// 送り仮名入力中(▽モード，*つき)
object SKKOkuriganaState : SKKState {
    override val isTransient = true
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {}

    override fun processKey(context: SKKEngine, pcode: Int) {
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(pcode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val pcodeLower = if (isUpper) Character.toLowerCase(pcode) else pcode

        val composing = context.mComposing
        val kanjiKey = context.mKanjiKey
        val okurigana = context.mOkurigana

        if (composing.length == 1 || okurigana == null) {
            // 「ん」か「っ」を処理したらここで終わり
            val hchr = RomajiConverter.checkSpecialConsonants(composing[0], pcodeLower)
            if (hchr != null) {
                context.mOkurigana = hchr
                context.setComposingTextSKK(
                        createTrimmedBuilder(kanjiKey)
                                .append('*').append(hchr).append(pcodeLower.toChar())
                        , 1
                )
                composing.setLength(0)
                composing.append(pcodeLower.toChar())
                return
            }
        }
        // 送りがなが確定すれば変換，そうでなければComposingに積む
        composing.append(pcodeLower.toChar())
        val hchr = RomajiConverter.convert(composing.toString())
        if (okurigana != null) { //「ん」か「っ」がある場合
            if (hchr != null) {
                composing.setLength(0)
                context.mOkurigana = okurigana + hchr
                context.conversionStart(kanjiKey)
            } else {
                context.setComposingTextSKK(
                        createTrimmedBuilder(kanjiKey)
                                .append('*').append(okurigana).append(composing)
                        , 1
                )
            }
        } else {
            if (hchr != null) {
                composing.setLength(0)
                context.mOkurigana = hchr
                context.conversionStart(kanjiKey)
            } else {
                context.setComposingTextSKK(
                        createTrimmedBuilder(kanjiKey).append('*').append(composing), 1
                )
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.mComposing.setLength(0)
        context.mOkurigana = null
        context.setComposingTextSKK(context.mKanjiKey, 1)
        context.changeState(SKKKanjiState)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        val kanjiKey = context.mKanjiKey
        context.mComposing.setLength(0)
        context.mOkurigana = null
        kanjiKey.deleteCharAt(kanjiKey.length - 1)
        context.changeState(SKKKanjiState)
        context.setComposingTextSKK(kanjiKey, 1)

        return true
    }
}
