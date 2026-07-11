package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

object SKKHiraganaState : SKKState {
    override val icon = R.drawable.ic_hiragana

    override fun handleKanaKey(context: SKKEngine) {
        if (skkPrefs.toggleKanaKey) context.changeState(SKKASCIIState, true)
    }

    override fun handleEnter(context: SKKEngine): Boolean = context.run {
        when {
            mComposing.isNotEmpty() -> {
                commitComposing()
                setComposingTextSKK("")
                true
            }

            mRegister.isOngoing -> {
                mRegister.finish()
                true
            }

            else -> false
        }
    }

    internal fun processKana(
        context: SKKEngine,
        keyCode: Int, commitFunc: (SKKEngine, String) -> Unit
    ) {
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        val converted = RomajiConverter.convert(Character.toString(charCode))
        val isShift = converted.startsWith("<shift>")
        // シフトキーの状態をチェック
        val isUpper = isShift || Character.isUpperCase(charCode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val codeLower = when {
            isShift -> converted.removePrefix("<shift>").first().code
            isUpper -> Character.toLowerCase(charCode)
            else -> charCode
        }

        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            if (mComposing.length == 1) {
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)
                if (hiraganaChar != null) commitFunc(context, hiraganaChar)
            }
            if (isUpper) {
                // 漢字変換候補入力の開始。PreeditModeへの移行
                // すでに composing がある場合はそこから PreeditMode だったものとする (mA = Ma)
                changeState(SKKPreeditState)
                // Q で PreeditState 開始
                if (!skkPrefs.isModeKey(encodeKey(codeLower))) {
                    SKKPreeditState.processKey(context, codeLower)
                } else {
                    context.updateComplete() // 画面の更新
                }
            } else {
                mComposing.append(Char(codeLower))
                // 全角にする記号ならば全角，そうでなければローマ字変換
                val hiraganaChar = getZenkakuSeparator(mComposing.toString())
                    ?: RomajiConverter.convert(mComposing.toString())

                if (hiraganaChar.isNotEmpty()) { // 確定できるものがあれば確定
                    commitFunc(context, hiraganaChar)
                } else { // アルファベットならComposingに積む
                    if (isAlphabet(codeLower)) {
                        if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                            mComposing.setLength(0) // これまでの composing は typo とみなす
                            if (canRetry) // 「ca」などもあるので再突入
                                return processKana(context, keyCode, commitFunc)
                        }
                        setComposingTextSKK(mComposing)
                    } else {
                        commitFunc(context, Char(codeLower).toString())
                    }
                }
            }
        }
    }

    override fun handleBackspace(context: SKKEngine): Boolean =
        context.handleDelete()
            .also { if (it) context.setComposingTextSKK(context.mComposing) }

    override fun handleForwardDel(context: SKKEngine): Boolean =
        context.handleDelete(true)
            .also { if (it) context.setComposingTextSKK(context.mComposing) }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (context.changeInputMode(keyCode)) return
        processKana(context, keyCode) { engine, hiraganaChar ->
            engine.commitTextSKK(hiraganaChar)
            engine.mComposing.setLength(0)
        }
    }


    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean =
        context.run {
            if (mRegister.isOngoing) {
                mRegister.cancel()
                true
            } else if (reconvert) {
                reConvert()
            } else false
        }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}
