package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import java.io.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.max

private val PAT_QUOTED = "\"(.+?)\"".toRegex()
private val PAT_ESCAPE_NUM = """\\(\d+)""".toRegex()

// 半角から全角 (UNICODE)
fun hankaku2zenkaku(str: String?): String? {
    if (str == null) { return null }

    var skipNext = false
    return str.mapIndexedNotNull { index, it ->
        when {
            skipNext -> {
                skipNext = false
                null
            }

            it.code < 0x10000 -> {
                val c = H2Z[it.code]
                when {
                    c == null -> it

                    str.length > index + 1 -> when (str[index + 1]) {
                        'ﾞ' -> H2Z[0x10000 + it.code]?.let { d ->
                            skipNext = true
                            Char(d)
                        } ?: Char(c)

                        'ﾟ' -> H2Z[0x20000 + it.code]?.let { h ->
                            skipNext = true
                            Char(h)
                        } ?: Char(c)

                        else -> Char(c)
                    }

                    else -> Char(c)
                }
            }

            else -> it
        }
    }.joinToString("")
}

fun zenkaku2hankaku(str: String?): String? {
    if (str == null) { return null }

    return str.map {
        val fc = Z2H[it.code]
        if (fc == null) {
            it.toString()
        } else {
            val f = (0x30000 and fc) shr 16
            val c = 0x0FFFF and fc
            Char(c).toString() + when (f) {
                1 -> "ﾞ"
                2 -> "ﾟ"
                else -> ""
            }
        }
    }.joinToString("")
}

// ひらがなを全角カタカナにする
fun hiragana2katakana(str: String?, reversed: Boolean = false): String? {
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
    val i = str.indexOf(';') // セミコロンで解説が始まる
    return if (i == -1) str else str.substring(0, i)
}

private fun processNumber(str: String, number: String): String {
    return str
        .replace("#0", number) // 半角
        .replace("#1", number // 全角
            .map { char -> when (char.code) {
                in '0'.code..'9'.code -> (char.code + '０'.code - '0'.code).toChar()
                '.'.code -> '．' // もしかして「・」の方がいい?
                else -> char
            } }
            .joinToString("")
        )
        .replace("#2", number // 単純漢数字
            .map { char -> when (char.code) {
                in '0'.code..'9'.code -> "〇一二三四五六七八九"[char.code - '0'.code]
                '.'.code -> '．' // もしかして「・」の方がいい?
                else -> char
            } }
            .joinToString("")
        )
        .replace("#3", number // 位取りのある漢数字 (たぶんバグがある)
            .takeWhile { it != '.' } // 整数部のみ
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
            + number.dropWhile { it != '.' } // 小数点以下は位がないので別扱いにする
            .map { char -> when (char.code) {
                in '0'.code..'9'.code -> "〇一二三四五六七八九"[char.code - '0'.code]
                '.'.code -> '．' // もしかして「・」の方がいい?
                else -> char
            } }
            .joinToString("")
        )
        .replace("#", number) // suggestion 用
}

private fun unquote(str: String): String = PAT_QUOTED.findAll(str)
    .map { it.groupValues[1] }
    .joinToString("")

private fun unescapeOctal(str: String): String = PAT_ESCAPE_NUM.replace(str) {
    it.value.substring(1).toInt(8).toChar().toString()
} // emacs-lispのリテラルは8進数

fun processConcatAndMore(rawStr: String, kanjiKey: String): String {
    val number = Regex("\\d+").find(kanjiKey)?.value?.toIntOrNull()?.toString() ?: "#"

    if (rawStr.startsWith("(concat ") && rawStr.endsWith(")")) {
        return unescapeOctal(unquote(processNumber(rawStr, number)))
    }

    operator fun MatchResult.Destructured.component11(): String =
        this.toList()[10] // 不足を拡張
    Regex("^\\(cal-" +
            "(arg([-+*=]+)([\\d.]*)?([ymd])?-)?" +
            "(year([-+]\\d+)-)?" +
            "(month([-+]\\d+)-)?" +
            "(date([-+]\\d+)-)?" +
            "format (.+)\\)"
    ).matchEntire(rawStr)?.destructured
        ?.let { (hasArg, argOpts, argTimes, argUnit, _, yearDelta, _, monthDelta, _, dateDelta, raw) ->
            val isDelta = !argOpts.contains('=')
            val sign = if (argOpts.contains('-')) -1 else 1
            val times = if (argOpts.contains('*')) argTimes.toFloatOrNull() ?: 1f else 1f
            val calcDelta = if (!argOpts.contains('*')) {
                (argTimes.toFloatOrNull() ?: 0f) * sign
            } else 0f
            val str = unescapeOctal(unquote(raw))

            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"), Locale.JAPAN)

            if (number != "#") {
                val num = number.toInt()
                when (argUnit) {
                    "y" -> {
                        if (isDelta) calendar.add(Calendar.YEAR, num * sign * times.toInt())
                        else calendar.set(Calendar.YEAR, num * times.toInt())
                    }
                    "m" -> {
                        if (isDelta) calendar.add(Calendar.MONTH, num * sign * times.toInt())
                        else calendar.set(Calendar.MONTH, num * times.toInt())
                    }
                    "d" -> {
                        if (isDelta) calendar.add(Calendar.DATE, num * sign * times.toInt())
                        else calendar.set(Calendar.DAY_OF_MONTH, num * times.toInt())
                    }
                    else -> return processNumber(str, (num * times + calcDelta).toString())
                    // 単位換算など
                }
            } else if (hasArg.isNotEmpty()) {
                return str.replace(Regex("[ymd]{1,4}"), "#")
            }

            val oldYear = calendar.get(Calendar.YEAR)
            if ((yearDelta.toIntOrNull() ?: 0) <= -oldYear) {
                return str.replace(Regex("y{1,4}"), "#")
            }

            calendar.add(Calendar.YEAR, yearDelta.toIntOrNull() ?: 0)
            calendar.add(Calendar.MONTH, monthDelta.toIntOrNull() ?: 0)
            calendar.add(Calendar.DATE, dateDelta.toIntOrNull() ?: 0)

            val dayWeek = calendar.get(Calendar.DAY_OF_WEEK) // Calendar.*DAY == 日1〜土7
            val dayWeekStr = "X日月火水木金土"[dayWeek].toString()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val yearStr = calendar.get(Calendar.YEAR).toString()
            val format = str
                .replace("aaaa", dayWeekStr + "曜日")
                .replace("aaa", dayWeekStr)
                .replace("aa", if (hour < 12) "午前" else "午後")
                .replace(Regex("(?<!y)y(?!y)"), yearStr) // 単独の y で 1 桁を許容するように

            return DateFormat.format(format, calendar).toString()
        }

    return processNumber(rawStr, number)
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
}

private val Z2H_PAIRS =
    stepConv( // ←↑→↓
        0x2190, 1, 4, 0x0FFE9
    ) + listOf(
        0x2502 to 0x0FFE8, // ｜
        0x25A0 to 0x0FFED, // ■
        0x25CB to 0x0FFEE, // ○

        0x3000 to 0x00020, // スペース
        0x3001 to 0x0FF64, // 、
        0x3002 to 0x0FF61, // 。
        0x300C to 0x0FF62, // 「
        0x300D to 0x0FF63, // 」

        0x3099 to 0x10000, // ゛
        0x309A to 0x20000, // ゜
    ) + stepConv( // ァィゥェォ
        0x30A1, 2, 5, 0x0FF67
    ) + stepConv( // アイウエオ
        0x30A2, 2, 5, 0x0FF71
    ) + dakutenConv( // カガキギ..タダチヂ
        0x30AB, 12, 0x0FF76
    ) + listOf(
        0x30C3 to 0x0FF6F // ッ
    ) + dakutenConv( // ツヅテデトド
        0x30C4, 3, 0x0FF82
    ) + stepConv( // ナニヌネノ
        0x30CA, 1, 5, 0x0FF85
    ) + handakutenConv( // ハバパヒビピ..ホボポ
        0x30CF, 5, 0xFF8A
    ) + stepConv( // マミムメモ
        0x30DE, 1, 5, 0x0FF8F
    ) + stepConv( // ャュョ
        0x30E3, 2, 3, 0x0FF6C
    ) + stepConv( // ヤユヨ
        0x30E4, 2, 3, 0x0FF94
    ) + stepConv( // ラリルレロ
        0x30E9, 1, 5, 0x0FF97
    ) + listOf(
        // 0x30EE to 0x0FF9C, // ヮ
        0x30EF to 0x0FF9C, // ワ
        // 0x30F0 to 0x0FF72, // ヰ
        // 0x30F1 to 0x0FF74, // ヱ
        0x30F2 to 0x0FF65, // ヲ
        0x30F3 to 0x0FF9C, // ン
        0x30F4 to 0x1FF73, // ヴ
        0x30F5 to 0x0FF76, // 小書きカ
        0x30F6 to 0x0FF79, // 小書きケ
        0x30F7 to 0x1FF9C, // ワに濁点
        // 0x30F8 to 0x1FF72, // ヰに濁点
        // 0x30F9 to 0x1FF74, // ヱに濁点
        0x30FA to 0x1FF65, // ヲに濁点
        0x30FB to 0x0FF65, // 中黒
        0x30FC to 0x0FF70, // ー
        // 0x30FD..0x30FF ゝゞヿ
    ) + stepConv( // !..~ ASCII
        0xFF01, 1, 0x5E, 0x21
    ) + listOf(
        0xFF5F to 0x2985, // 二重括弧
        0xFF60 to 0x2986,
        // 0xFF61..0xFF9F 半角
        // 0xFFA0..0xFFDC ハングル
        0xFFE0 to 0x00A2, // ¢
        0xFFE1 to 0x00A3, // £
        0xFFE2 to 0x00AC, // not
        0xFFE3 to 0x00AF, // マクロン
        0xFFE4 to 0x00A6, // 破線｜
        0xFFE5 to 0x00A5, // ￥
        0xFFE6 to 0x20A9, // ウォン
    )
private val Z2H_OPTIONAL = listOf(
    0x30EE to 0x0FF9C, // ヮ
    0x30F0 to 0x0FF72, // ヰ
    0x30F1 to 0x0FF74, // ヱ
    0x30F8 to 0x1FF72, // ヰに濁点
    0x30F9 to 0x1FF74, // ヱに濁点
)

val Z2H = (Z2H_PAIRS + Z2H_OPTIONAL).toMap()
val H2Z = Z2H_PAIRS.associate { (z, h) -> h to z }

private fun stepConv(start: Int, step: Int, number: Int, target: Int): List<Pair<Int, Int>> {
    val other = start + step * (number - 1)
    val list = mutableListOf<Pair<Int, Int>>()
    for (i in start.rangeTo(other).step(step)) {
        list.add(i to (i - start) / step + target)
    }
    return list
}

private fun dakutenConv(start: Int, number: Int, target: Int) =
    stepConv(
        start, 2, number, target
    ) + stepConv(
        start + 1, 2, number, 0x10000 + target
    )

@Suppress("SameParameterValue")
private fun handakutenConv(start: Int, number: Int, target: Int) =
    stepConv(
        start, 3, number, target
    ) + stepConv(
        start + 1, 3, number, 0x10000 + target
    ) + stepConv(
        start + 2, 3, number, 0x20000 + target
    )
