package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.isVowel
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku

// 漢字変換のためのひらがな入力中(▽モード)
object SKKPreeditState : SKKState {
    override val isJapanese = true
    override val isTransient = true
    override val isPreedit = true
    override val canComplete = true
    override val prefix = "▽"

    override fun handleASCIIKey(context: SKKEngine): Boolean =
        context.changeState(SKKAbbrevState).let { true }

    override fun handleEnter(context: SKKEngine): Boolean =
        context.changeState(context.kanaState).let { true }

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
        val codeLower = keyCode.lowerCode
        val isUpper = keyCode.isShifted

        context.apply {
            val canRetry = mRoman.isNotEmpty() // 無限ループ防止
            if (mRoman.length == 1) {
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mRoman[0], codeLower)
                if (hiraganaChar != null) {
                    mKanjiKey.insertAtCursor(hiraganaChar)
                    setComposingTextSKK()
                    mRoman.clear()
                }
            }

            when (keyCode) {
                skkPrefs.asciiKey, skkPrefs.abbrevKey, skkPrefs.zenkakuKey ->
                    if (changeInputMode(keyCode)) return
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

            when (val lower = Char(codeLower)) {
                ' ', '>', ':' -> {
                    if (mCandidates.isSpecial) { // 記号モード
                        return if (lower == ':') {
                            mCandidates.loadAllSymbols() // カテゴリ未選択なので全候補から絞る
                            changeState(SKKNarrowingState)
                            SKKNarrowingState.processKey(context, skkPrefs.asciiKey) // 注釈が英語なので
                        } else {
                            pickCandidatesViewManually(mCandidates.mIndex)
                        }
                    }

                    // 変換開始
                    // 最後に単体の'n'で終わっている場合、'ん'に変換
                    if (mRoman.length == 1 && mRoman[0] == 'n') {
                        mKanjiKey.insertAtCursor("ん")
                        setComposingTextSKK()
                    }
                    if (lower == '>') mKanjiKey.roman = '>' // 接頭辞入力
                    mRoman.clear()
                    startConversion()
                    if (lower == ':') changeState(SKKNarrowingState)
                }

                else -> {
                    if (mCandidates.isSpecial) {
                        changeState(kanaState)
                        kanaState.processKey(context, keyCode)
                        return
                    }

                    // 最初の平仮名はついシフトキーを押しっぱなしにしてしまうため、
                    // kanjiKey.isEmpty の時はシフトが押されていなかったことにする
                    if (isUpper && mKanjiKey.isNotEmpty()) {
                        // 送り仮名開始
                        if (isVowel(codeLower)) { // 母音なら送り仮名決定，変換
                            mRoman.append(lower) // 「OkurI」の ri で処理する
                            mOkurigana = RomajiConverter.convert(mRoman.toString())
                            mKanjiKey.roman = RomajiConverter.getConsonantForVoiced(mOkurigana)
                            mRoman.clear() // 送りがなに消費されたはず
                            startConversion()
                        } else { // それ以外は送り仮名モード
                            if (!RomajiConverter.isIntermediateRomaji("${mRoman}$lower")) {
                                if (mRoman.isNotEmpty()) mRoman.clear() // 「OkukR」のcomposingはrに (kはtypoとみなす)
                                if (!RomajiConverter.isIntermediateRomaji(lower.toString()))
                                    return // 今回の code 自体が typo なので無視
                            }
                            mRoman.append(lower) // ty や ch のように 2 文字の場合あり
                            mKanjiKey.roman = RomajiConverter
                                .run { getConsonantForVoiced(convert(mRoman.toString() + 'u')) }
                            mKanjiKey.deleteAfterCursor()
                            setComposingTextSKK("${mKanjiKey.entry}*$mRoman")
                            changeState(SKKOkuriganaState)
                        }
                    } else {
                        // 未確定
                        mRoman.append(lower)
                        val composing = mRoman.toString()
                        // 全角にする記号ならば全角，そうでなければローマ字変換、だめなら数字かチェック
                        val hiraganaChar = getZenkakuSeparator(composing)
                            ?: RomajiConverter.convert(composing).ifEmpty {
                                composing.toIntOrNull()?.toString()
                                    ?: if (composing == "#") composing else null
                            }

                        if (hiraganaChar != null) {
                            mRoman.clear()
                            mKanjiKey.insertAtCursor(
                                if (kanaState is SKKHiraganaState) hiraganaChar
                                else katakana2hiragana(hiraganaChar)
                            )
                        } else if (!RomajiConverter.isIntermediateRomaji(mRoman.toString())) {
                            mRoman.clear() // これまでの composing は typo とみなす
                            if (canRetry) return processKey(context, keyCode) // 「ca」などもあるので再突入
                        }
                        updateComplete()
                    }
                }
            }
        }
    }

    override fun handleBackspace(context: SKKEngine): Boolean =
        context.handleDelete().also { if (it) context.updateComplete() }

    override fun handleForwardDel(context: SKKEngine): Boolean =
        context.handleDelete(true).also { if (it) context.updateComplete() }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        context.mKanjiKey.clear() // 確定させない
        context.changeState(context.kanaState)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean = false

    override fun transformLastChar(context: SKKEngine, type: String): Boolean = true.also {
        context.run {
            if (mRoman.isNotEmpty()) {
                mKanjiKey.insertAtCursor(mRoman.toString())
                mRoman.clear()
                return@run
            }
            when (mKanjiKey.cursor) {
                0 -> return@run
                1 if type == SKKEngine.TRANS_SHIFT -> return@run
            }
            val newLastChar = RomajiConverter.transform(
                mKanjiKey.substring(mKanjiKey.cursor - 1, mKanjiKey.cursor), type
            ).second
            // この transform に 2 文字が渡ることはない

            mKanjiKey.deleteAtCursor()
            if (type == SKKEngine.TRANS_SHIFT) {
                mKanjiKey.deleteAfterCursor()
                mKanjiKey.roman = RomajiConverter.getConsonantForVoiced(newLastChar)
                mOkurigana = newLastChar
                startConversion() // ▼合い
            } else {
                mKanjiKey.insertAtCursor(newLastChar)
                updateComplete()
            }
        }
    }
}
