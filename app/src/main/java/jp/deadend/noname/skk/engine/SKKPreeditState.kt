package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.createTrimmedBuilder
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.isVowel
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku

// 漢字変換のためのひらがな入力中(▽モード)
object SKKPreeditState : SKKState {
    override val isTransient = true
    override val isPreedit = true
    override val canComplete = true
    override val prefix = "▽"
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

    override fun handleEnter(context: SKKEngine): Boolean {
        context.changeState(context.kanaState)
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val codeLower = keyCode.lowerCode
        val isUpper = keyCode.isShifted

        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            if (mComposing.length == 1) {
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)
                if (hiraganaChar != null) {
                    mKanjiKey.insertAtCursor(hiraganaChar)
                    setComposingTextSKK()
                    mComposing.setLength(0)
                }
            }

            when (keyCode) {
                skkPrefs.asciiKey, skkPrefs.abbrevKey, skkPrefs.zenkakuKey -> {
                    changeInputMode(keyCode)
                    return
                }
                // abbrevはtransientなのでchangeInputModeで自動確定されない
                // ▽の状態で英数(abbrev)と仮名(kanji)を行き来するには ModeKey.KANA と ABBREV を使うことにする
                // 一般的なキーコードが分かれば対応するが、emacsではabbrevから普通の▽(kanji)に行けないと思う

                skkPrefs.katakanaKey -> {
                    // カタカナ変換
                    if (mKanjiKey.isNotEmpty()) {
                        val str = if (kanaState is SKKHiraganaState) {
                            hiragana2katakana(mKanjiKey.toString())
                        } else {
                            mKanjiKey.toString() // すでにひらがななのでそのまま
                        }
                        commitTextSKK(str)
                        mKanjiKey.clear()
                    }
                    changeState(kanaState)
                    return
                }

                skkPrefs.hankakuKanaKey -> {
                    if (mKanjiKey.isNotEmpty()) {
                        val zenkakuKatakana = hiragana2katakana(mKanjiKey.toString())
                        val str = if (kanaState is SKKHanKanaState) {
                            zenkakuKatakana // 半角カナで半角を出すのはエンターだから Ctrl-Q は全角カナが自然だと思う
                        } else {
                            zenkaku2hankaku(zenkakuKatakana)
                        }
                        commitTextSKK(str)
                        mKanjiKey.clear()
                    }
                    changeState(kanaState)
                    return
                }
            }

            when (codeLower) {
                ' '.code, '>'.code, ':'.code -> {
                    if (mCandidates.isSpecial) // カテゴリ選択なので絞り込みなどない
                        return pickCandidatesViewManually(mCandidates.mIndex)

                    // 変換開始
                    // 最後に単体の'n'で終わっている場合、'ん'に変換
                    if (mComposing.length == 1 && mComposing[0] == 'n') {
                        mKanjiKey.insertAtCursor("ん")
                        setComposingTextSKK()
                    }
                    if (codeLower == '>'.code) mKanjiKey.insertAtCursor(">") // 接頭辞入力
                    mComposing.setLength(0)
                    startConversion()
                    if (codeLower == ':'.code) changeState(SKKNarrowingState)
                }

                else -> {
                    // 最初の平仮名はついシフトキーを押しっぱなしにしてしまうため、
                    // kanjiKey.isEmpty の時はシフトが押されていなかったことにする
                    if (isUpper && mKanjiKey.isNotEmpty()) {
                        // 送り仮名開始
                        if (isVowel(codeLower)) { // 母音なら送り仮名決定，変換
                            mComposing.append(Char(codeLower)) // 「OkurI」の composing を ri に
                            mOkurigana = RomajiConverter.convert(mComposing.toString())
                            mKanjiKey.insertAtCursor(mComposing[0].toString()) //送りありの場合子音文字追加
                            mComposing.setLength(0) // 送りがなに消費されたはず
                            startConversion()
                        } else { // それ以外は送り仮名モード
                            if (!RomajiConverter.isIntermediateRomaji(
                                    "${mComposing}${Char(codeLower)}"
                                )
                            ) {
                                if (mComposing.isNotEmpty()) {
                                    mComposing.setLength(0) // 「OkukR」のcomposingはrに (kはtypoとみなす)
                                }
                                if (!RomajiConverter.isIntermediateRomaji(
                                        Char(codeLower).toString()
                                    )
                                ) {
                                    return // 今回の code 自体が typo なので無視
                                }
                            }
                            mComposing.append(Char(codeLower)) // ty や ch のように 2 文字の場合あり
                            mKanjiKey.insertAtCursor(mComposing[0].toString()) //送りありの場合子音文字追加
                            mKanjiKey.deleteAfterCursor()
                            setComposingTextSKK(
                                createTrimmedBuilder(mKanjiKey.entry).append('*').append(mComposing)
                            )
                            changeState(SKKOkuriganaState)
                        }
                    } else {
                        // 未確定
                        mComposing.append(Char(codeLower))
                        val composing = mComposing.toString()
                        // 全角にする記号ならば全角，そうでなければローマ字変換、だめなら数字かチェック
                        val hiraganaChar = getZenkakuSeparator(composing)
                            ?: RomajiConverter.convert(composing).ifEmpty {
                                composing.toIntOrNull()?.toString()
                                    ?: if (composing == "#") composing else null
                            }

                        if (hiraganaChar != null) {
                            mComposing.setLength(0)
                            mKanjiKey.insertAtCursor(
                                if (kanaState is SKKHiraganaState) hiraganaChar
                                else katakana2hiragana(hiraganaChar)
                            )
                            setComposingTextSKK()
                        } else {
                            if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                                mComposing.setLength(0) // これまでの composing は typo とみなす
                                if (canRetry) return processKey(context, keyCode) // 「ca」などもあるので再突入
                            }
                            setComposingTextSKK()
                        }
                        complete(mKanjiKey.toString())
                    }
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            setComposingTextSKK()
            complete(mKanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        context.mKanjiKey.clear() // 確定させない
        context.changeState(context.kanaState)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}
