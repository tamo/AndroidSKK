package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.btree.BTree
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.max

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
    isWordList: Boolean,
    recMan: RecordManager,
    btree: BTree<String, String>,
    overwrite: Boolean,
    callback: (count: Int) -> Unit
) {
    val decoder = Charset.forName(charset).newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)

    val loadSKKLine = fun (line: String) {
        val idx = line.indexOf(' ')
        if (idx != -1 && !line.startsWith(";;")) {
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1, line.length)
            if (overwrite) {
                btree.insert(key, value, true)
            } else {
                appendToEntry(key, value, btree)
            }
        }
    }

    var prevKey = ""
    var prevFreq = 0
    var isShortCut: Boolean
    val loadWordListLine = fun (line: String) {
        val csv = line.split(',', '=')
        if (csv.size < 4) return
        val key = csv[1]
        val freq = if (csv[0] == " word" && csv[2] == "f") {
            val f = try { csv[3].toInt() } catch (e: NumberFormatException) { 0 }
            prevKey = key
            prevFreq = f
            isShortCut = false
            if ("not_a_word" in csv) {
                if (overwrite) btree.remove(key)
                return
            }
            f
        } else if (csv[0] == "  shortcut" && csv[2] == "f") {
            isShortCut = true
            try { csv[3].toInt() } catch (e: NumberFormatException) { prevFreq }
        } else return
        if (freq == 0) return
        if (!isShortCut && overwrite) {
            btree.insert(key, "/$freq/$key/", true)
        } else {
            val pairs = (btree.find(prevKey) ?: "")
                .split('/').asSequence().filter { it.isNotEmpty() }
                .zipWithNext().filterIndexed { index, _ -> index % 2 == 0 }
                .map { (f, s) ->
                    s to try { f.toInt() } catch (e:NumberFormatException) { 0 }
                }
                .toMap().toMutableMap()
            val oldFreq = pairs[key] ?: 0
            pairs[key] = max(freq, oldFreq)
            pairs.flatMap { (k, v) -> listOf(v.toString(), k) }
                .reduce { acc, s -> "$acc/$s" }
                .let { btree.insert(prevKey, "/$it/", true) }
        }
    }

    val loadLine = if (isWordList) loadWordListLine else loadSKKLine
    BufferedReader(InputStreamReader(inputStream, decoder)).use { bufferedReader ->
        var count = 0
        bufferedReader.forEachLine { line ->
            loadLine(line)
            callback(count)
            if (++count % 1000 == 0) { recMan.commit() }
        }
        recMan.commit()
    }
}

interface SKKDictionaryInterface {
    val mRecMan: RecordManager
    val mRecID: Long
    val mBTree: BTree<String, String>
    val mIsASCII: Boolean
    var mIsLocked: Boolean

    fun findKeys(scope: CoroutineScope, rawKey: String): List<Pair<String, String>> {
        runBlocking { while (mIsLocked) delay(50) }
        mIsLocked = true

        val key = katakana2hiragana(rawKey) ?: return listOf<Pair<String, String>>().also { mIsLocked = false }
        val list = mutableListOf<Triple<String, String, Int>>()
        val tuple = Tuple<String, String>()
        val browser: TupleBrowser<String, String>
        var str: String
        val topFreq = ArrayList<Int>()

        try {
            browser = mBTree.browse(key) ?: return listOf<Pair<String, String>>().also { mIsLocked = false }

            while (list.size < if (mIsASCII) 100 else 5) {
                if (!scope.isActive) {
                    mIsLocked = false
                    throw CancellationException()
                }
                if (!browser.getNext(tuple)) break
                str = tuple.key
                if (!str.startsWith(key)) break
                if (mIsASCII) {
                    tuple.value
                        .split('/')
                        .filter { it.isNotEmpty() }
                        .zipWithNext()
                        .filterIndexed { index, _ -> index % 2 == 0 }
                        .forEach {
                            val freq = it.first.toInt() +
                                    if (str == key) 50 else 0 // 完全一致を優先
                            if (topFreq.size < 5 || freq >= topFreq.last()) {
                                topFreq.add(freq)
                                topFreq.sortDescending()
                                if (topFreq.size > 5) topFreq.removeAt(5)
                            }
                            if (freq >= topFreq.last()) { // 頻度が5位に入らなければだめ
                                list.add(Triple(str, it.second, freq))
                            }
                        }
                    continue
                }
                if (isAlphabet(str[str.length - 1].code) && !isAlphabet(str[0].code)) continue
                // 送りありエントリは飛ばす

                list.add(Triple(str, str, 0))
            }
        } catch (e: IOException) {
            Log.e("SKK", "Error in findKeys(): $e")
            throw RuntimeException(e)
        }
        if (mIsASCII) {
            list.sortByDescending { it.third }
        }

        return list.map { it.first to it.second }.also { mIsLocked = false }
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