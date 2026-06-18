package jp.deadend.noname.skk.engine

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jp.deadend.noname.skk.SHIFT_PRESSED
import jp.deadend.noname.skk.SKKApplication
import jp.deadend.noname.skk.SKKKanaRule
import jp.deadend.noname.skk.SKKPrefs
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import jp.deadend.noname.skk.encodeKey
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SKKEngineTest {
    private lateinit var engine: SKKEngine
    private val service = mockk<SKKService>(relaxed = true)
    private val ic = mockk<InputConnection>(relaxed = true)
    private val userDict = mockk<SKKUserDictionary>(relaxed = true)
    private val asciiDict = mockk<SKKUserDictionary>(relaxed = true)
    private val emojiDict = mockk<SKKUserDictionary>(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SKKApplication.prefs = SKKPrefs(context)
        val defaultRule = context.assets.open(SKKKanaRule.DEFAULT_RULE_FILE)
            .bufferedReader().use { it.readText() }
        RomajiConverter.loadRules(SKKKanaRule.parse(defaultRule))
        every { service.currentInputConnection } returns ic

        engine = SKKEngine(
            service, listOf(userDict, asciiDict, emojiDict),
            userDict, asciiDict, emojiDict
        )
        every { service.kanaState = any() } answers {
            engine.kanaState = it.invocation.args[0] as SKKState
        }
    }

    @Test
    fun testHiraganaState() {
        assertEquals(SKKHiraganaState, engine.state)

        engine.processKey(encodeKey('k'.code))
        verify { ic.setComposingText(match { it.toString() == "k" }, 1) }

        engine.processKey(encodeKey('a'.code))
        verify { ic.commitText(match { it.toString() == "か" }, 1) }
        assertEquals(0, engine.mComposing.length)
    }

    @Test
    fun testChangeState() {
        engine.processKey(encodeKey('q'.code))
        assertEquals(SKKKatakanaState, engine.state)
        assertEquals(SKKKatakanaState, engine.kanaState)

        engine.processKey(encodeKey('q'.code))
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(SKKHiraganaState, engine.kanaState)

        engine.processKey(encodeKey('l'.code))
        assertEquals(SKKASCIIState, engine.state)

        engine.handleKanaKey()
        assertEquals(SKKHiraganaState, engine.state)
    }

    @Test
    fun testPreeditState() {
        engine.processKey(encodeKey('a'.code, SHIFT_PRESSED))
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        engine.processKey(encodeKey('t'.code))
        engine.processKey(encodeKey('i'.code))
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あち", engine.mKanjiKey.toString())

        engine.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT) // あ[]ち
        engine.processKey(encodeKey('r'.code)) // あ[r]ち
        engine.processKey(encodeKey('e'.code)) // あれ[]ち
        assertEquals("あれち", engine.mKanjiKey.toString())
    }

    @Test
    fun testChangeLastChar() {
        engine.processKey(encodeKey('a'.code, SHIFT_PRESSED))
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        engine.changeLastChar("trans")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("ぁ", engine.mKanjiKey.toString())

        engine.changeLastChar("trans")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        engine.processKey(encodeKey('d'.code))
        engine.processKey(encodeKey('e'.code))
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あで", engine.mKanjiKey.toString())

        engine.changeLastChar("trans")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あて", engine.mKanjiKey.toString())

        engine.changeLastChar("shift")
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(true, engine.mRegister.isOngoing)
        assertEquals("あt", engine.mRegister.mStack.first().key)
        assertEquals("て", engine.mRegister.mStack.first().okurigana)
        assertEquals(0, engine.mKanjiKey.length)
        assertEquals(0, engine.mOkurigana.length)
    }

    @Test
    fun testAbbrevState() {
        engine.processKey(encodeKey('/'.code))
        engine.processKey(encodeKey('a'.code))
        assertEquals(SKKAbbrevState, engine.state)
        assertEquals("a", engine.mKanjiKey.toString())
    }

    @Test
    fun testConversionCycle() {
        val candidatesList = listOf("漢字", "幹事")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())

        engine.processKey(encodeKey('k'.code, SHIFT_PRESSED)) // ▽k
        engine.processKey(encodeKey('a'.code)) // ▽か
        engine.processKey(encodeKey('n'.code)) // ▽かn
        engine.processKey(encodeKey('j'.code)) // ▽かんj
        engine.processKey(encodeKey('i'.code)) // ▽かんじ
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("かんじ", engine.mKanjiKey.toString())

        engine.processKey(encodeKey(' '.code))
        assertEquals("▼漢字", engine.mComposingText.toString())
        assertEquals(SKKChooseState, engine.state)
        assertEquals("漢字", engine.mCandidates.mList?.get(engine.mCandidates.mIndex))

        engine.processKey(encodeKey(' '.code))
        assertEquals("▼幹事", engine.mComposingText.toString())
        assertEquals("幹事", engine.mCandidates.mList?.get(engine.mCandidates.mIndex))

        engine.processKey(encodeKey('x'.code))
        assertEquals("▼漢字", engine.mComposingText.toString())
    }

    @Test
    fun testBackspace() {
        engine.processKey(encodeKey('k'.code))
        verify { ic.setComposingText(match { it.toString() == "k" }, 1) }

        engine.handleBackspace()
        verify { ic.setComposingText(match { it.toString() == "" }, 1) }
        assertEquals(0, engine.mComposing.length)

        val candidatesList = listOf("漢字")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())
        engine.processKey(encodeKey('k'.code, SHIFT_PRESSED))
        engine.processKey(encodeKey('a'.code))
        engine.processKey(encodeKey('n'.code))
        engine.processKey(encodeKey('j'.code))
        engine.processKey(encodeKey('i'.code))
        engine.processKey(encodeKey(' '.code))
        engine.handleBackspace()
        verify { ic.commitText(match { it.toString() == "漢" }, 1) }
    }

    @Test
    fun testCancel() {
        engine.processKey(encodeKey('a'.code, SHIFT_PRESSED))
        assertEquals(SKKPreeditState, engine.state)

        engine.processKey(encodeKey(' '.code))
        assertEquals(true, engine.mRegister.isOngoing)
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals("[登録]あ：", engine.mComposingText.toString())

        engine.handleCancel() // Ctrl-g
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("▽あ", engine.mComposingText.toString())

        engine.handleCancel() // Ctrl-g
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(0, engine.mKanjiKey.length)
    }

    @Test
    fun testHandleEnter() {
        engine.processKey(encodeKey('n'.code))
        engine.handleEnter()
        verify { ic.commitText(match { it.toString() == "ん" }, 1) }
        assertEquals(0, engine.mComposing.length)

        engine.processKey(encodeKey('k'.code))
        engine.handleEnter()
        verify { ic.setComposingText(match { it.toString() == "" }, 1) }
        assertEquals(0, engine.mComposing.length)

        engine.processKey(encodeKey('q'.code))
        assertEquals(SKKKatakanaState, engine.state)
        engine.processKey(encodeKey('n'.code))
        engine.handleEnter()
        verify { ic.commitText(match { it.toString() == "ン" }, 1) }

        engine.processKey(encodeKey('a'.code, SHIFT_PRESSED))
        assertEquals(SKKPreeditState, engine.state)
        engine.processKey(encodeKey('k'.code))
        assertEquals(1, engine.mComposing.length)
        assertEquals('k', engine.mComposing[0])
        engine.handleEnter()
        verify { ic.commitText(match { it.toString() == "ア" }, 1) }
        assertEquals(SKKKatakanaState, engine.state)
        assertEquals(0, engine.mKanjiKey.length)
        assertEquals(0, engine.mComposing.length)

        val candidatesList = listOf("漢字", "感じ")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())
        engine.processKey(encodeKey('k'.code, SHIFT_PRESSED))
        engine.processKey(encodeKey('a'.code))
        engine.processKey(encodeKey('n'.code))
        engine.processKey(encodeKey('j'.code))
        engine.processKey(encodeKey('i'.code))
        assertEquals("かんじ", engine.mKanjiKey.toString()) // ひらがな
        assertEquals("▽カンジ", engine.mComposingText.toString()) // カタカナ
        engine.processKey(encodeKey(' '.code))
        engine.processKey(encodeKey(' '.code))
        assertEquals(SKKChooseState, engine.state)
        engine.handleEnter()
        verify { ic.commitText(match { it.toString() == "感ジ" }, 1) } // カタカナ
        assertEquals(SKKKatakanaState, engine.state)
    }
}
