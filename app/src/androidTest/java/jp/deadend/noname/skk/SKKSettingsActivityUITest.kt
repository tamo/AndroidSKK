package jp.deadend.noname.skk

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withChild
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SKKSettingsActivityUITest {

    private val device = UiDevice.getInstance(getInstrumentation())
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun testInputMethodSettings() {
        ActivityScenario.launch(SKKSettingsActivity::class.java)
        // インストール直後はシステムのキーボード設定が開くので UiDevice で操作
        device.wait(Until.findObject(By.text("SKK for Android")), 1000)?.let {
            it.click()
            // Attention ... Use this input method?
            device.wait(Until.findObject(By.text("OK")), 1000).click()
            // Note: If you restart your phone ...
            device.wait(Until.findObject(By.text("OK")), 1000).click()
            Thread.sleep(1000)
            device.pressBack()
            Thread.sleep(1000)
            // ここから UiDevice ではなく espresso で操作できるようになる
        }
    }

    @Test
    fun testDictManager() {
        assert(skkPrefs.dictOrder == skkPrefs.defaultDictOrder)
        onView(withText("SKK 辞書管理")).perform(click())
        onView(withText("SKK S 辞書"))
            .check(matches(isNotChecked()))
            .perform(click())
        // ダウンロードしますか?
        onView(withText("OK")).perform(click())
        Thread.sleep(5000)
        onView(withText("SKK S 辞書"))
            .check(matches(isChecked()))
        // 長押ししてドラッグで順番が変わるのもテストしたいけど面倒みたいなので略
        pressBack() // この onPause で skkPrefs が更新される
        assert(skkPrefs.dictOrder == skkPrefs.defaultDictOrder + "SKK S 辞書/skk_dict_S/")
    }

    @Test
    fun testUserDictTool() {
        // 下の方が見えないのでスワイプアップしておく
        onView(withChild(withChild(withChild(withText("SKK 辞書管理"))))).perform(swipeUp())
        onView(withText("ユーザー辞書の初期化")).perform(click())
        // 初期化されます。よろしいですか
        onView(withText("OK")).perform(click())
        // 初期化された状態からハンバーガーメニュー
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText("インポート")).perform(click())
        // このあとのテスト方法はまだ調べていない
    }

    @Test
    fun testHardKeyFragment() {
        assert(skkPrefs.kanaKey == 612)
        onView(withText("ハードウェアキーボードの設定")).perform(click())
        onView(withText("かなキー"))
            .check(matches(hasSibling(withText("CTRL+J"))))
            .perform(click())
            .check(matches(hasSibling(withText("Push any key..."))))
        repeat(3) { // ときどき key up しか発行されないことがある?
            Thread.sleep(1000)
            onView(withText("かなキー")).perform(pressKey(KeyEvent.KEYCODE_TAB))
        }
        onView(withText("かなキー"))
            .check(matches(hasSibling(withText("TAB"))))
        assert(skkPrefs.kanaKey == 9)
    }

    @Test
    fun testSoftKeyFragment() {
        assert(skkPrefs.useSoftKey == "auto")
        onView(withText("ソフトウェアキーボードの設定")).perform(click())
        onView(withText("ソフトウェアキーボードの表示"))
            .check(matches(hasSibling(withText("自動"))))
            .perform(click())
        onView(withText("常に表示")).perform(click())
        onView(withText("ソフトウェアキーボードの表示"))
            .check(matches(hasSibling(withText("常に表示"))))
        assert(skkPrefs.useSoftKey == "on")
    }
}