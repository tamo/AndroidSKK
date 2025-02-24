package jp.deadend.noname.skk

import android.content.Context
import android.os.Build
import android.os.Environment
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.SearchView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SKKSettingsActivityUITest {

    private val device = UiDevice.getInstance(getInstrumentation())
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dictText = "あu /(concat \"合\\057\");注釈/[う/合;注釈2/]/会;人と/[う/会;人と2/]/"

    @Before
    fun testInputMethodSettings() {
        ActivityScenario.launch(SKKSettingsActivity::class.java)
        // インストール直後はシステムのキーボード設定が開くので UiDevice で操作
        device.wait(
            Until.findObject(
                By.checkable(true)
                    .hasAncestor(By.hasChild(By.hasChild(By.text("SKK for Android"))))
            ), 1000
        )?.let {
            it.click()
            // Attention ... Use this input method?
            device.wait(Until.findObject(By.text("OK")), 1000).click()
            // Note: If you restart your phone ...
            device.wait(Until.findObject(By.text("OK")), 1000).click()
            Thread.sleep(1000) // アニメーション効果で時間がかかることがある
            device.pressBack()
            // 新しめの Android はインストール直後にまず通知権限を求める
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.wait(Until.findObject(By.text("Allow")), 1000)?.click()
                    ?: device.wait(Until.findObject(By.text("許可")), 1000)?.click()
            }
            Thread.sleep(1000)
            // ここから UiDevice ではなく espresso で操作できるようになる
        }
    }

    @Before
    fun prepareDictFile() {
        val dictFile = File(Environment.getExternalStorageDirectory(), "Download/sample_dict.txt")
        if (!dictFile.exists()) {
            dictFile.writeText(dictText) // なぜか知らないが新規作成はできて、その後は読むこともできない
        }
    }

    @Test
    fun testDictManager() {
        // 新規インストールであることを前提としているので失敗したら再テストで通る
        assert(skkPrefs.dictOrder == skkPrefs.defaultDictOrder)
        onView(withText("SKK 辞書管理")).perform(click())
        onView(withText("SKK S 辞書"))
            .check(matches(isNotChecked()))
            .perform(click())
        // ダウンロードしますか?
        onView(withText("OK")).perform(click())
        val maxWait = 5
        for (i in 1..maxWait) {
            Thread.sleep(1000)
            if (try {
                    onView(withText("SKK S 辞書")).check(matches(isChecked()))
                    true
                } catch (e: NoMatchingViewException) {
                    if (i < maxWait) false else throw e
                }
            ) break
        }
        // 長押ししてドラッグで順番が変わるのもテストしたいけど面倒みたいなので略

        pressBack() // この onPause で skkPrefs が更新される
        assert(skkPrefs.dictOrder == skkPrefs.defaultDictOrder + "SKK S 辞書/skk_dict_S/")
    }

    @Test
    fun testUserDictTool() {
        // 下の方が見えないのでスワイプアップしておく
        onView(isRoot()).perform(swipeUp())
        onView(withText("ユーザー辞書の初期化")).perform(click())
        // 初期化されます。よろしいですか
        onView(withText("OK")).perform(click())

        // 初期化された状態からハンバーガーメニュー
        onView(withText(dictText)).check(doesNotExist())
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText("インポート")).perform(click())

        // ファイル選択画面
        device.wait(Until.findObject(By.text("sample_dict.txt")), 1000).click()
        device.wait(Until.findObject(By.text(dictText)), 3000)
        // この時点では検索条件がないので辞書の内容がすべて見えている

        // キーボード選択の UI は Android バージョンによって異なるかも
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
        device.wait(Until.findObject(By.text("SKK for Android")), 1000).click()
        // これで SKK が有効になった

        // 検索欄
        onView(withId(R.id.userDictToolSearch)).perform(click())
        // AU で漢字変換して確定する
        device.pressKeyCodes(
            intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_U),
            KeyEvent.META_SHIFT_LEFT_ON
        )
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        onView(withId(R.id.userDictToolSearch)).check { v, _ ->
            assert((v as SearchView).query.contentEquals("合/う"))
            // テスト前に service が動いていたら失敗する: 再テストで通るはず
        }
        // 検索欄は「合/う」になっているので検索条件に合わなくなる
        onView(withText(dictText)).check(doesNotExist())

        // 検索条件を「合/う」から「合」にすると再び合致するようになる
        device.pressKeyCode(KeyEvent.KEYCODE_DEL)
        onView(withText(dictText)).check(doesNotExist())
        device.pressKeyCode(KeyEvent.KEYCODE_DEL)
        onView(withText(dictText)).check(matches(isDisplayed()))
    }

    @Test
    fun testHardKeyFragment() {
        assert(skkPrefs.kanaKey == KeyEvent.KEYCODE_J shl 4 or /* CTRL_PRESSED */ 4)
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
        assert(skkPrefs.kanaKey == KeyEvent.KEYCODE_TAB shl 4)
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