package jp.deadend.noname.skk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class SKKUserDictionaryTest {

    private val store = mockk<SKKStore>(relaxed = true)

    private fun createDict(isASCII: Boolean): SKKUserDictionary {
        val constructor = SKKUserDictionary::class.java.getDeclaredConstructor(
            SKKStore::class.java,
            Boolean::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(store, isASCII, "path", "btree", false) as SKKUserDictionary
    }

    @Test
    fun testGetCandidates_ASCII() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/100/word1/200/word2/150/word3/"

        val candidates = dict.getCandidates("test")
        assertEquals(listOf("word2", "word3", "word1"), candidates)
    }

    @Test
    fun testGetCandidates_ASCII_Distinct() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/100/word1/200/word1/"

        val candidates = dict.getCandidates("test")
        assertEquals(listOf("word1"), candidates)
    }

    @Test
    fun testGetCandidates_Normal() {
        val dict = createDict(isASCII = false)
        every { store.get("test") } returns "/word1/word2/word3/"

        val candidates = dict.getCandidates("test")
        assertEquals(listOf("word1", "word2", "word3"), candidates)
    }

    @Test
    fun testAddEntry_ASCII_New() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns null

        dict.addEntry("test", "word1", "")

        verify { store.set("test", "/160/word1/") }
    }

    @Test
    fun testAddEntry_ASCII_Existing_UpdateFreq() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/160/word1/100/word2/"

        dict.addEntry("test", "word2", "")

        verify { store.set("test", "/160/word2/160/word1/") }
    }

    @Test
    fun testAddEntry_ASCII_Existing_HigherFreq() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/160/word1/200/word2/"

        dict.addEntry("test", "word2", "")

        verify { store.set("test", "/200/word2/160/word1/") }
    }

    @Test
    fun testRemoveEntry_ASCII() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/160/word1/100/word2/"

        dict.removeEntry("test", "word1", "")

        verify { store.set("test", "/100/word2/") }
    }

    @Test
    fun testRemoveEntry_ASCII_LastOne() {
        val dict = createDict(isASCII = true)
        every { store.get("test") } returns "/160/word1/"

        dict.removeEntry("test", "word1", "")

        verify { store.delete("test") }
    }

    @Test
    fun testGetAllCandidates_ASCII() {
        val dict = createDict(isASCII = true)
        val cursor = mockk<SKKStoreCursor>()
        every { store.cursor() } returns cursor
        every { cursor.next() } returnsMany listOf(
            SKKStoreTuple("k1", "/100/w1/200/w2/"),
            SKKStoreTuple("k2", "/150/w3/100/w2/"),
            null
        )

        val all = dict.getAllCandidates()
        assertEquals(listOf("w1", "w2", "w3"), all)
    }

    @Test
    fun testAddEntry_Normal_Okurigana() {
        val dict = createDict(isASCII = false)
        every { store.get("かk") } returns null

        dict.addEntry("かk", "書", "く")

        verify { store.set("かk", "/書/[く/書/]/") }
    }
}
