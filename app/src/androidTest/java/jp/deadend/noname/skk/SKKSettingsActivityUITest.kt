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
    private val preText = "あk /(concat \"開\\057\");注釈/[く/開;注釈2/]/あ;ひら/[く/あ;ひら2/]/"
    private val postText =
        "あk /あ;ひら/(concat \"開\\057\");注釈/[く/開;注釈2/]/[く/あ;ひら2/]/[く/(concat \"開\\057\");注釈/]/[く/あ;ひら/]/"

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
            dictFile.writeText(preText) // なぜか知らないが新規作成はできて、その後は読むこともできない
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
        onView(withText(preText)).check(doesNotExist())
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText("インポート")).perform(click())

        // ファイル選択画面
        device.wait(Until.findObject(By.text("sample_dict.txt")), 1000).click()
        device.wait(Until.findObject(By.text(preText)), 3000)
        // この時点では検索条件がないので辞書の内容がすべて見えている

        // キーボード選択の UI は Android バージョンによって異なるかも
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
        device.wait(Until.findObject(By.text("SKK for Android")), 1000).click()
        // これで SKK が有効になった

        // 検索欄
        onView(withId(R.id.userDictToolSearch)).perform(click())
        // AkU で漢字変換して確定する
        device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_LEFT_ON)
        device.pressKeyCode(KeyEvent.KEYCODE_K)
        device.pressKeyCode(KeyEvent.KEYCODE_U, KeyEvent.META_SHIFT_LEFT_ON)
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        onView(withId(R.id.userDictToolSearch)).check { v, _ ->
            assert((v as SearchView).query.contentEquals("開/く"))
            // テスト前に service が動いていたら失敗する: 再テストで通るはず
        }
        // 検索欄は「開/く」になっているので検索条件に合わなくなる
        onView(withText(preText)).check(doesNotExist())

        // 検索条件を「開/く」から「開」にすると再び合致するようになる
        device.pressKeyCode(KeyEvent.KEYCODE_DEL)
        onView(withText(preText)).check(doesNotExist())
        device.pressKeyCode(KeyEvent.KEYCODE_DEL)
        onView(withText(preText)).check(matches(isDisplayed()))

        // カタカナで変換を進める
        device.pressKeyCodes(intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_Q))
        device.pressKeyCodes(
            intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_K),
            KeyEvent.META_SHIFT_LEFT_ON
        )
        device.pressKeyCodes(
            intArrayOf(KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)
        )
        onView(withId(R.id.userDictToolSearch)).check { v, _ ->
            assert((v as SearchView).query.contentEquals("アク"))
        }

        // 「んん」に「オ」を登録
        device.pressKeyCode(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_LEFT_ON) // ▽n
        device.pressKeyCodes( // 「▽ンn」でスペースでも「んん」に登録される
            intArrayOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_SPACE).plus(
                intArrayOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_ENTER)
            ) // 登録でひらがなになったが手動でカタカナにして登録
        )
        onView(withId(R.id.userDictToolSearch)).check { v, _ ->
            assert((v as SearchView).query.contentEquals("アクお"))
        } // 登録終了でカタカナに戻り、「オ」を反転して「お」に

        // 「んん*で」を「えxで」と登録
        device.pressKeyCode(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_LEFT_ON) // ▽n
        device.pressKeyCodes(intArrayOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_N)) // ▽ン
        device.pressKeyCode(KeyEvent.KEYCODE_D, KeyEvent.META_SHIFT_LEFT_ON) // ▽ン*d
        device.pressKeyCodes(
            intArrayOf(
                KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_E, // 登録でひらがなになっている
                KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_X, // 本家SKKの挙動は不明
                KeyEvent.KEYCODE_ENTER
            )
        )
        onView(withId(R.id.userDictToolSearch)).check { v, _ ->
            assert((v as SearchView).query.contentEquals("アクおエxデ"))
        } // 登録終了でカタカナに戻り、「え」を反転し「エ」になる

        pressBack() // IMEキャンセル
        pressBack() // 設定ルートに戻る
        onView(withText("ユーザー辞書ツール")).perform(click())
        Thread.sleep(1000)
        onView(withText("んん /オ/")).check(matches(isDisplayed()))
        onView(withText("んんd /えx/[で/えx/]/")).check(matches(isDisplayed()))
        onView(withText(postText)).check(matches(isDisplayed()))
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