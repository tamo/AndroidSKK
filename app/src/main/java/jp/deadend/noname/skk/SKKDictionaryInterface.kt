package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.btree.BTree
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.max

@Throws(IOException::class)
private fun appendToEntry(key: String, value: String, btree: BTree<String, String>) {
    val oldVal = btree.find(key)

    if (oldVal != null) {
        val valList = value.substring(1).split("/").dropLastWhile { it.isEmpty() }
        val oldValList = oldVal.substring(1).split("/").dropLastWhile { it.isEmpty() }

        val newValue = StringBuilder()
        newValue.append("/")
        valList.union(oldValList).forEach { newValue.append(it, "/") }
        btree.insert(key, newValue.toString(), true)
    } else {
        btree.insert(key, value, true)
    }
}

internal fun isTextDictInEucJp(inputStream: InputStream): Boolean {
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
        } catch (_: CharacterCodingException) {
            dLog("euc checker: failed after $prevLine")
            failed = true
        }
        dLog("euc checker: read $count lines")
    }
    return !failed
}

@Throws(IOException::class)
internal fun loadFromTextDict(
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

    val loadSKKLine = fun(line: String) {
        val idx = line.indexOf(' ')
        if (idx != -1 && !line.startsWith(";;")) {
            val key = line.take(idx)
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
    val loadWordListLine = fun(line: String) {
        val csv = line.split(',', '=')
        if (csv.size < 4) return
        val key = csv[1]
        val freq = if (csv[0] == " word" && csv[2] == "f") {
            val f = try {
                csv[3].toInt()
            } catch (_: NumberFormatException) {
                0
            }
            prevKey = key
            @Suppress("AssignedValueIsNeverRead")
            prevFreq = f
            isShortCut = false
            if ("not_a_word" in csv) {
                if (overwrite) btree.remove(key)
                return
            }
            f
        } else if (csv[0] == "  shortcut" && csv[2] == "f") {
            isShortCut = true
            try {
                csv[3].toInt()
            } catch (_: NumberFormatException) {
                prevFreq
            }
        } else return
        if (freq == 0) return
        if (!isShortCut && overwrite) {
            btree.insert(key, "/$freq/$key/", true)
        } else {
            val pairs = (btree.find(prevKey).orEmpty())
                .split('/').asSequence().filter { it.isNotEmpty() }
                .zipWithNext().filterIndexed { index, _ -> index % 2 == 0 }
                .map { (f, s) ->
                    s to try {
                        f.toInt()
                    } catch (_: NumberFormatException) {
                        0
                    }
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
            if (++count % 1000 == 0) {
                recMan.commit()
            }
        }
        recMan.commit()
    }
}

interface SKKDictionaryInterface {
    val mRecMan: RecordManager?
    val mBTree: BTree<String, String>?
    val mIsASCII: Boolean
    val mMutex: Mutex

    suspend fun findKeys(scope: CoroutineScope, rawKey: String): List<Pair<String, String>> {
        val key = katakana2hiragana(rawKey) ?: return listOf()
        val list = mutableListOf<Triple<String, String, Int>>()
        val tuple = Tuple<String, String>()
        val browser: TupleBrowser<String, String>
        val topFreq = ArrayList<Int>()

        try {
            browser = mBTree?.browse(key) ?: return listOf()

            // 絵文字は1500ほどあるし CandidatesView の行数が可変になったので多めが良さそう
            while (list.size < if (mIsASCII) 1500 else 15) {
                if (!scope.isActive) {
                    throw CancellationException()
                }

                if (!browser.getNext(tuple)) break
                val str = tuple.key
                when {
                    !str.startsWith(key) -> break

                    mIsASCII -> {
                        tuple.value
                            .split('/')
                            .filter { it.isNotEmpty() }
                            .zipWithNext()
                            .filterIndexed { index, _ -> index % 2 == 0 }
                            .forEach {
                                val freq = it.first.toInt() +
                                        if (str == key) 50 else 0 // 完全一致を優先
                                if (freq !in topFreq) {
                                    topFreq.apply {
                                        add(freq)
                                        sortDescending()
                                        while (size > 5) removeAt(lastIndex)
                                    }
                                }
                                if (freq >= topFreq.last()) { // 頻度が5位に入らなければだめ
                                    list.add(Triple(str, it.second, freq))
                                }
                            }
                        continue
                    }

                    !isAlphabet(str.first().code) && isAlphabet(str.last().code)
                        -> continue // 送りありエントリは飛ばす

                    else -> list.add(Triple(str, str, 0))
                }
            }
        } catch (e: IOException) {
            Log.e("SKK", "Error in findKeys(): $e")
            throw RuntimeException(e)
        }
        if (mIsASCII) {
            list.sortByDescending { it.third }
        }

        return list.map { it.first to it.second }
    }

    fun getCandidates(rawKey: String): List<String>? = null

    fun close() {
        runBlocking {
            mMutex.withLock {
                mRecMan?.let {
                    it.commit()
                    it.close()
                }
            }
        }
    }
}