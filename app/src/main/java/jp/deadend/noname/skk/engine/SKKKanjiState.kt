package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.createTrimmedBuilder
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isVowel
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku

// 漢字変換のためのひらがな入力中(▽モード)
object SKKKanjiState : SKKState {
    override val isTransient = true
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.apply {
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState, true)
            } else {
                changeState(SKKHiraganaState)
            }
        }
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(keyCode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val codeLower = if (isUpper) Character.toLowerCase(keyCode) else keyCode

        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            if (mComposing.length == 1) {
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)
                if (hiraganaChar != null) {
                    mKanjiKey.append(hiraganaChar)
                    setComposingTextSKK(mKanjiKey)
                    mComposing.setLength(0)
                }
            }

            when (codeLower) {
                'l'.code -> changeInputMode(keyCode)

                '/'.code -> changeInputMode(keyCode)
                // abbrevはtransientなのでchangeInputModeで自動確定されない
                // ▽の状態で英数(abbrev)と仮名(kanji)を行き来するには kanaKey(-1010) と / を使うことにする
                // 一般的なキーコードが分かれば対応するが、emacsではabbrevから普通の▽(kanji)に行けないと思う

                'q'.code -> {
                    // カタカナ変換
                    if (mKanjiKey.isNotEmpty()) {
                        val str = if (kanaState == SKKHiraganaState) {
                            hiragana2katakana(mKanjiKey.toString())
                        } else {
                            mKanjiKey.toString() // すでにひらがななのでそのまま
                        }
                        if (str != null) commitTextSKK(str)
                        mKanjiKey.setLength(0)
                    }
                    changeState(kanaState)
                }

                17 /* Ctrl-Q */ -> {
                    if (mKanjiKey.isNotEmpty()) {
                        val zenkakuKatakana = hiragana2katakana(mKanjiKey.toString())
                        val str = if (kanaState === SKKHanKanaState) {
                            zenkakuKatakana // 半角カナで半角を出すのはエンターだから Ctrl-Q は全角カナが自然だと思う
                        } else {
                            zenkaku2hankaku(zenkakuKatakana)
                        }
                        if (str != null) commitTextSKK(str)
                        mKanjiKey.setLength(0)
                    }
                }

                ' '.code, '>'.code -> {
                    // 変換開始
                    // 最後に単体の'n'で終わっている場合、'ん'に変換
                    if (mComposing.length == 1 && mComposing[0] == 'n') {
                        mKanjiKey.append('ん')
                        setComposingTextSKK(mKanjiKey)
                    }
                    if (codeLower == '>'.code) mKanjiKey.append('>') // 接頭辞入力
                    mComposing.setLength(0)
                    conversionStart(mKanjiKey)
                }

                else -> {
                    if (isUpper && mKanjiKey.isNotEmpty()) {
                        // 送り仮名開始
                        // 最初の平仮名はついシフトキーを押しっぱなしにしてしまうた
                        // め、kanjiKeyの長さをチェックkanjiKeyの長さが0の時はシフトが
                        // 押されていなかったことにして下方へ継続させる
                        if (isVowel(codeLower)) { // 母音なら送り仮名決定，変換
                            mComposing.append(codeLower.toChar()) // 「OkurI」の composing を ri に
                            mOkurigana = RomajiConverter.convert(mComposing.toString())
                            mKanjiKey.append(mComposing[0]) //送りありの場合子音文字追加
                            mComposing.setLength(0) // 送りがなに消費されたはず
                            conversionStart(mKanjiKey)
                        } else { // それ以外は送り仮名モード
                            if (!RomajiConverter.isIntermediateRomaji(
                                    "${mComposing}${codeLower.toChar()}"
                                )
                            ) {
                                if (mComposing.isNotEmpty()) {
                                    mComposing.setLength(0) // 「OkukR」のcomposingはrに (kはtypoとみなす)
                                }
                                if (!RomajiConverter.isIntermediateRomaji(
                                        codeLower.toChar().toString()
                                    )
                                ) {
                                    return // 今回の code 自体が typo なので無視
                                }
                            }
                            mComposing.append(codeLower.toChar()) // ty や ch のように 2 文字の場合あり
                            mKanjiKey.append(mComposing[0]) //送りありの場合子音文字追加
                            setComposingTextSKK(
                                createTrimmedBuilder(mKanjiKey).append('*').append(mComposing)
                            )
                            changeState(SKKOkuriganaState)
                        }
                    } else {
                        // 未確定
                        mComposing.append(codeLower.toChar())
                        val composing = mComposing.toString()
                        // 全角にする記号ならば全角，そうでなければローマ字変換、だめなら数字かチェック
                        val hiraganaChar = getZenkakuSeparator(composing)
                            ?: RomajiConverter.convert(composing)
                            ?: composing.toIntOrNull()?.toString()
                            ?: if (composing == "#") composing else null

                        if (hiraganaChar != null) {
                            mComposing.setLength(0)
                            mKanjiKey.append(
                                if (kanaState == SKKHiraganaState) {
                                    hiraganaChar
                                } else {
                                    katakana2hiragana(hiraganaChar)
                                }
                            )
                            setComposingTextSKK(mKanjiKey)
                        } else {
                            if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                                mComposing.setLength(0) // これまでの composing は typo とみなす
                                if (canRetry) return processKey(context, keyCode) // 「ca」などもあるので再突入
                            }
                            setComposingTextSKK("${mKanjiKey}${mComposing}")
                        }
                        updateSuggestions(mKanjiKey.toString())
                    }
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            setComposingTextSKK("${mKanjiKey}${mComposing}")
            updateSuggestions(mKanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.mKanjiKey.setLength(0) // 確定させない
        context.changeState(context.kanaState)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}
