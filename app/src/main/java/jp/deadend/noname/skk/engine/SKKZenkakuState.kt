package jp.deadend.noname.skk.engine

import android.os.Build
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku
// import jp.deadend.noname.skk.skkPrefs

// 全角英数モード
object SKKZenkakuState : SKKState {
    override val isTransient = false
    override val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        R.drawable.ic_full_alphabet
    } else {
        R.drawable.immodeic_full_alphabet
    }

    override fun handleKanaKey(context: SKKEngine) {
        // ここで toggleKanaKey のとき ASCII にしなくていいのか？
        // if (skkPrefs.toggleKanaKey) context.changeState(SKKASCIIState) else
        context.changeState(context.kanaState, false) // 「かな」は状態だけ変更
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.commitTextSKK(hankaku2zenkaku(pcode).toChar().toString(), 1)
    }

    override fun afterBackspace(context: SKKEngine) {}

    override fun handleCancel(context: SKKEngine): Boolean {
        return SKKHiraganaState.handleCancel(context)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState) // こちらはキーボードも変更
        return true
    }
}
