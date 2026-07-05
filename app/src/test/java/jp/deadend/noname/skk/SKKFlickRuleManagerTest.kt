package jp.deadend.noname.skk

import android.content.Context
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SKKFlickRuleManagerTest {
    private lateinit var activity: SKKFlickRuleManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        SKKFlickRule.clear(context)

        val controller = Robolectric.buildActivity(SKKFlickRuleManager::class.java)
        activity = controller.setup().get()

        val mockService = io.mockk.mockk<SKKService>(relaxed = true)
        val keyboardView = activity.findViewById<FlickJPKeyboardView>(R.id.flickKeyboardView)
        keyboardView.setService(mockService)
    }

    @Test
    fun testGodanPerSection() {
        val checkUseGodan = activity.findViewById<android.widget.CheckBox>(R.id.checkUseGodan)
        assertFalse("Initially Godan should be off for Main", checkUseGodan.isChecked)

        // Enable Godan for Main
        checkUseGodan.isChecked = true
        shadowOf(getMainLooper()).idle()

        // Verify that Main section now has keys 20..23
        val ruleMap = activity.getPrivateProperty<MutableFlickRule>("ruleMap")
        val mainEntries = ruleMap.sections[SKKFlickRule.SECTION_MAIN]?.entries
        for (i in 20..23) {
            assertTrue("Main section should have key $i", mainEntries?.containsKey(i) == true)
        }

        // Switch to Number section
        val radioNumber = activity.findViewById<android.widget.RadioButton>(R.id.radioSectionNumber)
        radioNumber.isChecked = true
        shadowOf(getMainLooper()).idle()

        assertFalse("Godan should be off for Number section", checkUseGodan.isChecked)
        assertFalse(
            "Number section should NOT have Godan keys yet",
            (ruleMap.sections[SKKFlickRule.SECTION_NUMBER]?.entries?.keys?.maxOrNull() ?: 0) > 19
        )

        // Enable Godan for Number
        checkUseGodan.isChecked = true
        shadowOf(getMainLooper()).idle()
        val numEntries = ruleMap.sections[SKKFlickRule.SECTION_NUMBER]?.entries
        for (i in 20..23) {
            assertTrue("Number section should have key $i", numEntries?.containsKey(i) == true)
        }

        // Switch back to Main and disable Godan
        val radioMain = activity.findViewById<android.widget.RadioButton>(R.id.radioSectionMain)
        radioMain.isChecked = true
        shadowOf(getMainLooper()).idle()
        assertTrue("Main section should still have Godan checked", checkUseGodan.isChecked)

        checkUseGodan.isChecked = false
        shadowOf(getMainLooper()).idle()

        // A confirmation dialog should appear. In Robolectric we can simulate clicking it.
        val dialog = activity.supportFragmentManager.findFragmentByTag("dialog")
        assertNotNull("Confirmation dialog should be shown", dialog)

        // Simulate positive click
        val listener = dialog
            ?.getPrivateProperty<jp.deadend.noname.dialog.ConfirmationDialogFragment.Listener>("mListener")
        listener?.onPositiveClick()
        shadowOf(getMainLooper()).idle()

        assertTrue(
            "Main section should have no keys > 19",
            (ruleMap.sections[SKKFlickRule.SECTION_MAIN]?.entries
                ?.keys?.filter { it > 19 }?.size ?: 0) == 0
        )

        // Verify Number section STILL has Godan keys
        radioNumber.isChecked = true
        shadowOf(getMainLooper()).idle()
        assertTrue("Number section should still have Godan checked", checkUseGodan.isChecked)
        assertTrue(
            "Number section should still have Godan keys",
            (ruleMap.sections[SKKFlickRule.SECTION_NUMBER]?.entries?.keys?.maxOrNull() ?: 0) > 19
        )
    }

    private fun <T> Any.getPrivateProperty(name: String): T {
        val field = this::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }
}
