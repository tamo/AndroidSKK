package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.btree.BTree
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

@Throws(IOException::class)
private fun appendToEntry(key: String, value: String, btree: BTree<String, String>) {
    val oldval = btree.find(key)

    if (oldval != null) {
        val valList = value.substring(1).split("/").dropLastWhile { it.isEmpty() }
        val oldvalList = oldval.substring(1).split("/").dropLastWhile { it.isEmpty() }

        val newValue = StringBuilder()
        newValue.append("/")
        valList.union(oldvalList).forEach { newValue.append(it, "/") }
        btree.insert(key, newValue.toString(), true)
    } else {
        btree.insert(key, value, true)
    }
}

internal fun isTextDicInEucJp(inputStream: InputStream): Boolean {
    val decoder = Charset.forName("EUC-JP").newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
    var failed = false
    BufferedReader(InputStreamReader(inputStream, decoder)).use { bufferedReader ->
        var count = 0
        var prevLine = "0: (nothing has been read)"
        try {
            bufferedReader.forEachLine { line ->
                prevLine = "${++count}: $line"
                // if (count > 1000) { return@forEachLine }
            }
        } catch (e: CharacterCodingException) {
            dlog("euc checker: failed after $prevLine")
            failed = true
        }
        dlog("euc checker: read $count lines")
    }
    return !failed
}

@Throws(IOException::class)
internal fun loadFromTextDic(
    inputStream: InputStream,
    charset: String,
    recMan: RecordManager,
    btree: BTree<String, String>,
    overwrite: Boolean,
    callback: (count: Int) -> Unit
) {
    val decoder = Charset.forName(charset).newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)

    BufferedReader(InputStreamReader(inputStream, decoder)).use { bufferedReader ->
        var count = 0
        bufferedReader.forEachLine { line ->
            val idx = line.indexOf(' ')
            if (idx != -1 && !line.startsWith(";;")) {
                val key = line.substring(0, idx)
                val value = line.substring(idx + 1, line.length)
                if (overwrite) {
                    btree.insert(key, value, true)
                } else {
                    appendToEntry(key, value, btree)
                }

                if (++count % 1000 == 0) { recMan.commit() }
                callback(count)
            }
        }
        recMan.commit()
    }
}

interface SKKDictionaryInterface {
    val mRecMan: RecordManager
    val mRecID: Long
    val mBTree: BTree<String, String>
    val mIsASCII: Boolean

    fun findKeys(scope: CoroutineScope, rawKey: String): List<String> {
        val key = katakana2hiragana(rawKey) ?: return listOf()
        val list = mutableListOf<Tuple<String, Int>>()
        val tuple = Tuple<String, String>()
        val browser: TupleBrowser<String, String>
        var str: String
        val topFreq = ArrayList<Int>()

        try {
            browser = mBTree.browse(key) ?: return listOf()

            while (list.size < if (mIsASCII) 100 else 5) {
                scope.ensureActive() // ここでキャンセルされる
                if (!browser.getNext(tuple)) break
                str = tuple.key
                if (!str.startsWith(key)) break
                if (mIsASCII) {
                    val freq = tuple.value.let {
                        it.substring(1, it.length - 1) // 前後のスラッシュを除く
                            .substringBefore('/') // 複数になっている場合は最初だけ選ぶ
                            .toInt()
                    }
                    if (topFreq.size < 5 || freq >= topFreq.last()) {
                        topFreq.add(freq)
                        topFreq.sortDescending()
                        if (topFreq.size > 5) topFreq.removeAt(5)
                    }
                    if (freq < topFreq.last()) continue // 頻度が5位に入らなければだめ
                    list.add(Tuple<String, Int>(str, freq))
                    continue
                }
                if (isAlphabet(str[str.length - 1].code) && !isAlphabet(str[0].code)) continue
                // 送りありエントリは飛ばす

                list.add(Tuple(tuple.key, 0))
            }
        } catch (e: IOException) {
            Log.e("SKK", "Error in findKeys(): $e")
            throw RuntimeException(e)
        }
        if (mIsASCII) {
            list.sortByDescending { it.value }
        }

        return list.map { it.key }
    }

    fun close() {
        try {
            mRecMan.commit()
            mRecMan.close()
        } catch (e: Exception) {
            Log.e("SKK", "Error in close(): $e")
            throw RuntimeException(e)
        }
    }
}