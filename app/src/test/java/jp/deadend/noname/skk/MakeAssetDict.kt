package jp.deadend.noname.skk

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MakeAssetDict {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: MakeAssetDict <command> [args...]")
            println("Commands:")
            println("  wordlist <input_combined> <output_zip> <notice_file>")
            println("  emoji <input_jisyo> <output_zip> <notice_file>")
            println("  symbol <output_jisyo> <output_zip>")
            return
        }

        when (args[0]) {
            "wordlist" -> convertWordList(args[1], args[2], args[3])
            "emoji" -> convertEmoji(args[1], args[2], args[3])
            "symbol" -> convertSymbol(args[1], args[2])
            else -> println("Unknown command: ${args[0]}")
        }
    }

    fun convertWordList(inputPath: String, outputZipPath: String, noticePath: String) {
        val dbBaseName = File(outputZipPath).nameWithoutExtension
        val mvFileName = "$dbBaseName.mv"
        File(mvFileName).delete()

        println("Converting WordList $inputPath to $outputZipPath...")
        val store = MVStoreStore.open(mvFileName, "skk_dict")

        var lastKey: String? = null
        var lastFreq: String? = null
        var count = 0

        File(inputPath).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val (word, freq) = when {
                    line.startsWith(" word=") -> {
                        val parts = line.split(",")
                        val word = parts.find { it.startsWith(" word=") }?.substringAfter("word=")
                        val freq = parts.find { it.startsWith("f=") }?.substringAfter("f=") ?: "0"
                        val isNotAWord = "not_a_word=true" in parts

                        if (word == null) {
                            println("Error parsing the line: $line")
                            return@forEachLine
                        }

                        lastKey = word
                        lastFreq = freq
                        if (isNotAWord || freq == "0") return@forEachLine

                        word to freq
                    }

                    line.startsWith("  shortcut=") -> {
                        val parts = line.split(",")
                        val word =
                            parts.find { it.startsWith("  shortcut=") }?.substringAfter("shortcut=")
                        val freq = parts.find { it.startsWith("f=") }?.substringAfter("f=")
                            ?.toIntOrNull()?.toString() ?: lastFreq

                        if (lastKey == null || word == null || freq == null) {
                            println("Error parsing the line: $line")
                            return@forEachLine
                        }

                        word to freq
                    }

                    else -> {
                        return@forEachLine
                    }
                }
                val oldValue = store.get(lastKey) ?: "/"
                val newValue = "$oldValue$freq/$word/"
                store.set(lastKey, newValue)
                count++

                if (count % 5000 == 0) {
                    store.commit()
                    print(".")
                }
            }
        }
        println("\nProcessed $count word entries.")
        store.commit()
        store.close()

        zipDatabase(outputZipPath, mvFileName, noticePath)
    }

    fun convertEmoji(inputPath: String, outputZipPath: String, noticePath: String) {
        println("Converting Emoji source $inputPath to $outputZipPath...")
        val entries = mutableMapOf<String, MutableList<String>>()
        var count = 0

        File(inputPath).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                if (line.trimStart().startsWith(";")) return@forEachLine

                val spaceIdx = line.indexOf(' ')
                if (spaceIdx == -1) return@forEachLine

                val candidatesPart = line.substring(spaceIdx + 1).trim('/')
                val candidates = candidatesPart.split('/')

                candidates.forEach { candidate ->
                    val parts = candidate.split(';')
                    if (parts.size != 3) {
                        println("Error parsing line: $line")
                        return@forEachLine
                    }
                    val emoji = parts[0]
                    val key = parts[1]
                    val keywords = parts[2].replace(",", " | ")
                    val converted = "50/$emoji;$keywords"
                    entries.getOrPut(key) { mutableListOf() }.add(converted)
                    count++
                }
            }
        }
        val merged = entries.map { (key, candidates) ->
            val value = candidates.distinct().joinToString(" | ") { it.substringAfter(";") }
            key to "/$value/"
        }

        val dbBaseName = File(outputZipPath).nameWithoutExtension
        val mvFileName = "$dbBaseName.mv"
        File(mvFileName).delete()

        val store = MVStoreStore.open(mvFileName, "skk_dict")

        merged.forEach { (key, value) ->
            store.set(key, value)
        }
        println("Processed $count emoji entries.")
        store.commit()
        store.close()

        zipDatabase(outputZipPath, mvFileName, noticePath)
    }

    private fun convertSymbol(outputPath: String, outputZipPath: String, noticePath: String = "") {
        createSymbolSKK(outputPath)

        println("Converting Symbol source $outputPath to $outputZipPath...")
        val entries = mutableListOf<Pair<String, String>>()
        var count = 0

        File(outputPath).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                if (line.trimStart().startsWith(";")) return@forEachLine

                val key = line.substringBefore(' ')
                if (key == line) return@forEachLine
                val value = line.substringAfter(' ')

                entries.add(key to value)
                count++
            }
        }

        val dbBaseName = File(outputZipPath).nameWithoutExtension
        val mvFileName = "$dbBaseName.mv"
        File(mvFileName).delete()

        val store = MVStoreStore.open(mvFileName, "skk_dict")

        entries.forEach { (key, value) -> store.set(key, value) }
        println("Processed $count symbol entries.")
        store.commit()
        store.close()

        zipDatabase(outputZipPath, mvFileName, noticePath)
    }

    private fun createSymbolSKK(outputPath: String) {
        data class Category(val name: String, val ranges: List<IntRange>)

        val categories = listOf(
            Category(
                "00.半角", listOf(
                    0x0021..0x002F, // ( ! " # $ % & ' ( ) * + , - . / )
                    0x003A..0x0040, // ( : ; < = > ? @ )
                    0x005B..0x0060, // ( [ \ ] ^ _ ` )
                    0x007B..0x007E, // ( { | } ~ )
                    0x00A1..0x00AC, 0x00AE..0x00BF, // ラテン
                )
            ),
            Category(
                "01.点と括弧", listOf(
                    0xFF01..0xFF0F, 0xFF1A..0xFF20, // 全角記号
                    0xFF3B..0xFF40, 0xFF5B..0xFF5E, // 全角記号
                    0x3000..0x3003, 0x3005..0x3005, // CJKの読点・句点・々
                    0x3008..0x301F, // CJK用の主要な括弧類（「」『』【】、引用符等）
                    0xFF08..0xFF09, 0xFF3B..0xFF3D, 0xFF5B..0xFF5D, // 丸、角、波括弧
                    0x309B..0x309E, // 濁点・半濁点等
                    0x30FB..0x30FE, // カタカナ中黒（・）等
                    0x2010..0x205F, // 一般句読点ブロック（ハイフン、中黒、空白系等）
                    0x3004..0x303F, // その他の記号 (重複は除去される)
                    0x3220..0x3247, 0x3280..0x32B0, // 囲み記号
                    0xFE30..0xFE4F, // 縦書き用句読点、下線、装飾波線
                    0x2768..0x2775, // 装飾括弧
                )
            ),
            Category(
                "02.単位", listOf(
                    0x2030..0x2037, 0x2057..0x2057, // パーミル等
                    0x20A0..0x20CF, // 通貨記号
                    // 0x20D0~20FFの「結合文字」をスキップ
                    0x2100..0x214F, // 文字様記号 (℃, ℉, ℡, ℆ 等)
                    0x3302..0x3357, // 小さい文字の日本語単位
                    0x3371..0x337F, 0x32FF..0x32FF, 0x3380..0x33DF, // CJK
                    0xFF04..0xFF05, 0xFFE0..0xFFE6, // 全角の通貨・パーセント記号（＄, ％, ￥）
                )
            ),
            Category(
                "03.数字", listOf(
                    0x2460..0x2473, 0x3251..0x325F, 0x32B1..0x32BF, // 丸付き数字
                    0x2776..0x277F, 0x24EB..0x24FF, 0x2780..0x2793, // 丸付き数字
                    0x2474..0x249B, // 囲み英数字
                    0x3358..0x3370, // 0から24点
                    0x32C0..0x32CB, 0x33E0..0x33FE, // 月日
                    0x2150..0x2189, // ローマ数字等
                )
            ),
            Category(
                "04.矢印", listOf(
                    0x2190..0x21FF, // 矢印ブロック
                    0x2794..0x27BF, // 補足矢印・装飾矢印ブロック
                    0x2B30..0x2B4F, // 数学矢印
                    0x2B5A..0x2B95, // その他
                )
            ),
            Category(
                "05.数学", listOf(
                    0x2200..0x22FF, // 数学記号ブロック全般（∀, ∃, ∑ 等）
                    0x2715..0x2716, 0x2795..0x2797, // 絵文字演算子
                    0x2A00..0x2AFF, // 補足数学記号（大型演算子等）
                    0x2B30..0x2B4F, // 数学矢印
                    0xFF0B..0xFF0D, 0xFF1C..0xFF1E, // 演算子（＋, －, ＜, ＝, ＞）
                )
            ),
            Category(
                "06.技術", listOf(
                    0x2300..0x23FF, 0x3004..0x3004,
                )
            ),
            Category(
                "07.図形", listOf(
                    0x2500..0x25FF, // 罫線と幾何学図形ブロック全般
                    0x2B00..0x2BFF, // 補足幾何学図形
                )
            ),
            Category("08.装飾", listOf(0x2600..0x2767)),
            Category(
                "09.外国", listOf(
                    0x00C0..0x017F, // ラテン
                    0x0370..0x03FF, // ギリシャ
                    0x0400..0x04FF, // キリル
                    0x0250..0x02AF, // IPA 拡張
                )
            ),
            Category(
                "10.装飾英字", listOf(
                    0x1D468..0x1D49B, // 太字斜体 (𝑩𝑰𝑮 𝑳𝑶𝑽𝑬...)
                    0x1D4D0..0x1D503, // 太字筆記体 (𝓑𝓘𝓖 𝓛𝓞𝓥𝓔...)
                    0x1D538..0x1D56B, // 白抜き (𝔻𝕠𝕦𝕓𝕝𝕖-𝕤𝕥𝕣𝕦𝕔𝕜)
                    0x1D56C..0x1D59F, // フラクトゥール (𝔅𝔩𝔞𝔠𝔨𝔩𝔢𝔱𝔱𝔢𝔯)
                )
            ),
        )

        File(outputPath).bufferedWriter().use { writer ->
            writer.write(";; Okuri-nasi entries\n")

            for (cat in categories) {
                val sb = StringBuilder()
                sb.append(cat.name).append(" ")

                val seen = mutableSetOf<Int>()
                for (range in cat.ranges) for (code in range) seen.add(code)

                for (code in seen) {
                    val char = String(Character.toChars(code))
                    if (char == "/" || char == "\\") continue
                    when (Character.getType(code).toByte()) {
                        Character.UNASSIGNED, Character.CONTROL, Character.FORMAT,
                        Character.PRIVATE_USE, Character.SURROGATE -> continue

                        else if Character.isWhitespace(code) -> continue
                    }

                    sb.append("/").append(char)
                        .append(";").append(String.format("U+%04X", code))
                        .append(" ").append(Character.getName(code).orEmpty())
                }
                sb.append("/\n")
                writer.write(sb.toString())
            }
        }
        println("Generated Symbol SKK dictionary at: $outputPath")
    }

    private fun zipDatabase(zipPath: String, mvFileName: String, noticePath: String) {
        ZipOutputStream(FileOutputStream(zipPath)).use { zos ->
            val file = File(mvFileName)
            if (file.exists()) {
                zos.putNextEntry(ZipEntry(mvFileName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                file.delete()
            }
            val noticeFile = File(noticePath)
            if (noticeFile.exists()) {
                zos.putNextEntry(ZipEntry(noticeFile.name))
                noticeFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        println("Generated $zipPath")
    }
}
