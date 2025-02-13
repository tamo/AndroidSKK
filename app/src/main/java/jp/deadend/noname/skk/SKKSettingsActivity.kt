package jp.deadend.noname.skk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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


class SKKSettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var binding: ActivitySettingsBinding
    class SettingsMainFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_main, rootKey)
        }
    }
    class SettingsSoftKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_softkey, rootKey)
        }
    }
    class SettingsHardKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_hardkey, rootKey)
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is SetKeyPreference) {
                val dialogFragment = SetKeyDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, "dialog")
            } else {
                super.onDisplayPreferenceDialog(preference)
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

        // 触感のプレビュー
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .registerOnSharedPreferenceChangeListener { prefs, key ->
                if (key == applicationContext.resources.getString(R.string.prefkey_haptic)) {
                    val haptic = prefs.getInt(key, 1)
                    ViewCompat.performHapticFeedback(binding.root, haptic)
                }
            }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val args = pref.extras
        val className = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, className)
        fragment.arguments = args
        fragment.setTargetFragment(caller, 0)
        supportFragmentManager.beginTransaction()
            .replace(binding.content.id, fragment)
            .addToBackStack(null)
            .commit()

        return true
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