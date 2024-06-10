package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.createTrimmedBuilder
import jp.deadend.noname.skk.hirakana2katakana
import jp.deadend.noname.skk.isVowel

// 漢字変換のためのひらがな入力中(▽モード)
object SKKKanjiState : SKKState {
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

        if (composing.length == 1) {
            val hchr = RomajiConverter.checkSpecialConsonants(composing[0], pcodeLower)
            if (hchr != null) {
                kanjiKey.append(hchr)
                context.setComposingTextSKK(kanjiKey, 1)
                composing.setLength(0)
            }
        }
        if (pcodeLower == 'q'.code) {
            // カタカナ変換
            if (kanjiKey.isNotEmpty()) {
                val str = hirakana2katakana(kanjiKey.toString())
                if (str != null) context.commitTextSKK(str, 1)
            }
            context.changeState(SKKHiraganaState)
        } else if (pcodeLower == ' '.code || pcodeLower == '>'.code) {
            // 変換開始
            // 最後に単体の'n'で終わっている場合、'ん'に変換
            if (composing.length == 1 && composing[0] == 'n') {
                kanjiKey.append('ん')
                context.setComposingTextSKK(kanjiKey, 1)
            }
            if (pcodeLower == '>'.code) kanjiKey.append('>') // 接頭辞入力
            composing.setLength(0)
            context.conversionStart(kanjiKey)
        } else if (isUpper && kanjiKey.isNotEmpty()) {
            // 送り仮名開始
            // 最初の平仮名はついシフトキーを押しっぱなしにしてしまうた
            // め、kanjiKeyの長さをチェックkanjiKeyの長さが0の時はシフトが
            // 押されていなかったことにして下方へ継続させる
            kanjiKey.append(pcodeLower.toChar()) //送りありの場合子音文字追加
            composing.setLength(0)
            if (isVowel(pcodeLower)) { // 母音なら送り仮名決定，変換
                context.mOkurigana = RomajiConverter.convert(pcodeLower.toChar().toString())
                context.conversionStart(kanjiKey)
            } else { // それ以外は送り仮名モード
                composing.append(pcodeLower.toChar())
                context.setComposingTextSKK(
                        createTrimmedBuilder(kanjiKey).append('*').append(pcodeLower.toChar()), 1
                )
                context.changeState(SKKOkuriganaState)
            }
        } else {
            // 未確定
            composing.append(pcodeLower.toChar())
            // 全角にする記号ならば全角，そうでなければローマ字変換
            val hchr = context.getZenkakuSeparator(composing.toString())
                    ?: RomajiConverter.convert(composing.toString())

            if (hchr != null) {
                composing.setLength(0)
                kanjiKey.append(hchr)
                context.setComposingTextSKK(kanjiKey, 1)
            } else {
                context.setComposingTextSKK(kanjiKey.toString() + composing.toString(), 1)
            }
            context.updateSuggestions(kanjiKey.toString())
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        val kanjiKey = context.mKanjiKey.toString()
        val composing = context.mComposing.toString()

        context.setComposingTextSKK(kanjiKey + composing, 1)
        context.updateSuggestions(kanjiKey)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.changeState(SKKHiraganaState)
        return true
    }
}
