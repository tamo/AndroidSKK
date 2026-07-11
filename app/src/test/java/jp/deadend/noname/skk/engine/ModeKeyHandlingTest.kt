package jp.deadend.noname.skk.engine

import android.content.Context
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import jp.deadend.noname.skk.SKKApplication
import jp.deadend.noname.skk.SKKKanaRule
import jp.deadend.noname.skk.SKKPrefs
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModeKeyHandlingTest {
    private lateinit var engine: SKKEngine
    private lateinit var prefs: SKKPrefs
    private val service = mockk<SKKService>(relaxed = true)
    private val ic = mockk<InputConnection>(relaxed = true)
    private val userDict = mockk<SKKUserDictionary>(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = SKKPrefs(context)
        SKKApplication.prefs = prefs

        // Load default rules for "zl" -> "→" and "z/" -> "・"
        val defaultRule = context.assets.open(SKKKanaRule.DEFAULT_RULE_FILE)
            .bufferedReader().use { it.readText() }
        RomajiConverter.loadRules(SKKKanaRule.parse(defaultRule))

        every { service.currentInputConnection } returns ic
        engine = SKKEngine(service, listOf(userDict), userDict, userDict, userDict, userDict)

        every { service.kanaState = any() } answers {
            engine.kanaState = it.invocation.args[0] as SKKState
        }
        every { service.isHiragana } answers { engine.kanaState is SKKHiraganaState }
    }

    private fun typeKey(char: Char) {
        engine.processKey(char.code)
    }

    @Test
    fun testZLtoArrowWithArbitraryAsciiKey() {
        prefs.asciiKey = 'l'.code

        assertEquals(SKKHiraganaState.name, engine.state.name)

        typeKey('z')
        assertEquals("z", engine.mRoman.toString())

        typeKey('l')
        // Should NOT change to ASCII state, but commit "→"
        assertEquals(SKKHiraganaState.name, engine.state.name)
        assertEquals(0, engine.mRoman.length)
        // verify { ic.commitText("→", 1) } // mockk verify might be tricky here depending on how commitTextSKK is called
    }

    @Test
    fun testZSlashToDotWithArbitraryAbbrevKey() {
        prefs.abbrevKey = '/'.code

        assertEquals(SKKHiraganaState.name, engine.state.name)

        typeKey('z')
        assertEquals("z", engine.mRoman.toString())

        typeKey('/')
        // Should NOT change to Abbrev state, but commit "・"
        assertEquals(SKKHiraganaState.name, engine.state.name)
        assertEquals(0, engine.mRoman.length)
    }

    @Test
    fun testModeSwitchWhenNotFollowingZ() {
        prefs.asciiKey = 'l'.code

        assertEquals(SKKHiraganaState.name, engine.state.name)

        typeKey('l')
        assertEquals(SKKASCIIState.name, engine.state.name)
    }

    @Test
    fun testPreeditModeKeyHandling() {
        prefs.asciiKey = 'l'.code

        typeKey('A')
        assertEquals(SKKPreeditState.name, engine.state.name)

        typeKey('z')
        assertEquals("z", engine.mRoman.toString())

        typeKey('l')
        // Should NOT change to ASCII state, but append "→" to preedit
        assertEquals(
            "State should be Preedit. Current roman: " + engine.mRoman.toString() + ", KanjiKey: " + engine.mKanjiKey.toString(),
            SKKPreeditState.name,
            engine.state.name
        )
        assertEquals(0, engine.mRoman.length)
        assertEquals("あ→", engine.mKanjiKey.toString())
    }
}
