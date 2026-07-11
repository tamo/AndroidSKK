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
    private val symbolDict = mockk<SKKUserDictionary>(relaxed = true)

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
            userDict, asciiDict, emojiDict, symbolDict
        )
        every { service.kanaState = any() } answers {
            engine.kanaState = it.invocation.args[0] as SKKState
        }
        every { service.isHiragana } answers { engine.kanaState is SKKHiraganaState }
    }

    private fun typeText(text: String) {
        for (char in text) {
            when (char) {
                '\n' -> engine.handleEnter()
                '◀' -> engine.handleBackspace()
                '▶' -> engine.handleForwardDel()
                '■' -> engine.handleCancel() // Ctrl-g
                '←' -> engine.handleDpad(KeyEvent.KEYCODE_DPAD_LEFT)
                '↑' -> engine.handleDpad(KeyEvent.KEYCODE_DPAD_UP)
                '→' -> engine.handleDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
                '↓' -> engine.handleDpad(KeyEvent.KEYCODE_DPAD_DOWN)
                else -> {
                    val code = char.code
                    if (Character.isUpperCase(code)) {
                        engine.processKey(encodeKey(Character.toLowerCase(code), SHIFT_PRESSED))
                    } else {
                        engine.processKey(encodeKey(code))
                    }
                }
            }
        }
    }

    @Test
    fun testHiraganaState() {
        assertEquals(SKKHiraganaState, engine.state)

        typeText("k")
        verify { ic.setComposingText(match { it.toString() == "k" }, 1) }

        typeText("a")
        verify { ic.commitText(match { it.toString() == "か" }, 1) }
        assertEquals(0, engine.mRoman.length)
    }

    @Test
    fun testChangeState() {
        typeText("q")
        assertEquals(SKKKatakanaState, engine.state)
        assertEquals(SKKKatakanaState, engine.kanaState)

        typeText("q")
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(SKKHiraganaState, engine.kanaState)

        typeText("l")
        assertEquals(SKKASCIIState, engine.state)

        engine.handleKanaKey()
        assertEquals(SKKHiraganaState, engine.state)
    }

    @Test
    fun testPreeditState() {
        typeText("A")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        typeText("ti")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あち", engine.mKanjiKey.toString())

        typeText("←re") // あ[]ち -> あ[r]ち -> あれ[]ち
        assertEquals("あれち", engine.mKanjiKey.toString())
    }

    @Test
    fun testChangeLastChar() {
        typeText("A")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        engine.changeLastChar(SKKEngine.TRANS_AUTO)
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("ぁ", engine.mKanjiKey.toString())

        engine.changeLastChar(SKKEngine.TRANS_AUTO)
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あ", engine.mKanjiKey.toString())

        typeText("de")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あで", engine.mKanjiKey.toString())

        engine.changeLastChar(SKKEngine.TRANS_AUTO)
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("あて", engine.mKanjiKey.toString())

        engine.changeLastChar(SKKEngine.TRANS_SHIFT)
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(true, engine.mRegister.isOngoing)
        assertEquals("あt", engine.mRegister.mStack.first().key)
        assertEquals("て", engine.mRegister.mStack.first().okurigana)
        assertEquals(0, engine.mKanjiKey.length)
        assertEquals(0, engine.mOkurigana.length)
    }

    @Test
    fun testAbbrevState() {
        typeText("/a")
        assertEquals(SKKAbbrevState, engine.state)
        assertEquals("a", engine.mKanjiKey.toString())
    }

    @Test
    fun testConversionCycle() {
        val candidatesList = listOf("漢字", "幹事")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())

        typeText("Kanji")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("かんじ", engine.mKanjiKey.toString())

        typeText(" ")
        assertEquals("▼漢字", engine.mComposingText.toString())
        assertEquals(SKKChooseState, engine.state)
        assertEquals("漢字", engine.mCandidates.mList?.get(engine.mCandidates.mIndex))

        typeText(" ")
        assertEquals("▼幹事", engine.mComposingText.toString())
        assertEquals("幹事", engine.mCandidates.mList?.get(engine.mCandidates.mIndex))

        typeText("x")
        assertEquals("▼漢字", engine.mComposingText.toString())
    }

    @Test
    fun testBackspace() {
        typeText("k")
        verify { ic.setComposingText(match { it.toString() == "k" }, 1) }

        engine.handleBackspace()
        verify { ic.setComposingText(match { it.toString() == "" }, 1) }
        assertEquals(0, engine.mRoman.length)

        val candidatesList = listOf("漢字")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())
        typeText("Kanji ◀")
        verify { ic.commitText(match { it.toString() == "漢" }, 1) }
    }

    @Test
    fun testCancel() {
        typeText("A")
        assertEquals(SKKPreeditState, engine.state)

        typeText(" ")
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
        typeText("n\n")
        verify { ic.commitText(match { it.toString() == "ん" }, 1) }
        assertEquals(0, engine.mRoman.length)

        typeText("k\n")
        verify { ic.setComposingText(match { it.toString() == "" }, 1) }
        assertEquals(0, engine.mRoman.length)

        typeText("qn\n")
        verify { ic.commitText(match { it.toString() == "ン" }, 1) }

        typeText("Ak")
        assertEquals(1, engine.mRoman.length)
        assertEquals('k', engine.mRoman[0])
        typeText("\n")
        verify { ic.commitText(match { it.toString() == "ア" }, 1) }
        assertEquals(SKKKatakanaState, engine.state)
        assertEquals(0, engine.mKanjiKey.length)
        assertEquals(0, engine.mRoman.length)

        val candidatesList = listOf("漢字", "感じ")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())
        typeText("Kanji")
        assertEquals("かんじ", engine.mKanjiKey.toString()) // ひらがな
        assertEquals("▽カンジ", engine.mComposingText.toString()) // カタカナ
        typeText("  ")
        assertEquals(SKKChooseState, engine.state)
        typeText("\n")
        verify { ic.commitText(match { it.toString() == "感ジ" }, 1) } // カタカナ
        assertEquals(SKKKatakanaState, engine.state)
    }

    @Test
    fun testKatakanaConversionReversed() {
        val candidatesList = listOf("オバQ")
        every { userDict.getEntry("おばq") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())

        // 1. Hiragana mode (default)
        // Use '/' to switch to Abbrev mode from Preedit
        typeText("Oba/q \n")
        verify { ic.commitText(match { it.toString() == "オバQ" }, 1) }

        // 2. Katakana mode
        typeText("q") // Switch to Katakana mode
        assertEquals(SKKKatakanaState, engine.kanaState)

        // Use 'l' to switch to Abbrev mode from Preedit
        typeText("Obalq \n")
        verify { ic.commitText(match { it.toString() == "おばQ" }, 1) }
    }

    @Test
    fun testForwardDel() {
        // Preedit state
        typeText("Ati") // あち
        assertEquals("あち", engine.mKanjiKey.toString())
        assertEquals(2, engine.mKanjiKey.cursor)

        typeText("←←") // |あち
        assertEquals(0, engine.mKanjiKey.cursor)
        typeText("▶") // |ち
        assertEquals("ち", engine.mKanjiKey.toString())
        assertEquals(0, engine.mKanjiKey.cursor)

        typeText("a") // あ|ち
        assertEquals("あち", engine.mKanjiKey.toString())
        assertEquals(1, engine.mKanjiKey.cursor)
        typeText("▶") // あ|
        assertEquals("あ", engine.mKanjiKey.toString())
        assertEquals(1, engine.mKanjiKey.cursor)

        // Registration state
        engine.reset()
        typeText("A ") // ▽あ -> space -> registration
        assertEquals(true, engine.mRegister.isOngoing)
        typeText("kanji") // [登録]あ：かんじ|
        println("Entry after typing kanji: ${engine.mRegister.first()?.entry}, cursor: ${engine.mRegister.first()?.cursor}")
        assertEquals("かんじ", engine.mRegister.first()?.entry.toString())
        assertEquals(3, engine.mRegister.first()?.cursor)

        typeText("←←") // [登録]あ：か|んじ
        println("Entry after moving cursor: ${engine.mRegister.first()?.entry}, cursor: ${engine.mRegister.first()?.cursor}")
        assertEquals(1, engine.mRegister.first()?.cursor)
        typeText("▶") // [登録]あ：か|じ
        println("Entry after delete: ${engine.mRegister.first()?.entry}, cursor: ${engine.mRegister.first()?.cursor}")
        assertEquals("かじ", engine.mRegister.first()?.entry.toString())
        assertEquals(1, engine.mRegister.first()?.cursor)

        // Normal state (should return false and let the system handle it)
        engine.mRegister.mStack.clear()
        engine.reset()
        assertEquals(SKKHiraganaState, engine.state)
        assertEquals(false, engine.mRegister.isOngoing)
        val result = engine.handleForwardDel()
        assertEquals(false, result)
    }

    @Test
    fun testOkuriganaSokuon() {
        typeText("IXtu")
        assertEquals(SKKOkuriganaState, engine.state)
        assertEquals("いt", engine.mKanjiKey.toString())
        assertEquals("っ", engine.mOkurigana)

        typeText("◀")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("い", engine.mKanjiKey.toString())
    }

    @Test
    fun testOkuriganaComposingBackspace() {
        typeText("IRy")
        assertEquals(SKKOkuriganaState, engine.state)
        assertEquals("いr", engine.mKanjiKey.toString())
        assertEquals("", engine.mOkurigana)
        assertEquals("ry", engine.mRoman.toString())

        typeText("◀")
        assertEquals(SKKOkuriganaState, engine.state)
        assertEquals("r", engine.mRoman.toString())

        typeText("◀")
        assertEquals(SKKPreeditState, engine.state)
        assertEquals("い", engine.mKanjiKey.toString())
    }

    @Test
    fun testNarrowingHint() {
        val candidatesList = listOf("漢字", "感じ")
        every { userDict.getEntry("かんじ") } returns
                SKKUserDictionary.Entry(candidatesList, emptyList())
        every { userDict.getEntry("か") } returns
                SKKUserDictionary.Entry(listOf("漢"), emptyList())
        typeText("Kanji :")
        assertEquals(SKKNarrowingState, engine.state)
        assertEquals("▼漢字 hint: ", engine.mComposingText.toString())

        typeText("k")
        assertEquals("▼漢字 hint: k", engine.mComposingText.toString())

        typeText("a")
        assertEquals("▼漢字 hint: か", engine.mComposingText.toString())
    }
}
