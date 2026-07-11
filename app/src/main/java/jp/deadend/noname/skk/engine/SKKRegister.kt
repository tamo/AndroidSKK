package jp.deadend.noname.skk.engine

import android.view.KeyEvent
import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.processConcatAndMore
import jp.deadend.noname.skk.zenkaku2hankaku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque

class SKKRegister(private val engine: SKKEngine) {
    internal val mStack = ArrayDeque<RegistrationInfo>()
    private var kanaStateBefore: SKKState = SKKHiraganaState

    internal class RegistrationInfo(val key: String, val okurigana: String) {
        val entry = StringBuilder()
        var cursor = 0
        operator fun component1() = this
        operator fun component2() = entry
    }

    internal fun first() = mStack.peekFirst()

    internal val isOngoing: Boolean
        get() = mStack.isNotEmpty()

    internal fun start(str: String) = engine.apply {
        SKKLog.d("SKKRegister.start($str)")
        mStack.addFirst(RegistrationInfo(str, mOkurigana))
        reset()
        kanaStateBefore = kanaState
        changeState(SKKHiraganaState) // 辞書にカタカナで登録してしまわないように
    }

    internal fun finish() {
        val (regInfo, entry) = mStack.removeFirst()
        if (entry.isNotEmpty()) {
            val regEntryStr = entry.toString().let {
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
            runBlocking(Dispatchers.IO) {
                engine.mUserDict.addEntry(regInfo.key, regEntryStr, regInfo.okurigana)
            }
            (processConcatAndMore(entry.toString(), regInfo.key) + regInfo.okurigana).let {
                engine.commitTextSKK(
                    when (kanaStateBefore) {
                        SKKHiraganaState -> it
                        SKKKatakanaState -> hiragana2katakana(it, reversed = true)
                        SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(it))
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
        val (regInfo, _) = mStack.removeFirst()
        mRoman.clear()
        mKanjiKey.set(regInfo.key)
        mKanjiKey.roman?.let { roman ->
            mKanjiKey.roman = null
            if (roman != '>' && keyboardType == "qwerty") { // Flickでアルファベットがあっても困る
                mRoman.append(roman)
            }
        }
        changeState(SKKPreeditState)
        setComposingTextSKK()
        complete(mKanjiKey.toString())
    }

    fun handleDpad(keyCode: Int): Boolean {
        if (engine.mKanjiKey.isNotEmpty() || engine.mRoman.isNotEmpty()) return false
        val (regInfo, entry) = first() ?: return false
        regInfo.cursor = when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> 0
            KeyEvent.KEYCODE_DPAD_LEFT -> regInfo.cursor.dec().coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> regInfo.cursor.inc().coerceAtMost(entry.length)
            KeyEvent.KEYCODE_MOVE_END -> entry.length
            else -> return false
        }
        engine.setComposingTextSKK()
        return true
    }
}
