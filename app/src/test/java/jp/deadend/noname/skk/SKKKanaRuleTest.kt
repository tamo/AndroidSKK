package jp.deadend.noname.skk

import org.junit.Assert.assertEquals
import org.junit.Test

class SKKKanaRuleTest {

    @Test
    fun parse_validFormat_returnsMap() {
        val text = """
            # comment line
            a,あ,ア
            q,い,イ
            ;,ー,ー
            
            bn,びん,ビン
        """.trimIndent()

        val result = SKKKanaRule.parse(text)

        assertEquals("あ", result["a"])
        assertEquals("い", result["q"])
        assertEquals("ー", result[";"])
        assertEquals("びん", result["bn"])
        assertEquals(4, result.size)
    }

    @Test
    fun parse_invalidFormat_ignoresLines() {
        val text = """
            # missing comma
            invalidLine
            a,あ
            # empty fields
            ,
            b,
            c,  ,
        """.trimIndent()

        val result = SKKKanaRule.parse(text)

        assertEquals("あ", result["a"])
        assertEquals(1, result.size)
    }

    @Test
    fun parse_duplicateKey_lastWins() {
        val text = """
            a,あ
            a,い
        """.trimIndent()

        val result = SKKKanaRule.parse(text)

        assertEquals("い", result["a"])
        assertEquals(1, result.size)
    }

    @Test
    fun parse_emptyInput_returnsEmptyMap() {
        assertEquals(0, SKKKanaRule.parse("").size)
        assertEquals(0, SKKKanaRule.parse("   ").size)
        assertEquals(0, SKKKanaRule.parse("# comment only").size)
    }
}
