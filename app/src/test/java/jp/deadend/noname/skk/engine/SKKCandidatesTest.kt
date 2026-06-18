package jp.deadend.noname.skk.engine

import io.mockk.every
import io.mockk.mockk
import jp.deadend.noname.skk.SKKService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SKKCandidatesTest {
    private val engine = mockk<SKKEngine>(relaxed = true)
    private val service = mockk<SKKService>(relaxed = true)
    private val candidates = SKKCandidates(engine, service)

    @Before
    fun setUp() {
        SKKNarrowingState.mOriginalCandidates = null
    }

    @Test
    fun testNarrow_EmptyHint() {
        val list = listOf("A", "B")
        candidates.mList = list

        candidates.narrow("")
        assertEquals(list, candidates.mList)
    }

    @Test
    fun testNarrow_KanjiMatch() {
        val originalList = listOf("漢字; 注釈も含む", "幹事", "監事", "感じ")
        candidates.mList = originalList
        every { engine.find("おとこ") } returns listOf("男", "漢", "♂; 注釈に感を含む")

        candidates.narrow("おとこ")
        assertEquals(listOf("漢字; 注釈も含む"), candidates.mList)
    }

    @Test
    fun testNarrow_DirectMatch() {
        val originalList = listOf("漢字", "幹事", "監事", "感じ")
        candidates.mList = originalList
        every { engine.find("じ") } returns listOf("時", "字", "地")

        candidates.narrow("じ")
        assertEquals(listOf("漢字", "感じ"), candidates.mList)
    }

    @Test
    fun testNarrow_KatakanaMatch() {
        val originalList = listOf("\uD83D\uDCFA;テレビ", "\uD83D\uDCFB;ラジオ")
        candidates.mList = originalList
        every { engine.find("てれ") } returns emptyList()

        candidates.narrow("てれ")
        assertEquals(listOf("\uD83D\uDCFA;テレビ"), candidates.mList)
    }

    @Test
    fun testNarrow_NoMatch() {
        val originalList = listOf("A", "B")
        candidates.mList = originalList
        every { engine.find("X") } returns emptyList()

        candidates.narrow("X")
        assertEquals(null, candidates.mList)
    }

    @Test
    fun testNarrow_KanjiPartlyMatch() {
        val originalList = listOf("受", "授", "樹", "寿")
        candidates.mList = originalList
        every { engine.find("じゅもく") } returns listOf("樹木")

        candidates.narrow("じゅもく")
        assertEquals(listOf("樹"), candidates.mList)
    }
}
