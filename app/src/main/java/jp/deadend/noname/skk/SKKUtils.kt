package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.*
import java.util.zip.ZipInputStream

//private val PAT_QUOTED = "\"(.+?)\"".toRegex()
private val PAT_ESCAPE_NUM = """\\(\d+)""".toRegex()

// 半角から全角 (UNICODE)
fun hankaku2zenkaku(pcode: Int) = if (pcode == 0x20) 0x3000 else pcode - 0x20 + 0xFF00
// スペースだけ、特別

// ひらがなを全角カタカナにする
fun hirakana2katakana(str: String?): String? {
    if (str == null) { return null }

    val str2 = str.map { if (it in 'ぁ'..'ん') it.plus(0x60) else it }.joinToString("")
    val idx = str2.indexOf("ウ゛")
    return if (idx == -1) str2 else str2.replaceRange(idx, idx+2, "ヴ")
}

fun isAlphabet(code: Int) = (code in 0x41..0x5A || code in 0x61..0x7A)

fun isVowel(code: Int) = (code == 0x61 || code == 0x69 || code == 0x75 || code == 0x65 || code == 0x6F)
// a, i, u, e, o

fun removeAnnotation(str: String): String {
    val i = str.lastIndexOf(';') // セミコロンで解説が始まる
    return if (i == -1) str else str.substring(0, i)
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
    val zis = ZipInputStream(BufferedInputStream(input))
    val ze = zis.nextEntry
    val bos = BufferedOutputStream(FileOutputStream(File(outDir, ze.name)))
    val buf = ByteArray(1024)

    var size = zis.read(buf, 0, buf.size)
    while (size > -1) {
        bos.write(buf, 0, size)
        size = zis.read(buf, 0, buf.size)
    }

    bos.close()
    zis.closeEntry()
    zis.close()
}

