package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.processConcatAndMore
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku
import java.util.ArrayDeque

class SKKRegister(private val engine: SKKEngine) {
    internal val mStack = ArrayDeque<RegistrationInfo>()
    internal var kanaStateBefore: SKKState = SKKHiraganaState

    internal class RegistrationInfo(val key: String, val okurigana: String) {
        val entry = StringBuilder()
    }

    internal val isOngoing: Boolean
        get() = !mStack.isEmpty()

    internal fun start(str: String) = engine.apply {
        mStack.addFirst(RegistrationInfo(str, mOkurigana))
        reset()
        kanaStateBefore = kanaState
        changeState(SKKHiraganaState) // 辞書にカタカナで登録してしまわないように
    }

    internal fun finish() {
        val regInfo = mStack.removeFirst()
        if (regInfo.entry.isNotEmpty()) {
            val regEntryStr = regInfo.entry.toString().let {
                // セミコロンとスラッシュのエスケープ (なので登録で注釈を付けることはできない)
                if (it.contains(';') || it.contains('/')) {
                    "(concat \"${
                        it.replace(";", "\\073")
                            .replace("/", "\\057")
                        //  .replace("#", "\\043") をしてしまうと数値変換が登録できなくなる
                    }\")"
                } else it
            }
            // if (isPersonalizedLearning) のチェックはこの場合しないでおく
            engine.mUserDict.addEntry(
                regInfo.key, regEntryStr, regInfo.okurigana
            )
            (processConcatAndMore(regInfo.entry.toString(), regInfo.key) + regInfo.okurigana).let {
                engine.commitTextSKK(
                    when (kanaStateBefore) {
                        SKKHiraganaState -> it
                        SKKKatakanaState -> hiragana2katakana(it, reversed = true).orEmpty()
                        SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(it)).orEmpty()
                        // 登録した内容が半角化できる文字を含んでいる場合は、やりすぎになるが無視
                        else -> throw RuntimeException("kanaState: $kanaStateBefore")
                    }
                )
            }
        }
        engine.reset()
        if (mStack.isNotEmpty()) {
            engine.setComposingTextSKK("")
        } else {
            engine.changeState(kanaStateBefore)
        }
    }

    fun cancel() = engine.apply {
        changeState(kanaStateBefore)
        val regInfo = mStack.removeFirst()
        mKanjiKey.setLength(0)
        mKanjiKey.append(regInfo.key)
        mComposing.setLength(0)
        mKanjiKey.lastOrNull()?.let { maybeComposing ->
            if (isAlphabet(maybeComposing.code)) {
                mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
                if (!skkPrefs.preferFlick) { // Flickでアルファベットがあっても困る
                    mComposing.append(maybeComposing)
                }
            }
        }
        changeState(SKKPreeditState)
        setComposingTextSKK("${mKanjiKey}${mComposing}")
        complete(mKanjiKey.toString())
    }
}
