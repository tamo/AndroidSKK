package jp.deadend.noname.skk.engine

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jp.deadend.noname.skk.CandidateLayout
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.SKKApplication
import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKPrefs
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class SKKCandidatesTest {
    private val engine = mockk<SKKEngine>(relaxed = true)
    private val service = mockk<SKKService>(relaxed = true)
    private val candidatesView = mockk<jp.deadend.noname.skk.CandidatesView>(relaxed = true)
    private val candidates = SKKCandidates(engine, service)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<Context>()
        SKKApplication.prefs = SKKPrefs(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(context.getString(R.string.pref_fuzzy_suggestion), true).commit()

        SKKNarrowingState.mOriginalCandidates = null
        every { engine.mUserDict } returns mockk<SKKUserDictionary>(relaxed = true)
        every { service.mCandidatesView } returns candidatesView
        every { candidatesView.buildLayout(any(), any(), any()) } returns
                (CandidateLayout.EMPTY to 1)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testComplete_respectsDictOrder() = runTest {
        val dict1 = mockk<SKKDictionaryInterface>(relaxed = true)
        val dict2 = mockk<SKKDictionaryInterface>(relaxed = true)
        every { engine.mDictList } returns listOf(dict1, dict2)
        every { engine.state } returns mockk<SKKHiraganaState>(relaxed = true)
        every { service.isHiragana } returns true

        coEvery { dict1.findCompletePairs(any(), "は") } coAnswers {
            Thread.sleep(100) // 最初の辞書の結果は、遅く返ってきても最初になる
            listOf("はあ" to "ハア") // 実際には日本語の辞書の pair は同じもの二つだが
        }
        coEvery { dict2.findCompletePairs(any(), "は") } returns
                listOf("はい" to "ハイ")

        candidates.complete("は")

        var count = 0
        while (candidates.mList == null && count < 10) {
            Thread.sleep(100)
            count++
        }

        // mList に入るのは findCompletePairs の second の方
        assertEquals(listOf("ハア", "ハイ"), candidates.mList)
    }

    @Test
    fun testComplete_fuzzyPriorityInSameDict() = runTest {
        val dict1 = mockk<SKKDictionaryInterface>(relaxed = true)
        every { engine.mDictList } returns listOf(dict1)
        every { engine.state } returns mockk<SKKHiraganaState>(relaxed = true)
        every { service.isHiragana } returns true

        coEvery { dict1.getCandidates("か") } returns listOf("下", "課")
        coEvery { dict1.findCompletePairs(any(), "か") } returns
                listOf("か" to "か", "かい" to "かい")
        coEvery { dict1.getCandidates("が") } returns listOf("画", "我")
        coEvery { dict1.findCompletePairs(any(), "が") } returns
                listOf("が" to "が", "がい" to "がい")

        candidates.complete("か")

        var count = 0
        while (candidates.mList == null && count < 10) {
            Thread.sleep(100)
            count++
        }

        assertEquals(listOf("か", "が", "かい", "がい"), candidates.mList)
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
        every { engine.lookup("おとこ") } returns listOf("男", "漢", "♂; 注釈に感を含む")

        candidates.narrow("おとこ")
        assertEquals(listOf("漢字; 注釈も含む"), candidates.mList)
    }

    @Test
    fun testNarrow_DirectMatch() {
        val originalList = listOf("漢字", "幹事", "監事", "感じ")
        candidates.mList = originalList
        every { engine.lookup("じ") } returns listOf("時", "字", "地")

        candidates.narrow("じ")
        assertEquals(listOf("漢字", "感じ"), candidates.mList)
    }

    @Test
    fun testNarrow_KatakanaMatch() {
        val originalList = listOf("\uD83D\uDCFA;テレビ", "\uD83D\uDCFB;ラジオ")
        candidates.mList = originalList
        every { engine.lookup("てれ") } returns emptyList()

        candidates.narrow("てれ")
        assertEquals(listOf("\uD83D\uDCFA;テレビ"), candidates.mList)
    }

    @Test
    fun testNarrow_NoMatch() {
        val originalList = listOf("A", "B")
        candidates.mList = originalList
        every { engine.lookup("X") } returns emptyList()

        candidates.narrow("X")
        assertEquals(null, candidates.mList)
    }

    @Test
    fun testNarrow_KanjiPartlyMatch() {
        val originalList = listOf("受", "授", "樹", "寿")
        candidates.mList = originalList
        every { engine.lookup("じゅもく") } returns listOf("樹木")

        candidates.narrow("じゅもく")
        assertEquals(listOf("樹"), candidates.mList)
    }

    @Test
    fun testPickCandidate_ResetsWhenNotSequential() {
        candidates.mList = listOf("candidate")
        candidates.mQuery = "query"
        candidates.isSequential = false
        every { engine.state.hasCandidates } returns true
        every { engine.kanaState } returns SKKHiraganaState

        candidates.pickCandidate(0)

        assertEquals(false, candidates.isSequential)
        io.mockk.verify { engine.reset() }
        io.mockk.verify { engine.changeState(any()) }
    }

    @Test
    fun testPickCandidate_DoesNotResetWhenSequential() {
        candidates.mList = listOf("candidate")
        candidates.mQuery = "query"
        candidates.isSequential = true
        every { engine.state.hasCandidates } returns true
        every { engine.kanaState } returns SKKHiraganaState

        candidates.pickCandidate(0)

        assertEquals(true, candidates.isSequential)
        io.mockk.verify(exactly = 0) { engine.reset() }
        io.mockk.verify(exactly = 0) { engine.changeState(any()) }
    }
}
