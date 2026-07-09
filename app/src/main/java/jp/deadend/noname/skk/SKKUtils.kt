package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream
import kotlin.math.max

fun Int.toAlphaFloat(): Float = this / 255f
fun Int.percentToAlpha(): Int = this * 255 / 100

private val PAT_QUOTED = "\"(.+?)\"".toRegex()
private val PAT_ESCAPE_NUM = """\\\d{1,3}""".toRegex()
private val PAT_NUMBER_LIST = Regex("\\d+(\\.\\d+)?")
private val PAT_HASH_NUM = Regex("#[0-3]")
private val PAT_HASH_NUM_OPT = Regex("#[0-3]?")
private val PAT_CAL = Regex(
    "^\\(cal-" +
            "(arg([-+*=]+)([\\d.]*)?([ymd])?-)?" +
            "(year([-+]\\d+)-)?" +
            "(month([-+]\\d+)-)?" +
            "(date([-+]\\d+)-)?" +
            "format (.+)\\)"
)

// 半角から全角 (UNICODE)
fun hankaku2zenkaku(str: String): String = str.let { s ->
    buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val char = s[i]
            val code = char.code
            if (code < 0x10000) {
                val base = H2Z[code]
                if (base != null && i + 1 < s.length) {
                    val combined = when (s[i + 1]) {
                        'ﾞ' -> H2Z[0x10000 + code]
                        'ﾟ' -> H2Z[0x20000 + code]
                        else -> null
                    }
                    if (combined != null) {
                        append(combined.toChar())
                        i += 2
                        continue
                    }
                }
                append(base?.toChar() ?: char)
            } else {
                append(char)
            }
            i++
        }
    }
}

fun zenkaku2hankaku(str: String): String = str.let { s ->
    buildString(s.length) {
        for (char in s) {
            val fc = Z2H[char.code]
            if (fc == null) {
                append(char)
            } else {
                val f = (0x30000 and fc) shr 16
                val c = 0x0FFFF and fc
                append(c.toChar())
                when (f) {
                    1 -> append('ﾞ')
                    2 -> append('ﾟ')
                }
            }
        }
    }
}

// ひらがなを全角カタカナにする
fun hiragana2katakana(str: String, reversed: Boolean = false): String {
    var skipNext = false // 「う゛」を「ヴ」にして文字数が減るときのフラグ
    return str.mapIndexedNotNull { index, it ->
        if (skipNext) {
            skipNext = false
            null
        } else when (it) {
            in 'ぁ'..'ゔ' -> {
                if (it == 'う' && str.length > index + 1 && isDakuten(str[index + 1].code)) {
                    skipNext = true
                    'ヴ'
                } else it + 0x60
            }

            in 'ァ'..'ヴ' -> {
                if (reversed) it - 0x60
                else it
            }

            else -> it
        }
    }.joinToString("")
}

fun katakana2hiragana(str: String): String = str.map {
    if (it in 'ァ'..'ヴ') it.minus(0x60) else it
}.joinToString("") // 「ヴ」が「う゛」ではなく「ゔ」になる

fun isAlphabet(code: Int) = code.toChar() in 'a'..'z' || code.toChar() in 'A'..'Z'
fun isAlNum(code: Int) = isAlphabet(code) || code.toChar() in '0'..'9'

fun isHiragana(code: Int) = code in 0x3041..0x3096
fun isKatakana(code: Int) = (H2Z[code] ?: code) in 0x30A1..0x30FA

fun isDakuten(code: Int) = code == 0x3099 || code == 0x309B
fun isHandakuten(code: Int) = code == 0x309A || code == 0x309C
fun isKanaSymbol(code: Int) = isDakuten(code) || isHandakuten(code)
        || code in 0x309D..0x309E // ゝゞ
        || code in 0x30FD..0x30FE // ヽヾ

fun isAnyKana(code: Int) = isHiragana(code) || isKatakana(code) || isKanaSymbol(code)

fun isVowel(code: Int) = code.toChar() in "aiueo"
// a, i, u, e, o

fun removeAnnotation(str: String): String = str.substringBefore(';')

private fun processNumber(str: String, numberList: List<String>): String {
    var result = str
    numberList.forEach { result = processSingleNumber(result, it) }
    return result.replace(PAT_HASH_NUM, "#")
}

private fun processSingleNumber(str: String, number: String): String {
    return when (val target = PAT_HASH_NUM_OPT.find(str)?.value) {
        "#0", "#" -> str.replaceFirst(target, number)
        "#1" -> str.replaceFirst(
            target, number // 全角
                .map { char ->
                    when (char) {
                        in '0'..'9' -> Char(char.code + '０'.code - '0'.code)
                        '.' -> '．' // もしかして「・」の方がいい?
                        else -> char
                    }
                }
                .joinToString("")
        )

        "#2" -> str.replaceFirst(
            target, number // 単純漢数字
                .map { char ->
                    when (char) {
                        in '0'..'9' -> "〇一二三四五六七八九"[char - '0']
                        '.' -> '．' // もしかして「・」の方がいい?
                        else -> char
                    }
                }
                .joinToString("")
        )

        "#3" -> str.replaceFirst(
            target, number // 位取りのある漢数字 (たぶんバグがある)
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
                            ).toIntOrNull() ?: 0) > 0
                        ) {
                            sb.append("一万億兆京垓"[index / 4])
                        }
                    }
                    sb.toString()
                }
                .reversed()
                .joinToString("")
                    + number.dropWhile { it != '.' } // 小数点以下は位がないので別扱いにする
                .map { char ->
                    when (char.code) {
                        in '0'.code..'9'.code -> "〇一二三四五六七八九"[char.code - '0'.code]
                        '.'.code -> '．' // もしかして「・」の方がいい?
                        else -> char
                    }
                }
                .joinToString("")
        )

        else -> str
    }
}

private fun unquote(str: String): String = PAT_QUOTED.findAll(str)
    .joinToString("") { it.groupValues[1] }

private fun unescapeOctal(str: String): String = PAT_ESCAPE_NUM.replace(str) {
    Char(it.value.removePrefix("\\").toInt(8)).toString()
} // emacs-lispのリテラルは8進数

fun processConcatAndMore(rawStr: String, kanjiKey: String): String {
    val numberList = PAT_NUMBER_LIST.findAll(kanjiKey)
        .map { it.value }.toMutableList()

    if (rawStr.startsWith("(concat ") && rawStr.endsWith(")")) {
        return unescapeOctal(unquote(processNumber(rawStr, numberList)))
    }

    operator fun MatchResult.Destructured.component11(): String =
        this.toList()[10] // 不足を拡張
    PAT_CAL.matchEntire(rawStr)?.destructured
        ?.let { (hasArg, argOpts, argTimes, argUnit, _, yearDelta, _, monthDelta, _, dateDelta, raw) ->
            val isDelta = !argOpts.contains('=')
            val sign = if (argOpts.contains('-')) -1 else 1
            val times = if (argOpts.contains('*')) argTimes.toFloatOrNull() ?: 1f else 1f
            val calcDelta = if (!argOpts.contains('*')) {
                (argTimes.toFloatOrNull() ?: 0f) * sign
            } else 0f
            val str = unescapeOctal(unquote(raw))

            var dt = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))

            if (numberList.isNotEmpty()) {
                val numStr = numberList.removeAt(0)
                val num = numStr.toFloat()
                dt = when (argUnit) {
                    "y" -> {
                        if (isDelta) dt.plusYears((num * sign * times).toLong())
                        else dt.withYear((num * times).toInt())
                    }

                    "m" -> {
                        if (isDelta) dt.plusMonths((num * sign * times).toLong())
                        else dt.withMonth((num * times).toInt())
                    }

                    "d" -> {
                        if (isDelta) dt.plusDays((num * sign * times).toLong())
                        else dt.withDayOfMonth((num * times).toInt())
                    }

                    // 単位換算など
                    else -> return processNumber(
                        str,
                        listOf((num * times + calcDelta).toString()) + numberList
                    )
                }
            } else if (hasArg.isNotEmpty()) {
                return str.replace(Regex("[ymd]{1,4}"), "#")
            }

            val oldYear = dt.year
            if ((yearDelta.toIntOrNull() ?: 0) <= -oldYear) {
                return str.replace(Regex("y{1,4}"), "#")
            }

            dt = dt.plusYears((yearDelta.toIntOrNull() ?: 0).toLong())
                .plusMonths((monthDelta.toIntOrNull() ?: 0).toLong())
                .plusDays((dateDelta.toIntOrNull() ?: 0).toLong())

            val dayWeek = dt.dayOfWeek.value % 7 + 1 // Mon(1)..Sun(7) -> Sun(1)..Sat(7)
            val dayWeekStr = "X日月火水木金土"[dayWeek].toString()
            val hour = dt.hour
            val yearStr = dt.year.toString()
            val format = str
                .replace("aaaa", dayWeekStr + "曜日")
                .replace("aaa", dayWeekStr)
                .replace("aa", if (hour < 12) "午前" else "午後")
                .replace(Regex("(?<!y)y(?!y)"), yearStr) // 単独の y で 1 桁を許容するように

            return DateFormat.format(format, java.util.Date(dt.toInstant().toEpochMilli()))
                .toString()
        }

    return processNumber(rawStr, numberList)
}

fun createTrimmedBuilder(orig: StringBuilder): StringBuilder {
    val ret = StringBuilder(orig)
    ret.deleteCharAt(ret.length - 1)
    return ret
}

fun getFileNameFromUri(context: Context, uri: Uri): String? = when (uri.scheme) {
    "content" -> {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
    }

    "file" -> uri.path?.let { File(it).name }
    else -> null
}

@Throws(IOException::class)
internal fun unzipFile(input: InputStream, outDir: File) {
    ZipInputStream(BufferedInputStream(input)).use { zis ->
        while (true) {
            val ze = zis.nextEntry ?: break
            val f = File(outDir, ze.name)
            if (!f.canonicalPath.startsWith(outDir.canonicalPath)) {
                throw IOException("zip path traversal")
            }
            if (ze.isDirectory) {
                f.mkdirs()
            } else {
                f.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(f)).use { bos ->
                    zis.copyTo(bos)
                }
            }
            zis.closeEntry()
        }
    }
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
        0x30F3 to 0x0FF9D, // ン
        0x30F4 to 0x1FF73, // ヴ
        // 0x30F5 to 0x0FF76, // 小書きカ
        // 0x30F6 to 0x0FF79, // 小書きケ
        // 0x30F7 to 0x1FF9C, // ワに濁点
        // 0x30F8 to 0x1FF72, // ヰに濁点
        // 0x30F9 to 0x1FF74, // ヱに濁点
        // 0x30FA to 0x1FF65, // ヲに濁点
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
    0x30F5 to 0x0FF76, // 小書きカ
    0x30F6 to 0x0FF79, // 小書きケ
    0x30EE to 0x0FF9C, // ヮ
    0x30F0 to 0x0FF72, // ヰ
    0x30F1 to 0x0FF74, // ヱ
)

val Z2H = (Z2H_PAIRS + Z2H_OPTIONAL).toMap()
val H2Z = Z2H_PAIRS.associate { (z, h) -> h to z }

private fun stepConv(start: Int, step: Int, number: Int, target: Int): List<Pair<Int, Int>> =
    buildList {
        val other = start + step * (number - 1)
        for (i in start..other step step) {
            add(i to (i - start) / step + target)
        }
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

private val KANA_VARIANTS: Pair<Map<Char, List<Char>>, Map<Char, List<Char>>> by lazy {
    @Suppress("SpellCheckingInspection")
    val kanaGroups = listOf(
        // 各文字の軽い変換
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つづっ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ", "わゎ",
        // 英字で隣接
        "qwa", "wqeas", "ewrsd", "retdf", "tryfg", "ytugh", "uyihj", "iuojk", "oipkl", "pol",
        "aqws", "sweadz", "dersfx", "frtdgc", "gtyfhv", "hyugjb", "juihkn", "kiojlm", "lopk",
        "zsx", "xdzc", "cfxv", "vgcb", "bhvn", "njbm", "mkn"
    ) to listOf(
        // フリックせず押しただけ、「あ」段からの重い変換
        "あいうえおぁぃぅぇぉ",
        "かきくけこがぎぐげご",
        "さしすせそざじずぜぞ",
        "たちつってとだぢづでど", "なにぬねの",
        "はひふへほばびぶべぼぱぴぷぺぽ", "まみむめも",
        "やゃゆゅよょ", "らりるれろ", "わをん",
        // 英字でもう少し広範囲
        "qwa", "wqeas", "ewrsd", "retdf", "tryfg", "ytugh", "uyihj", "iuojk", "oipkl", "pol",
        "aqwsz", "sweadz", "dersfzxc", "frtdgxcv", "gtyfhcvb",
        "hyugjvbn", "juihkbnm", "kiojlnm", "lopkm",
        "zasdx", "xsdfzc", "cdfgxv", "vfghcb", "bghjvn", "nhjkbm", "mjkln"
    )

    fun variant(groups: List<String>): Map<Char, List<Char>> {
        val map = mutableMapOf<Char, List<Char>>()
        for (group in groups) {
            map[group.first()] = group.toList()
        }
        return map
    }
    variant(kanaGroups.first) to variant(kanaGroups.second)
}

internal fun fuzzy(str: String, isFuzzier: Boolean): Sequence<String> {
    if (str.isEmpty()) return sequenceOf()

    fun withCaps(s: String, force: Boolean = false) = sequence {
        yield(s) // そのまま出力
        if (force || isFuzzier) {
            val capitalized = s.replaceFirstChar { it.uppercase() }
            if (capitalized != s) yield(capitalized) // 最初だけ大文字で出力
            val upper = s.uppercase()
            if (upper != s && upper != capitalized) yield(upper) // ぜんぶ大文字にして出力
        }
    }

    val conservativeVariants = str.map { KANA_VARIANTS.first[it] ?: listOf(it) }
    val progressiveVariants = str.map { KANA_VARIANTS.second[it] ?: listOf(it) }

    val fuzzyVariations = sequence {
        yieldAll(withCaps(str, force = true)) // まず typo を探さず大文字だけ調整

        for (typos in 1..str.length) {
            yieldAll(kanaCombo(conservativeVariants, typos).flatMap { withCaps(it) })
            if (isFuzzier)
                yieldAll(kanaCombo(progressiveVariants, typos).flatMap { withCaps(it) })
        }
    }

    return fuzzyVariations.distinct()
}

private fun kanaCombo(lists: List<List<Char>>, tolerance: Int): Sequence<String> {
    if (tolerance == 0) return sequenceOf(lists.map { it[0] }.joinToString(""))
    if (lists.isEmpty()) return sequenceOf()

    return sequence {
        // 最初を変更しないで検索してから、最初を変更して検索
        yieldAll(kanaCombo(lists.drop(1), tolerance).map { lists[0][0] + it })
        if (lists[0].size > 1) for (i in 1 until lists[0].size) {
            val head = lists[0][i] // 最初だけ変更
            yieldAll(kanaCombo(lists.drop(1), tolerance - 1).map { head + it })
        }
    }
}
