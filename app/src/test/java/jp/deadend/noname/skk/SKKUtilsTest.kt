package jp.deadend.noname.skk

import org.junit.Assert.assertEquals
import org.junit.Test

class SKKUtilsTest {

    @Test
    fun testHankaku2Zenkaku() {
        assertEquals("ａｂｃ", hankaku2zenkaku("abc"))
        assertEquals("１２３", hankaku2zenkaku("123"))
        assertEquals("アイウ", hankaku2zenkaku("ｱｲｳ"))
        assertEquals("カキクケコ", hankaku2zenkaku("ｶｷｸｹｺ"))
        assertEquals("ワオン", hankaku2zenkaku("ﾜｵﾝ"))
        assertEquals("ガギグゲゴ", hankaku2zenkaku("ｶﾞｷﾞｸﾞｹﾞｺﾞ"))
        assertEquals("パピプペポ", hankaku2zenkaku("ﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ"))
        assertEquals("ヴ", hankaku2zenkaku("ｳﾞ"))
        assertEquals("－", hankaku2zenkaku("-")) // マイナス
        assertEquals("ー", hankaku2zenkaku("ｰ")) // 長音
        assertEquals(null, hankaku2zenkaku(null))
    }

    @Test
    fun testZenkaku2Hankaku() {
        assertEquals("abc xyz ", zenkaku2hankaku("ａｂｃ　xyz "))
        assertEquals("123890", zenkaku2hankaku("１２３890"))
        assertEquals("ｱｲｳあいう", zenkaku2hankaku("アイウあいう"))
        assertEquals("ｶｷｸｹｺ", zenkaku2hankaku("カキクケコ"))
        assertEquals("ﾜｵﾝ", zenkaku2hankaku("ワオン"))
        assertEquals("ﾜｹ", zenkaku2hankaku("ヮヶ"))
        assertEquals("ｶﾞｷﾞｸﾞｹﾞｺﾞ", zenkaku2hankaku("ガギグゲゴ"))
        assertEquals("ﾊﾟﾋﾟﾌﾟﾍﾟﾎﾟ", zenkaku2hankaku("パピプペポ"))
        assertEquals("ｳﾞ", zenkaku2hankaku("ヴ"))
        assertEquals("ｰ", zenkaku2hankaku("ー")) // 長音
        assertEquals("-", zenkaku2hankaku("－")) // マイナス
        assertEquals(null, zenkaku2hankaku(null))
    }

    @Test
    fun testHiragana2Katakana() {
        assertEquals("アイウアイウ", hiragana2katakana("あいうアイウ"))
        assertEquals("ヴヴ", hiragana2katakana("う゛ヴ"))
        assertEquals("アイウあいう", hiragana2katakana("あいうアイウ", true))
        assertEquals("ヴゔ", hiragana2katakana("う゛ヴ", true))
        assertEquals("ー", hiragana2katakana("ー")) // かなカナの区別なし
        assertEquals(null, hiragana2katakana(null))
    }

    @Test
    fun testKatakana2Hiragana() {
        assertEquals("あいうあいう", katakana2hiragana("アイウあいう"))
        assertEquals("ゔ", katakana2hiragana("ヴ"))
        assertEquals(null, katakana2hiragana(null))
    }

    @Test
    fun testIsAlphabet() {
        assertEquals(true, isAlphabet('a'.code))
        assertEquals(true, isAlphabet('Z'.code))
        assertEquals(false, isAlphabet('1'.code))
    }

    @Test
    fun testIsVowel() {
        assertEquals(true, isVowel('a'.code))
        assertEquals(true, isVowel('u'.code))
        assertEquals(false, isVowel('b'.code))
    }

    @Test
    fun testRemoveAnnotation() {
        assertEquals("変換", removeAnnotation("変換;注釈"))
        assertEquals("単品", removeAnnotation("単品"))
    }

    @Test
    fun testProcessConcatAndMore() {
        assertEquals("第123回45/", processConcatAndMore("(concat \"第#0回#0\\057\")", "だい123かい45すらっしゅ"))
        assertEquals("第１２３回45;", processConcatAndMore("(concat \"第#1回#0\\073\")", "だい123かい45せみころん"))
        assertEquals("第一二三回#", processConcatAndMore("第#2回#0", "だい123かい"))
        assertEquals("第百二十三回", processConcatAndMore("第#3回", "だい123かい"))
        assertEquals("第123回45", processConcatAndMore("(concat \"第#回\" \"#0\")", "だい123かい45"))
        assertEquals("0/0/0/0", processConcatAndMore("(concat \"0\\0570\\0570\\0570\")", "1みりもかんけいないけど"))
    }
}