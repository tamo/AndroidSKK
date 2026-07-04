package jp.deadend.noname.skk

import android.content.Context
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jp.deadend.noname.skk.engine.SKKState
import jp.deadend.noname.skk.engine.SKKZenkakuState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlickJPKeyboardViewTest {
    private lateinit var view: FlickJPKeyboardView
    private val service = mockk<SKKService>(relaxed = true)
    private val ic = mockk<InputConnection>(relaxed = true)
    private val engineState = mockk<SKKState>(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        view = FlickJPKeyboardView(context, null)
        view.setService(service)
        every { service.currentInputConnection } returns ic
        every { service.engineState } returns engineState
    }

    @Test
    fun testExecuteActionEscaping() {
        view.executeAction("(()C)")

        verifyOrder {
            service.processKey(encodeKey('('.code))
            service.processKey(encodeKey('C'.code))
            service.processKey(encodeKey(')'.code))
        }
    }

    @Test
    fun testExecuteActionModifiers() {
        view.executeAction("(C)a")
        verify { service.processKey(encodeKey('a'.code, CTRL_PRESSED)) }

        view.executeAction("(A)b")
        verify { service.processKey(encodeKey('b'.code, ALT_PRESSED)) }

        view.executeAction("(M)c")
        verify { service.processKey(encodeKey('c'.code, META_PRESSED)) }

        view.executeAction("(C)(A)x")
        verify { service.processKey(encodeKey('x'.code, CTRL_PRESSED or ALT_PRESSED)) }
    }

    @Test
    fun testExecuteActionZenkakuMode() {
        view.executeAction("(Z)abc(Z)")
        verifyOrder {
            service.processKeyIn(SKKZenkakuState, 'a')
            service.processKeyIn(SKKZenkakuState, 'b')
            service.processKeyIn(SKKZenkakuState, 'c')
        }

        // After toggle, next char should be normal
        view.executeAction("d")
        verify { service.processKey(encodeKey('d'.code)) }
    }

    @Test
    fun testExecuteActionCommitMode() {
        view.executeAction("(Commit)xyz(Commit)")
        verifyOrder {
            service.commitTextSKK("x")
            service.commitTextSKK("y")
            service.commitTextSKK("z")
        }

        // After toggle, next char should be normal
        view.executeAction("w")
        verify { service.processKey(encodeKey('w'.code)) }
    }

    @Test
    fun testExecuteActionSpecialActions() {
        view.executeAction("(Backspace)")
        verify { service.handleBackspace() }

        view.executeAction("(Enter)")
        verify { service.handleEnter() }

        view.executeAction("(Delete)")
        verify { service.handleForwardDel() }
    }

    @Test
    fun testExecuteActionMixed() {
        view.executeAction("(C)a(Z)b(Commit)c")
        verifyOrder {
            service.processKey(encodeKey('a'.code, CTRL_PRESSED))
            service.processKeyIn(SKKZenkakuState, 'b')
            service.commitTextSKK("c")
        }
    }
}
