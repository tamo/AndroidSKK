package jp.deadend.noname.skk.engine

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

        "xya" to "ゃ",   "xyu" to "ゅ",   "xyo" to "ょ",
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
        "z]" to "』", "zh" to "←", "zj" to "↓", "zk" to "↑", "zl" to "→"
    )

    private val mConsonantMap = mapOf(
        "が" to "g", "ぎ" to "g", "ぐ" to "g", "げ" to "g", "ご" to "g",
        "か" to "k", "き" to "k", "く" to "k", "け" to "k", "こ" to "k",
        "ざ" to "z", "じ" to "z", "ず" to "z", "ぜ" to "z", "ぞ" to "z",
        "さ" to "s", "し" to "s", "す" to "s", "せ" to "s", "そ" to "s",
        "だ" to "d", "ぢ" to "d", "づ" to "d", "で" to "d", "ど" to "d",
        "た" to "t", "ち" to "t", "つ" to "t", "て" to "t", "と" to "t",
        "ば" to "b", "び" to "b", "ぶ" to "b", "べ" to "b", "ぼ" to "b",
        "ぱ" to "p", "ぴ" to "p"," ぷ" to "p", "ぺ" to "p", "ぽ" to "p",
        "は" to "h", "ひ" to "h", "ふ" to "h", "へ" to "h", "ほ" to "h"
    )

    private val mSmallKanaMap = mapOf(
        "あ" to "ぁ", "い" to "ぃ", "う" to "ぅ", "え" to "ぇ", "お" to "ぉ",
        "ぁ" to "あ", "ぃ" to "い", "ぅ" to "う", "ぇ" to "え", "ぉ" to "お",
        "や" to "ゃ", "ゆ" to "ゅ", "よ" to "ょ", "つ" to "っ",
        "ゃ" to "や", "ゅ" to "ゆ", "ょ" to "よ", "っ" to "つ",
        "ア" to "ァ", "イ" to "ィ", "ウ" to "ゥ", "エ" to "ェ", "オ" to "ォ",
        "ァ" to "ア", "ィ" to "イ", "ゥ" to "ウ", "ェ" to "エ", "ォ" to "オ",
        "ヤ" to "ャ", "ユ" to "ュ", "ヨ" to "ョ", "ツ" to "ッ",
        "ャ" to "ヤ", "ュ" to "ユ", "ョ" to "ヨ", "ッ" to "ツ"
    )

    private val mDakutenMap = mapOf(
        "か" to "が", "き" to "ぎ", "く" to "ぐ", "け" to "げ", "こ" to "ご",
        "が" to "か", "ぎ" to "き", "ぐ" to "く", "げ" to "け", "ご" to "こ",
        "さ" to "ざ", "し" to "じ", "す" to "ず", "せ" to "ぜ", "そ" to "ぞ",
        "ざ" to "さ", "じ" to "し", "ず" to "す", "ぜ" to "せ", "ぞ" to "そ",
        "た" to "だ", "ち" to "ぢ", "つ" to "づ", "て" to "で", "と" to "ど",
        "だ" to "た", "ぢ" to "ち", "づ" to "つ", "で" to "て", "ど" to "と",
        "は" to "ば", "ひ" to "び", "ふ" to "ぶ", "へ" to "べ", "ほ" to "ぼ",
        "ば" to "は", "び" to "ひ", "ぶ" to "ふ", "べ" to "へ", "ぼ" to "ほ",
        "カ" to "ガ", "キ" to "ギ", "ク" to "グ", "ケ" to "ゲ", "コ" to "ゴ",
        "ガ" to "カ", "ギ" to "キ", "グ" to "ク", "ゲ" to "ケ", "ゴ" to "コ",
        "サ" to "ザ", "シ" to "ジ", "ス" to "ズ", "セ" to "セ", "ソ" to "ゾ",
        "ザ" to "サ", "ジ" to "シ", "ズ" to "ス", "ゼ" to "ゼ", "ゾ" to "ソ",
        "タ" to "ダ", "チ" to "ヂ", "ツ" to "ヅ", "テ" to "デ", "ト" to "ド",
        "ダ" to "タ", "ヂ" to "チ", "ヅ" to "ツ", "デ" to "テ", "ド" to "ト",
        "ハ" to "バ", "ヒ" to "ビ", "フ" to "ブ", "ヘ" to "ベ", "ホ" to "ボ",
        "バ" to "ハ", "ビ" to "ヒ", "ブ" to "フ", "ベ" to "ヘ", "ボ" to "ホ",
        "ウ" to "ヴ", "ヴ" to "ウ"
    )

    private val mHandakutenMap = mapOf(
        "は" to "ぱ", "ひ" to "ぴ", "ふ" to "ぷ", "へ" to "ぺ", "ほ" to "ぽ",
        "ぱ" to "は", "ぴ" to "ひ", "ぷ" to "ふ", "ぺ" to "へ", "ぽ" to "ほ",
        "ハ" to "パ", "ヒ" to "ピ", "フ" to "プ", "ヘ" to "ペ", "ホ" to "ポ",
        "パ" to "ハ", "ピ" to "ヒ", "プ" to "フ", "ペ" to "ヘ", "ポ" to "ホ"
    )

    fun convert(romaji: String) = mRomajiMap[romaji]
    fun getConsonantForVoiced(kana: String) = mConsonantMap[kana]
    fun convertLastChar(kana: String, type: String) = when (type) {
        SKKEngine.LAST_CONVERTION_SMALL      -> mSmallKanaMap[kana]
        SKKEngine.LAST_CONVERTION_DAKUTEN    -> mDakutenMap[kana]
        SKKEngine.LAST_CONVERTION_HANDAKUTEN -> mHandakutenMap[kana]
        else -> null
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
}