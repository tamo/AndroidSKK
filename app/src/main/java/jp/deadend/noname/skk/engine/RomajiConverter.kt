package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.dlog
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.isVowel

object RomajiConverter {
    private val mRomajiMap = mapOf(
        "a"  to "あ", "i"  to "い", "u"  to "う", "e"  to "え", "o"  to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yi" to "い", "yu" to "ゆ", "ye" to "いぇ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wi" to "うぃ", "we" to "うぇ", "wo" to "を", "nn" to "ん",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "va" to "う゛ぁ", "vi" to "う゛ぃ", "vu" to "う゛", "ve" to "う゛ぇ", "vo" to "う゛ぉ",

        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "xtu" to "っ", "xke" to "ヶ",
        "cha" to "ちゃ", "chi" to "ち", "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fu" to "ふ", "fe" to "ふぇ", "fo" to "ふぉ",

        "xya" to "ゃ",   "xyu" to "ゅ",   "xyo" to "ょ", "xwa" to "ゎ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "sha" to "しゃ", "shi" to "し",   "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
        "ja"  to "じゃ", "ji"  to "じ",   "ju"  to "じゅ", "je"  to "じぇ", "jo"  to "じょ",
        "cha" to "ちゃ", "chi" to "ち",   "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tye" to "ちぇ", "tyo" to "ちょ",
        "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
        "dha" to "でゃ", "dhi" to "でぃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",
        "dya" to "ぢゃ", "dyi" to "ぢぃ", "dyu" to "ぢゅ", "dye" to "ぢぇ", "dyo" to "ぢょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "rye" to "りぇ", "ryo" to "りょ",
        "z," to "‥", "z-" to "〜", "z." to "…", "z/" to "・", "z[" to "『",
        "z]" to "』", "zh" to "←", "zj" to "↓", "zk" to "↑", "zl" to "→",
    )

    private val mIntermediateRomajiSet = shortenRomajiSet(mRomajiMap.keys)
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
        "か" to "ゕ", "け" to "ゖ",
        "や" to "ゃ", "ゆ" to "ゅ", "よ" to "ょ", "つ" to "っ", "わ" to "ゎ",
        "ア" to "ァ", "イ" to "ィ", "ウ" to "ゥ", "エ" to "ェ", "オ" to "ォ",
        "カ" to "ヵ", "ケ" to "ヶ",
        "ヤ" to "ャ", "ユ" to "ュ", "ヨ" to "ョ", "ツ" to "ッ", "ワ" to "ヮ",
    )

    private val mReversedSmallKanaMap = mSmallKanaMap.entries.associate{ (l, s) -> s to l}

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

    fun convert(romaji: String) = mRomajiMap[romaji]
    fun getConsonantForVoiced(kana: String): String {
        return when (val c = kana[0].code) {
            'ぁ'.code, 'あ'.code -> "a"
            'ぃ'.code, 'い'.code -> "i"
            'ぅ'.code, 'う'.code -> "u"
            'ぇ'.code, 'え'.code -> "e"
            'ぉ'.code, 'お'.code -> "o"
            in 'か'.code..'ご'.code -> if ((c - 'か'.code) % 2 == 0) "k" else "g"
            in 'さ'.code..'ぞ'.code -> if ((c - 'さ'.code) % 2 == 0) "s" else "z"
            in 'た'.code..'ぢ'.code -> if ((c - 'た'.code) % 2 == 0) "t" else "d"
            'っ'.code -> "t"
            in 'つ'.code..'ど'.code -> if ((c - 'つ'.code) % 2 == 0) "t" else "d"
            in 'な'.code..'の'.code -> "n"
            in 'は'.code..'ぽ'.code -> when ((c - 'は'.code) % 3) {
                0 -> "h"
                1 -> "b"
                else -> "p"
            }
            in 'ま'.code..'も'.code -> "m"
            in 'ゃ'.code..'よ'.code -> "y"
            in 'ら'.code..'ろ'.code -> "r"
            in 'ゎ'.code..'を'.code -> "w"
            'ん'.code -> "n"
            'ゔ'.code -> "v"
            'ゕ'.code, 'ゖ'.code -> "k"
            else -> ""
        }
    }
    fun convertLastChar(str: String, type: String): String? {
        dlog("convertLastChar(str=$str, type=$type)")

        if (str.isEmpty()) return ""
        var first = if (str.lastIndex > 0) str[str.lastIndex - 1].toString() else ""
        val last = str.last()

        val zen = hankaku2zenkaku(first + last)!! // 2文字
        val kana = if (first.isNotEmpty() && zen.length == 1) {
            first = "" // ｶﾞとかﾊﾟ(2文字)からガやパ(1文字)になったので消さないとｶガやﾊパになる
            zen
        } else {
            hankaku2zenkaku(last.toString())!!
        }
        assert(kana.length == 1)

        val kanaLast = kana.last().code
        if (
            type != SKKEngine.LAST_CONVERSION_SHIFT // SHIFTは英数でも可
            && kanaLast !in 0x3041..0x3096 // ひらがな
            && kanaLast !in 0x3099..0x309E // 濁点や記号
            && kanaLast !in 0x30A1..0x30FA // カタカナ
            && kanaLast !in 0x30FD..0x30FE // 「同じ」の記号
        ) {
            dlog("last is not convertible: $last")
            return str
        }
        dlog("first=$first (last=$last), kana=$kana")

        return first + (when (type) {
            SKKEngine.LAST_CONVERSION_SMALL      -> (mSmallKanaMap + mReversedSmallKanaMap)[kana]
            SKKEngine.LAST_CONVERSION_DAKUTEN    -> (mDakutenMap + mReversedDakutenMap)[kana]
                ?: mDakutenMap[mReversedHandakutenMap[kana]]            // 半濁点を濁点に
            SKKEngine.LAST_CONVERSION_HANDAKUTEN -> (mHandakutenMap + mReversedHandakutenMap)[kana]
                ?: mHandakutenMap[mReversedDakutenMap[kana]]            // 濁点を半濁点に
            SKKEngine.LAST_CONVERSION_TRANS      -> mSmallKanaMap[kana] // 普通を小に
                ?: mDakutenMap[mReversedSmallKanaMap[kana]]             // 小を濁点に
                ?: mDakutenMap[kana]                                    // 普通を濁点に
                ?: mHandakutenMap[mReversedDakutenMap[kana]]            // 濁点を半濁点に
                ?: mReversedHandakutenMap[kana]                         // 半濁点を普通に
                ?: mReversedDakutenMap[kana]                            // 濁点を普通に
                ?: mReversedSmallKanaMap[kana]                          // 小文字を普通に
            SKKEngine.LAST_CONVERSION_SHIFT      -> kana
            else -> return null
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
        (first.code == second) -> "っ"
        else -> null
    }

    fun isIntermediateRomaji(composing: String): Boolean = (composing in mIntermediateRomajiSet)
}