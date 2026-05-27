package jp.deadend.noname.skk

import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
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
            return
        }

        when (args[0]) {
            "wordlist" -> convertWordList(args[1], args[2], args[3])
            "emoji" -> convertEmoji(args[1], args[2], args[3])
            else -> println("Unknown command: ${args[0]}")
        }
    }

    fun convertWordList(inputPath: String, outputZipPath: String, noticePath: String) {
        val dbBaseName = File(outputZipPath).nameWithoutExtension
        File("$dbBaseName.db").delete()
        File("$dbBaseName.lg").delete()

        println("Converting WordList $inputPath to $outputZipPath...")
        val recMan = RecordManagerFactory.createRecordManager(dbBaseName)
        val btree = BTree<String, String>(recMan, StringComparator())
        recMan.setNamedObject("skk_dict", btree.recordId)

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
                        println(line)
                        return@forEachLine
                    }
                }
                val oldValue = btree.find(lastKey) ?: "/"
                val newValue = "$oldValue$freq/$word/"
                btree.insert(lastKey, newValue, true)
                count++

                if (count % 5000 == 0) {
                    recMan.commit()
                    print(".")
                }
            }
        }
        println("\nProcessed $count word entries.")
        recMan.commit()
        recMan.close()

        zipDatabase(outputZipPath, dbBaseName, noticePath)
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

                //val key = line.substring(0, spaceIdx)
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
            val value = candidates.distinct().reduce { a, c ->
                a + " | " + c.substringAfter(";")
            }
            key to "/$value/"
        }

        val dbBaseName = File(outputZipPath).nameWithoutExtension
        File("$dbBaseName.db").delete()
        File("$dbBaseName.lg").delete()

        val recMan = RecordManagerFactory.createRecordManager(dbBaseName)
        val btree = BTree<String, String>(recMan, StringComparator())
        recMan.setNamedObject("skk_dict", btree.recordId)

        merged.forEach { (key, value) ->
            btree.insert(key, value, true)
        }
        println("Processed $count emoji entries.")
        recMan.commit()
        recMan.close()

        zipDatabase(outputZipPath, dbBaseName, noticePath)
    }

    private fun zipDatabase(zipPath: String, dbBaseName: String, noticePath: String) {
        ZipOutputStream(FileOutputStream(zipPath)).use { zos ->
            listOf("$dbBaseName.db", "$dbBaseName.lg").forEach { fileName ->
                val file = File(fileName)
                if (file.exists()) {
                    zos.putNextEntry(ZipEntry(fileName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    file.delete()
                }
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
