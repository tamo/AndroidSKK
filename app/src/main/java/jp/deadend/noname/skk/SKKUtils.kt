package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.max

//private val PAT_QUOTED = "\"(.+?)\"".toRegex()
private val PAT_ESCAPE_NUM = """\\(\d+)""".toRegex()

// 半角から全角 (UNICODE)
fun hankaku2zenkaku(pcode: Int) = if (pcode == 0x20) 0x3000 else pcode - 0x20 + 0xFF00
// スペースだけ、特別

// ひらがなを全角カタカナにする
fun hirakana2katakana(str: String?, reversed: Boolean = false): String? {
    if (str == null) { return null }

    var skipNext = false // 「う゛」を「ヴ」にして文字数が減るときのフラグ
    val str2 = str.mapIndexedNotNull { index, it ->
        if (skipNext) {
            skipNext = false
            null
        } else when (it) {
            in 'ぁ'..'ゔ' -> {
                if (it == 'う' && str.length > index + 1 && str[index + 1] == '゛') {
                    skipNext = true
                    'ヴ'
                }
                else it.plus(0x60)
            }
            in 'ァ'..'ヴ' -> {
                if (reversed) it.minus(0x60)
                else it
            }
            else -> it
        }
    }.joinToString("")

    return str2
}

fun katakana2hiragana(str: String?): String? {
    if (str == null) { return null }

    return str.map { if (it in 'ァ'..'ヴ') it.minus(0x60) else it }.joinToString("")
    // 「ヴ」が「う゛」ではなく「ゔ」になる
}

fun isAlphabet(code: Int) = (code in 0x41..0x5A || code in 0x61..0x7A)

fun isVowel(code: Int) = (code == 0x61 || code == 0x69 || code == 0x75 || code == 0x65 || code == 0x6F)
// a, i, u, e, o

fun removeAnnotation(str: String): String {
    val i = str.lastIndexOf(';') // セミコロンで解説が始まる
    return if (i == -1) str else str.substring(0, i)
}

fun processNumber(str: String, number: String): String {
    return str
        .replace("#0", number) // 半角
        .replace("#1", number // 全角
            .map { char ->
                if (char.code in '0'.code..'9'.code) (char.code + '０'.code - '0'.code).toChar()
                else char
            }
            .joinToString("")
        )
        .replace("#2", number // 単純漢数字
            .map { char ->
                if (char.code in '0'.code..'9'.code) "〇一二三四五六七八九"[char.code - '0'.code]
                else char
            }
            .joinToString("")
        )
        .replace("#3", number // 位取りのある漢数字 (たぶんバグがある)
            .reversed()
            .mapIndexed { index, char ->
                val sb = StringBuilder()
                sb.append(
                    when (char.code) {
                        '1'.code -> if (index == 0) "一" else ""
                        in '2'.code..'9'.code -> "二三四五六七八九"[char.code - '2'.code]
                        else -> ""
                    }
                )
                if (char != '0') sb.append(
                    when (index % 4) {
                        1 -> "十"
                        2 -> "百"
                        3 -> "千"
                        else -> ""
                    }
                )
                if (index in 4..20 && index % 4 == 0) {
                    if ((number.substring( // 4桁まるごと0000かどうか
                            max(number.length - index - 4, 0),
                            number.length - index
                    ).toIntOrNull() ?: 0) > 0) {
                        sb.append("一万億兆京垓"[index / 4])
                    }
                }
                sb.toString()
            }
            .reversed()
            .joinToString("")
        )
        .replace("#", number) // suggestion 用
}

fun processConcatAndEscape(str: String): String {
    val len = str.length
    if (len < 12) { return str }
    if (str[0] != '('
            || str.substring(1, 9) != "concat \""
            || str.substring(len - 2, len) != "\")"
    ) { return str }
    // (concat "...") の形に決め打ち

//    val str2 = PAT_QUOTED.findAll(str.substring(8 until len-1)).map { it.value }.joinToString("")

    return PAT_ESCAPE_NUM.replace(str.substring(9 until len-2)) {
        it.value.substring(1).toInt(8).toChar().toString()
    }
    // emacs-lispのリテラルは8進数
}

fun createTrimmedBuilder(orig: StringBuilder): StringBuilder {
    val ret = StringBuilder(orig)
    ret.deleteCharAt(ret.length - 1)
    return ret
}

// debug log
fun dlog(msg: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("SKK", msg)
}

fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val fileName: String?
    when (uri.scheme) {
        "content" -> {
            val cursor = context.contentResolver
                            .query(uri, arrayOf((OpenableColumns.DISPLAY_NAME)), null, null, null)
            cursor?.moveToFirst()
            fileName = cursor?.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            cursor?.close()
        }
        "file" -> fileName = uri.path?.let { File(it).name }
        else -> fileName = null
    }

    return fileName
}

@Throws(IOException::class)
internal fun unzipFile(input: InputStream, outDir: File) {
    MainScope().launch(Dispatchers.IO) {
        val zis = ZipInputStream(BufferedInputStream(input))
        var ze: ZipEntry
        while (zis.nextEntry.also { ze = it } != null) {
            val f = File(outDir, ze.name)
            if (!f.canonicalPath.startsWith(outDir.canonicalPath)) {
                throw IOException("zip path traversal")
            }
            if (f.isDirectory) {
                f.mkdirs()
            } else {
                f.parentFile?.mkdirs()
                val bos = BufferedOutputStream(FileOutputStream(f))
                val buf = ByteArray(1024)

                var size = zis.read(buf, 0, buf.size)
                while (size > -1) {
                    bos.write(buf, 0, size)
                    size = zis.read(buf, 0, buf.size)
                }

                bos.close()
            }
            zis.closeEntry()
        }
        zis.close()
    }.start()
}

