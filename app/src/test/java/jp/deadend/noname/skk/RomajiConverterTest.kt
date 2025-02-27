package jp.deadend.noname.skk

import jp.deadend.noname.skk.engine.RomajiConverter
import jp.deadend.noname.skk.engine.SKKEngine.Companion.LAST_CONVERSION_DAKUTEN
import jp.deadend.noname.skk.engine.SKKEngine.Companion.LAST_CONVERSION_HANDAKUTEN
import jp.deadend.noname.skk.engine.SKKEngine.Companion.LAST_CONVERSION_SHIFT
import jp.deadend.noname.skk.engine.SKKEngine.Companion.LAST_CONVERSION_SMALL
import jp.deadend.noname.skk.engine.SKKEngine.Companion.LAST_CONVERSION_TRANS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiConverterTest {

    @Test
    fun testConvert() {
        assertEquals("", RomajiConverter.convert(""))
        assertEquals("あ", RomajiConverter.convert("a"))
        assertEquals("か", RomajiConverter.convert("ka"))
        assertEquals("きゃ", RomajiConverter.convert("kya"))
        assertEquals("ちゃ", RomajiConverter.convert("cha"))
        assertEquals("", RomajiConverter.convert("n")) // 未確定
        assertEquals("ん", RomajiConverter.convert("nn"))
        assertEquals("う゛ぁ", RomajiConverter.convert("va")) // ゔぁ ではない
        assertEquals("‥", RomajiConverter.convert("z,"))
        assertEquals("〜", RomajiConverter.convert("z-"))
        assertEquals("…", RomajiConverter.convert("z."))
        assertEquals("・", RomajiConverter.convert("z/"))
        assertEquals("『", RomajiConverter.convert("z["))
        assertEquals("』", RomajiConverter.convert("z]"))
        assertEquals("←", RomajiConverter.convert("zh"))
        assertEquals("↓", RomajiConverter.convert("zj"))
        assertEquals("↑", RomajiConverter.convert("zk"))
        assertEquals("→", RomajiConverter.convert("zl"))
        assertEquals("", RomajiConverter.convert("an")) // a が来るわけない
        assertEquals("", RomajiConverter.convert("ca")) // c が残るわけない
    }

    @Test
    fun testGetConsonantForVoiced() {
        assertEquals("", RomajiConverter.getConsonantForVoiced(""))
        assertEquals("a", RomajiConverter.getConsonantForVoiced("あ"))
        assertEquals("i", RomajiConverter.getConsonantForVoiced("い"))
        assertEquals("u", RomajiConverter.getConsonantForVoiced("う"))
        assertEquals("e", RomajiConverter.getConsonantForVoiced("え"))
        assertEquals("o", RomajiConverter.getConsonantForVoiced("お"))
        assertEquals("k", RomajiConverter.getConsonantForVoiced("か"))
        assertEquals("g", RomajiConverter.getConsonantForVoiced("ぎ"))
        assertEquals("s", RomajiConverter.getConsonantForVoiced("さ"))
        assertEquals("z", RomajiConverter.getConsonantForVoiced("ぞ"))
        assertEquals("t", RomajiConverter.getConsonantForVoiced("た"))
        assertEquals("d", RomajiConverter.getConsonantForVoiced("ぢ"))
        assertEquals("t", RomajiConverter.getConsonantForVoiced("っ")) // 注意
        assertEquals("d", RomajiConverter.getConsonantForVoiced("づ"))
        assertEquals("n", RomajiConverter.getConsonantForVoiced("の"))
        assertEquals("h", RomajiConverter.getConsonantForVoiced("は"))
        assertEquals("b", RomajiConverter.getConsonantForVoiced("び"))
        assertEquals("p", RomajiConverter.getConsonantForVoiced("ぽ"))
        assertEquals("m", RomajiConverter.getConsonantForVoiced("ま"))
        assertEquals("y", RomajiConverter.getConsonantForVoiced("ょ"))
        assertEquals("r", RomajiConverter.getConsonantForVoiced("ろ"))
        assertEquals("w", RomajiConverter.getConsonantForVoiced("を"))
        assertEquals("n", RomajiConverter.getConsonantForVoiced("ん"))
        assertEquals("v", RomajiConverter.getConsonantForVoiced("ゔ"))
        assertEquals("v", RomajiConverter.getConsonantForVoiced("う゛")) // u ではない
        assertEquals("a", RomajiConverter.getConsonantForVoiced("ア"))
        assertEquals("g", RomajiConverter.getConsonantForVoiced("ギ"))
        assertEquals("g", RomajiConverter.getConsonantForVoiced("ｸﾞ"))
        assertEquals("k", RomajiConverter.getConsonantForVoiced("ゕ"))
        assertEquals("", RomajiConverter.getConsonantForVoiced("invalid"))
    }

    @Test
    fun testGetVowel() {
        assertEquals(null, RomajiConverter.getVowel(""))
        assertEquals('a', RomajiConverter.getVowel("ゃ"))
        assertEquals('i', RomajiConverter.getVowel("ぃ"))
        assertEquals('u', RomajiConverter.getVowel("ゅ"))
        assertEquals('e', RomajiConverter.getVowel("ぇ"))
        assertEquals('o', RomajiConverter.getVowel("ょ"))
        assertEquals('a', RomajiConverter.getVowel("ャ"))
        assertEquals('i', RomajiConverter.getVowel("ィ"))
        assertEquals('u', RomajiConverter.getVowel("ュ"))
        assertEquals('e', RomajiConverter.getVowel("ェ"))
        assertEquals('o', RomajiConverter.getVowel("ョ"))
        assertEquals('a', RomajiConverter.getVowel("あ"))
        assertEquals('i', RomajiConverter.getVowel("い"))
        assertEquals('u', RomajiConverter.getVowel("う"))
        assertEquals('e', RomajiConverter.getVowel("え"))
        assertEquals('o', RomajiConverter.getVowel("お"))
        assertEquals('a', RomajiConverter.getVowel("か"))
        assertEquals('i', RomajiConverter.getVowel("き"))
        assertEquals('u', RomajiConverter.getVowel("く"))
        assertEquals('e', RomajiConverter.getVowel("け"))
        assertEquals('o', RomajiConverter.getVowel("こ"))
        assertEquals('a', RomajiConverter.getVowel("や"))
        assertEquals('u', RomajiConverter.getVowel("ゆ"))
        assertEquals('o', RomajiConverter.getVowel("よ"))
        assertEquals('a', RomajiConverter.getVowel("わ"))
        assertEquals('o', RomajiConverter.getVowel("を"))

        assertEquals('n', RomajiConverter.getVowel("ん")) // 注意
        assertEquals(null, RomajiConverter.getVowel("invalid"))
    }

    @Test
    fun testConvertLastChar() {
        // LAST_CONVERSION_SMALL
        assertEquals("" to "", RomajiConverter.convertLastChar("", LAST_CONVERSION_SMALL))
        assertEquals("" to "ぁ", RomajiConverter.convertLastChar("あ", LAST_CONVERSION_SMALL))
        assertEquals("" to "あ", RomajiConverter.convertLastChar("ぁ", LAST_CONVERSION_SMALL))
        assertEquals("" to "ゕ", RomajiConverter.convertLastChar("か", LAST_CONVERSION_SMALL))
        assertEquals("" to "か", RomajiConverter.convertLastChar("ゕ", LAST_CONVERSION_SMALL))
        assertEquals("" to "が", RomajiConverter.convertLastChar("が", LAST_CONVERSION_SMALL))
        assertEquals("" to "ゃ", RomajiConverter.convertLastChar("や", LAST_CONVERSION_SMALL))
        assertEquals("" to "っ", RomajiConverter.convertLastChar("つ", LAST_CONVERSION_SMALL))
        assertEquals("" to "ァ", RomajiConverter.convertLastChar("ア", LAST_CONVERSION_SMALL))
        assertEquals("" to "ヵ", RomajiConverter.convertLastChar("カ", LAST_CONVERSION_SMALL))
        assertEquals("" to "ガ", RomajiConverter.convertLastChar("ｶﾞ", LAST_CONVERSION_SMALL))
        assertEquals("" to "ヵ", RomajiConverter.convertLastChar("ｶ", LAST_CONVERSION_SMALL))
        // 2 文字を前提として first と last で見ている
        assertEquals("i" to "d", RomajiConverter.convertLastChar("invalid", LAST_CONVERSION_SMALL))

        // LAST_CONVERSION_DAKUTEN
        assertEquals("か" to "が", RomajiConverter.convertLastChar("かか", LAST_CONVERSION_DAKUTEN))
        assertEquals("か" to "ば", RomajiConverter.convertLastChar("かは", LAST_CONVERSION_DAKUTEN))
        assertEquals("か" to "は", RomajiConverter.convertLastChar("かば", LAST_CONVERSION_DAKUTEN))
        assertEquals("か" to "ゞ", RomajiConverter.convertLastChar("かゝ", LAST_CONVERSION_DAKUTEN))
        assertEquals("カ" to "ガ", RomajiConverter.convertLastChar("カカ", LAST_CONVERSION_DAKUTEN))
        assertEquals("カ" to "バ", RomajiConverter.convertLastChar("カハ", LAST_CONVERSION_DAKUTEN))
        assertEquals("カ" to "ヾ", RomajiConverter.convertLastChar("カヽ", LAST_CONVERSION_DAKUTEN))

        // LAST_CONVERSION_HANDAKUTEN
        assertEquals("" to "ぱ", RomajiConverter.convertLastChar("は", LAST_CONVERSION_HANDAKUTEN))
        assertEquals("" to "は", RomajiConverter.convertLastChar("ぱ", LAST_CONVERSION_HANDAKUTEN))
        assertEquals("" to "パ", RomajiConverter.convertLastChar("ハ", LAST_CONVERSION_HANDAKUTEN))

        // LAST_CONVERSION_TRANS
        assertEquals("" to "ぁ", RomajiConverter.convertLastChar("あ", LAST_CONVERSION_TRANS))
        assertEquals("" to "あ", RomajiConverter.convertLastChar("ぁ", LAST_CONVERSION_TRANS))
        assertEquals("" to "び", RomajiConverter.convertLastChar("ひ", LAST_CONVERSION_TRANS))
        assertEquals("" to "ぴ", RomajiConverter.convertLastChar("び", LAST_CONVERSION_TRANS))
        assertEquals("" to "ひ", RomajiConverter.convertLastChar("ぴ", LAST_CONVERSION_TRANS))
        assertEquals("" to "ヵ", RomajiConverter.convertLastChar("カ", LAST_CONVERSION_TRANS))
        assertEquals("" to "ガ", RomajiConverter.convertLastChar("ヵ", LAST_CONVERSION_TRANS))
        assertEquals("" to "カ", RomajiConverter.convertLastChar("ガ", LAST_CONVERSION_TRANS))
        assertEquals("" to "ヵ", RomajiConverter.convertLastChar("ｶ", LAST_CONVERSION_TRANS))
        assertEquals("" to "カ", RomajiConverter.convertLastChar("ｶﾞ", LAST_CONVERSION_TRANS))

        // LAST_CONVERSION_SHIFT
        assertEquals("あ" to "a", RomajiConverter.convertLastChar("あa", LAST_CONVERSION_SHIFT))
        assertEquals("あ" to "あ", RomajiConverter.convertLastChar("ああ", LAST_CONVERSION_SHIFT))
        assertEquals("あ" to "漢", RomajiConverter.convertLastChar("あ漢", LAST_CONVERSION_SHIFT))
    }

    @Test
    fun testCheckSpecialConsonants() {
        assertEquals(null, RomajiConverter.checkSpecialConsonants('あ', 'a'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('あ', 'n'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('n', 'a'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('n', 'n'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('k', 'n'.code))
        assertEquals("ん", RomajiConverter.checkSpecialConsonants('n', 'k'.code))
        assertEquals("っ", RomajiConverter.checkSpecialConsonants('k', 'k'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('y', 'n'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('n', 'y'.code))
        assertEquals("っ", RomajiConverter.checkSpecialConsonants('y', 'y'.code))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('z', 'n'.code))
        // 以下は "っ" にならない
        assertEquals(null, RomajiConverter.checkSpecialConsonants(0.toChar(), 0))
        assertEquals(null, RomajiConverter.checkSpecialConsonants('あ', 'あ'.code))
    }

    @Test
    fun testIsIntermediateRomaji() {
        // true
        assertTrue(RomajiConverter.isIntermediateRomaji("k"))
        assertTrue(RomajiConverter.isIntermediateRomaji("ky"))
        assertTrue(RomajiConverter.isIntermediateRomaji("sh"))
        assertTrue(RomajiConverter.isIntermediateRomaji("ch"))
        assertTrue(RomajiConverter.isIntermediateRomaji("n"))
        assertTrue(RomajiConverter.isIntermediateRomaji("gy"))
        assertTrue(RomajiConverter.isIntermediateRomaji("j"))
        assertTrue(RomajiConverter.isIntermediateRomaji("ny"))
        assertTrue(RomajiConverter.isIntermediateRomaji("hy"))
        assertTrue(RomajiConverter.isIntermediateRomaji("by"))
        assertTrue(RomajiConverter.isIntermediateRomaji("py"))
        assertTrue(RomajiConverter.isIntermediateRomaji("my"))
        assertTrue(RomajiConverter.isIntermediateRomaji("ry"))
        assertTrue(RomajiConverter.isIntermediateRomaji("f"))
        assertTrue(RomajiConverter.isIntermediateRomaji("v"))
        assertTrue(RomajiConverter.isIntermediateRomaji("x"))
        assertTrue(RomajiConverter.isIntermediateRomaji("c"))
        assertTrue(RomajiConverter.isIntermediateRomaji("dh"))
        assertTrue(RomajiConverter.isIntermediateRomaji("th"))
        assertTrue(RomajiConverter.isIntermediateRomaji("dy"))
        assertTrue(RomajiConverter.isIntermediateRomaji("ty"))
        assertTrue(RomajiConverter.isIntermediateRomaji("zy"))
        assertTrue(RomajiConverter.isIntermediateRomaji("sy"))
        assertTrue(RomajiConverter.isIntermediateRomaji("z"))
        assertTrue(RomajiConverter.isIntermediateRomaji("s"))
        assertTrue(RomajiConverter.isIntermediateRomaji("t"))
        assertTrue(RomajiConverter.isIntermediateRomaji("d"))
        assertTrue(RomajiConverter.isIntermediateRomaji("h"))
        assertTrue(RomajiConverter.isIntermediateRomaji("b"))
        assertTrue(RomajiConverter.isIntermediateRomaji("p"))
        assertTrue(RomajiConverter.isIntermediateRomaji("m"))
        assertTrue(RomajiConverter.isIntermediateRomaji("y"))
        assertTrue(RomajiConverter.isIntermediateRomaji("r"))
        assertTrue(RomajiConverter.isIntermediateRomaji("w"))

        // false
        assertFalse(RomajiConverter.isIntermediateRomaji("a"))
        assertFalse(RomajiConverter.isIntermediateRomaji("i"))
        assertFalse(RomajiConverter.isIntermediateRomaji("u"))
        assertFalse(RomajiConverter.isIntermediateRomaji("e"))
        assertFalse(RomajiConverter.isIntermediateRomaji("o"))
        assertFalse(RomajiConverter.isIntermediateRomaji("nn"))
        // ここまでは確定しているはずのもの、以下はローマ字ルールにないもの
        assertFalse(RomajiConverter.isIntermediateRomaji("ts")) // t を typo とみなす
        assertFalse(RomajiConverter.isIntermediateRomaji("q")) // SKK では使用しない
        assertFalse(RomajiConverter.isIntermediateRomaji("l")) // SKK では使用しない
        assertFalse(RomajiConverter.isIntermediateRomaji("invalid"))
    }
}