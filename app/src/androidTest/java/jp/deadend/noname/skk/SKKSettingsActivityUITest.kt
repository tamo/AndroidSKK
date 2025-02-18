package jp.deadend.noname.skk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SKKSettingsActivityUITest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testSettings() {
        ActivityScenario.launch(SKKSettingsActivity::class.java)
        // インストール直後はシステムのキーボード設定が開くので UiDevice で操作
        val device = UiDevice.getInstance(getInstrumentation())
        device.wait(Until.findObject(By.text("SKK for Android")), 1000).click()
        // Attention ... Use this input method?
        device.wait(Until.findObject(By.text("OK")), 1000).click()
        // Note: If you restart your phone ...
        device.wait(Until.findObject(By.text("OK")), 1000).click()
        // でもテスト環境ではこの設定が保存されないみたい
    }

    @Test
    fun testUserDicTool() {
        // SettingsActivity を開くとシステムのキーボード設定が開いてしまうので直接 Intent で開く
        val intent = Intent("android.intent.action.MAIN", Uri.parse("*skk_userdict")).apply {
            component =
                ComponentName("jp.deadend.noname.skk", "jp.deadend.noname.skk.SKKUserDicTool")
        }
        ActivityScenario.launch<SKKUserDicTool>(intent)
        // 初期化されます。よろしいですか
        onView(withText("OK")).perform(click())
        // 初期化された状態からハンバーガーメニュー
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText("インポート")).perform(click())
        // このあとのテスト方法はまだ調べていない
    }
}