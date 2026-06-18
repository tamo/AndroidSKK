package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKApplication
import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.isAlNum
import jp.deadend.noname.skk.isAnyKana
import jp.deadend.noname.skk.isDakuten
import jp.deadend.noname.skk.isVowel
import jp.deadend.noname.skk.katakana2hiragana

object RomajiConverter {
    private var mRomajiMap: Map<String, String> = emptyMap()
    private var mIntermediateRomajiSet: Set<String> = emptySet()
    private var mVowelMap: Map<String, Char> = emptyMap()

    fun loadRules(rules: Map<String, String>?) {
        mRomajiMap = rules ?: emptyMap()
        mIntermediateRomajiSet = shortenRomajiSet(mRomajiMap.keys)
        mVowelMap = createVowelMap(mRomajiMap)
    }

    private fun shortenRomajiSet(set: Set<String>): Set<String> {
        val shorter = set
            .filter { it.length > 1 }
            .map { it.substring(0, it.lastIndex) }
            .toSet()
        if (shorter.isEmpty()) return shorter
        return shorter + shortenRomajiSet(shorter)
    } // cha を ch にしただけだと c が無効なので少なくとも 2 回は実行しなければいけない

    private val mSmallKanaMap = mapOf(
        "あ" to "ぁ", "い" to "ぃ", "う" to "ぅ", "え" to "ぇ", "お" to "ぉ",
        "や" to "ゃ", "ゆ" to "ゅ", "よ" to "ょ", "つ" to "っ", "わ" to "ゎ",
        "ア" to "ァ", "イ" to "ィ", "ウ" to "ゥ", "エ" to "ェ", "オ" to "ォ",
        "ヤ" to "ャ", "ユ" to "ュ", "ヨ" to "ョ", "ツ" to "ッ", "ワ" to "ヮ",
    )
    private val mSmallKMap = mapOf(
        "か" to "ゕ", "け" to "ゖ",
        "カ" to "ヵ", "ケ" to "ヶ",
    )

    private val mReversedSmallKanaMap = mSmallKanaMap.plus(mSmallKMap)
        .entries.associate { (l, s) -> s to l }

    private val mDakutenMap = mapOf(
        "う" to "ゔ",
        "か" to "が", "き" to "ぎ", "く" to "ぐ", "け" to "げ", "こ" to "ご",
        "さ" to "ざ", "し" to "じ", "す" to "ず", "せ" to "ぜ", "そ" to "ぞ",
        "た" to "だ", "ち" to "ぢ", "つ" to "づ", "て" to "で", "と" to "ど",
        "は" to "ば", "ひ" to "び", "ふ" to "ぶ", "へ" to "べ", "ほ" to "ぼ",
        "ゝ" to "ゞ",
        "ウ" to "ヴ",
        "カ" to "ガ", "キ" to "ギ", "ク" to "グ", "ケ" to "ゲ", "コ" to "ゴ",
        "サ" to "ザ", "シ" to "ジ", "ス" to "ズ", "セ" to "セ", "ソ" to "ゾ",
        "タ" to "ダ", "チ" to "ヂ", "ツ" to "ヅ", "テ" to "デ", "ト" to "ド",
        "ハ" to "バ", "ヒ" to "ビ", "フ" to "ブ", "ヘ" to "ベ", "ホ" to "ボ",
        "ワ" to "ヷ", "ヰ" to "ヸ", "ヱ" to "ヹ", "ヲ" to "ヺ", "ヽ" to "ヾ",
    )

    private val mReversedDakutenMap = mDakutenMap.entries.associate { (n, d) -> d to n } +
            mapOf("゛" to "", "゙" to "") // 濁点は2種類あるらしい 3099, 309B

    private val mHandakutenMap = mapOf(
        "は" to "ぱ", "ひ" to "ぴ", "ふ" to "ぷ", "へ" to "ぺ", "ほ" to "ぽ",
        "ハ" to "パ", "ヒ" to "ピ", "フ" to "プ", "ヘ" to "ペ", "ホ" to "ポ",
    )

    private val mReversedHandakutenMap = mHandakutenMap.entries.associate { (h, p) -> p to h } +
            mapOf("゜" to "", "゚" to "") // 半濁点も2種類 309A, 309C

    fun convert(romaji: String) = mRomajiMap[romaji].orEmpty()
    fun getConsonantForVoiced(kana: String): String {
        val hiragana = katakana2hiragana(hankaku2zenkaku(kana))
        if (hiragana.isEmpty()) return ""
        return when (val c = hiragana[0]) {
            'ぁ', 'あ' -> "a"
            'ぃ', 'い' -> "i"
            'ぅ' -> "u"
            'う' -> if (hiragana.length > 1 && isDakuten(hiragana[1].code)) "v" else "u"
            'ぇ', 'え' -> "e"
            'ぉ', 'お' -> "o"
            in 'か'..'ご' -> if ((c - 'か') % 2 == 0) "k" else "g"
            in 'さ'..'ぞ' -> if ((c - 'さ') % 2 == 0) "s" else "z"
            in 'た'..'ぢ' -> if ((c - 'た') % 2 == 0) "t" else "d"
            'っ' -> "t"
            in 'つ'..'ど' -> if ((c - 'つ') % 2 == 0) "t" else "d"
            in 'な'..'の' -> "n"
            in 'は'..'ぽ' -> when ((c - 'は') % 3) {
                0 -> "h"
                1 -> "b"
                else -> "p"
            }

            in 'ま'..'も' -> "m"
            in 'ゃ'..'よ' -> "y"
            in 'ら'..'ろ' -> "r"
            in 'ゎ'..'を' -> "w"
            'ん' -> "n"
            'ゔ' -> "v"
            'ゕ', 'ゖ' -> "k"
            else -> ""
        }
    }

    fun getVowel(kana: String): Char? =
        mVowelMap[katakana2hiragana(hankaku2zenkaku(kana))]

    private fun createVowelMap(map: Map<String, String>): Map<String, Char> =
        map.entries.associate { (r, k) -> k to r.last() }

    fun convertLastChar(str: String, type: String): Pair<String, String> {
        SKKLog.d("convertLastChar(str=$str, type=$type)")

        if (str.isEmpty()) return "" to "" // str が 0 文字の場合
        var first = if (str.lastIndex > 0) str[str.lastIndex - 1].toString() else "" // 1 文字の場合
        val last = str.last()

        val zen = checkNotNull(hankaku2zenkaku(first + last))
        val kana = if (first.isNotEmpty() && zen.length == 1) {
            first = "" // ｶﾞとかﾊﾟ(2文字)からガやパ(1文字)になったので消さないとｶガやﾊパになる
            zen
        } else if (type == SKKEngine.LAST_CONVERSION_SHIFT && isAlNum(last.code)) {
            last.toString() // 英数SHIFTは全角にしないで使う
        } else {
            checkNotNull(hankaku2zenkaku(last.toString()))
        }
        assert(kana.length == 1)

        val kanaLast = kana.last().code
        if (
            type != SKKEngine.LAST_CONVERSION_SHIFT // SHIFTは英数でも可
            && !isAnyKana(kanaLast)
        ) {
            SKKLog.d("last is not convertible: $last")
            return first to last.toString()
        }
        SKKLog.d("first=$first (last=$last), kana=$kana")

        return first to (when (type) {
            SKKEngine.LAST_CONVERSION_SMALL -> (mSmallKanaMap + mSmallKMap + mReversedSmallKanaMap)[kana]
            SKKEngine.LAST_CONVERSION_DAKUTEN -> (mDakutenMap + mReversedDakutenMap)[kana]
                ?: mDakutenMap[mReversedHandakutenMap[kana]]            // 半濁点を濁点に
            SKKEngine.LAST_CONVERSION_HANDAKUTEN -> (mHandakutenMap + mReversedHandakutenMap)[kana]
                ?: mHandakutenMap[mReversedDakutenMap[kana]]            // 濁点を半濁点に
            // useSmallK は既定で false
            SKKEngine.LAST_CONVERSION_TRANS -> (if (SKKApplication.prefs?.useSmallK == true) mSmallKMap[kana] else null)
                ?: mSmallKanaMap[kana]                                  // 普通を小に
                ?: mDakutenMap[mReversedSmallKanaMap[kana]]             // 小を濁点に
                ?: mDakutenMap[kana]                                    // 普通を濁点に
                ?: mHandakutenMap[mReversedDakutenMap[kana]]            // 濁点を半濁点に
                ?: mReversedHandakutenMap[kana]                         // 半濁点を普通に
                ?: mReversedDakutenMap[kana]                            // 濁点を普通に
                ?: mReversedSmallKanaMap[kana]                          // 小文字を普通に
            SKKEngine.LAST_CONVERSION_SHIFT -> kana
            else -> throw IllegalArgumentException("convertLastChar: unknown type $type")
        } ?: kana)
    }

    // 1文字目と2文字目を合わせて"ん"・"っ"になるか判定
    // ならなかったらnull
    fun checkSpecialConsonants(first: Char, second: Int) = when {
        (first == 'n') -> if (!isVowel(second) && second != 'n'.code && second != 'y'.code) {
            "ん"
        } else {
            null
        }

        (isIntermediateRomaji(first.toString()) && first.code == second) -> "っ"
        else -> null
    }

    fun isIntermediateRomaji(composing: String): Boolean = (composing in mIntermediateRomajiSet)
}
