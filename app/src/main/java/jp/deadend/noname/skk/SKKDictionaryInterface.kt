package jp.deadend.noname.skk

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import jdbm.RecordManager
import jdbm.btree.BTree
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import java.io.InputStream

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

@Throws(IOException::class)
internal fun loadFromTextDic(
    inputStream: InputStream,
    recMan: RecordManager,
    btree: BTree<String, String>,
    overwrite: Boolean
) {
    val decoder = Charset.forName("UTF-8").newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT)

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
            }
        }
    }

    recMan.commit()
}

interface SKKDictionaryInterface {
    val mRecMan: RecordManager
    val mRecID: Long
    val mBTree: BTree<String, String>

    fun findKeys(key: String, isASCII: Boolean = false): List<String> {
        val list = mutableListOf<Tuple<String, Int>>()
        val tuple = Tuple<String, String>()
        val browser: TupleBrowser<String, String>
        var str: String
        val topFreq = ArrayList<Int>()

        try {
            browser = mBTree.browse(key) ?: return listOf()

            while (list.size < if (isASCII) 100 else 5) {
                if (!browser.getNext(tuple)) break
                str = tuple.key
                if (!str.startsWith(key)) break
                if (isASCII) {
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
        if (isASCII) {
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