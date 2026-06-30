package jp.deadend.noname.skk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlickActionTest {

    @Test
    fun testEncodingBasic() {
        val action = FlickAction("abc")
        assertEquals(3, action.codes.size)
        assertEquals('a'.code, action.codes[0])
        assertEquals('b'.code, action.codes[1])
        assertEquals('c'.code, action.codes[2])
        assertEquals("abc", action.text)
        assertEquals("FlickAction(text=abc)", action.toString())
    }

    @Test
    fun testEncodingModifiers() {
        val action = FlickAction("<C>q")
        assertEquals(1, action.codes.size)
        assertEquals(encodeKey('q'.code, CTRL_PRESSED), action.codes[0])
        assertEquals("<C>q", action.text)
    }

    @Test
    fun testEncodingMultipleModifiers() {
        val action = FlickAction("<C><A>b")
        assertEquals(1, action.codes.size)
        assertEquals(encodeKey('b'.code, CTRL_PRESSED or ALT_PRESSED), action.codes[0])
        assertEquals("<C><A>b", action.text)
    }

    @Test
    fun testEncodingMultipleCharsWithModifiers() {
        val action = FlickAction("<C>a<A>b")
        assertEquals(2, action.codes.size)
        assertEquals(encodeKey('a'.code, CTRL_PRESSED), action.codes[0])
        assertEquals(encodeKey('b'.code, ALT_PRESSED), action.codes[1])
        assertEquals("<C>a<A>b", action.text)
    }

    @Test
    fun testStartsWithAndRemovePrefix() {
        val action = FlickAction("(Commit)abc")
        assertTrue(action.text.startsWith("(Commit)"))

        val prefixed = action.removePrefix("(Commit)")
        assertEquals("abc", prefixed.text)
        assertEquals(3, prefixed.codes.size)
        assertEquals('a'.code, prefixed.codes[0])
    }
}
