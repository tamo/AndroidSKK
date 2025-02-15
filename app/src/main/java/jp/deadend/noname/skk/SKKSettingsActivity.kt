package jp.deadend.noname.skk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import jp.deadend.noname.skk.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class SKKSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    var keyPref: Preference? = null

    class SettingsMainFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_main, rootKey)
        }
    }
    class SettingsSoftKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_softkey, rootKey)

            // 触感のプレビュー
            findPreference<Preference>(getString(R.string.prefkey_haptic))
                ?.setOnPreferenceChangeListener { _, haptic ->
                    ViewCompat.performHapticFeedback(requireActivity().window.decorView, haptic as Int)
                }
        }
    }

    class SettingsHardKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_hardkey, rootKey)

            findPreference<Preference>(getString(R.string.prefkey_kana_key))?.apply {
                setSummary(SetKeyUtil.getKeyName(skkPrefs.kanaKey))
                setOnPreferenceClickListener {
                    setSummary("Push any key...")
                    (requireActivity() as SKKSettingsActivity).keyPref = this
                    true
                }
            }

            findPreference<Preference>(getString(R.string.prefkey_cancel_key))?.apply {
                setSummary(SetKeyUtil.getKeyName(skkPrefs.cancelKey))
                setOnPreferenceClickListener {
                    setSummary("Push any key...")
                    (requireActivity() as SKKSettingsActivity).keyPref = this
                    true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        supportFragmentManager.beginTransaction()
            .replace(binding.content.id, SettingsMainFragment())
            .commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        // 初回起動時などキーボードが登録されていない場合のチェック
        // この前に権限要求が表示されていても、これはこれで表示されるので大丈夫のはず
        val imManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val imList = imManager.enabledInputMethodList
        if ("${packageName}/.SKKService" !in imList.map { it.id })
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyPref == null) {
            return super.dispatchKeyEvent(event)
        }

        dlog("dispatchKeyEvent($event)")
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER -> false
            KeyEvent.KEYCODE_HOME -> true
            else -> if (event.action == KeyEvent.ACTION_DOWN) {
                val key = SetKeyUtil.encodeKey(event)
                val name = SetKeyUtil.getKeyName(key)
                if (!name.endsWith("Unknown")) {
                    PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .edit().putInt(keyPref!!.key, key).apply()
                    keyPref!!.setSummary(name)
                    MainScope().launch(Dispatchers.Default) {
                        delay(500) // UP で何か実行されてしまわないように少し待つ
                        keyPref = null
                    }
                    true
                } else false
            } else false
        }
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    override fun onPause() {
        super.onPause()

        if (SKKService.isRunning()) {
            val intent = Intent(this@SKKSettingsActivity, SKKService::class.java)
            intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_READ_PREFS)
            startService(intent)
        }
    }
}