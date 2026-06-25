package jp.deadend.noname.skk

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import jp.deadend.noname.skk.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds


class SKKSettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var binding: ActivitySettingsBinding
    var keyPref: Preference? = null

    class SettingsMainFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_main, rootKey)

            findPreference<Preference>(getString(R.string.pref_log_viewer))?.apply {
                var lastTapTime = 0L
                var tapCount = 0
                var loadJob: Job? = null

                setOnPreferenceClickListener {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) tapCount++ else tapCount = 1
                    lastTapTime = now
                    if (tapCount > 2) {
                        SKKLog.saveCrashReport(context, Throwable("擬似クラッシュ"))
                        tapCount = 0
                    }

                    val loadLogListener = onPreferenceClickListener
                    val oldIcon = icon
                    val oldTitle = title

                    loadJob?.cancel()
                    loadJob = lifecycleScope.launch {
                        delay(300.milliseconds)
                        val text = withContext(Dispatchers.IO) {
                            val dir = context.getExternalFilesDir(null)
                                ?: return@withContext "ログの場所が開けません"
                            dir.listFiles { _, name ->
                                name.startsWith("SKK_strace_")
                            }?.maxByOrNull { it.lastModified() }?.let { latest ->
                                runCatching { latest.readText() }.getOrNull()
                            }.orEmpty() + SKKLog.recentLog()
                        }
                        if (text.isEmpty()) return@launch

                        icon = null
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        title = text
                        setOnPreferenceClickListener {
                            val now2 = System.currentTimeMillis()
                            if (now2 - lastTapTime < 300) tapCount++ else tapCount = 1
                            lastTapTime = now2
                            if (tapCount > 2) {
                                loadLogListener?.onPreferenceClick(this@apply)
                                return@setOnPreferenceClickListener true
                            }

                            loadJob?.cancel()
                            loadJob = lifecycleScope.launch {
                                delay(300.milliseconds)
                                (context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("SKK log", title))

                                onPreferenceClickListener = loadLogListener
                                icon = oldIcon
                                isIconSpaceReserved = true
                                isSingleLineTitle = true
                                title = oldTitle
                            }
                            true
                        }
                    }
                    true
                }
            }
        }
    }

    class SettingsSoftKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_soft_key, rootKey)
        }
    }

    class SettingsCommonFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_common, rootKey)
            updateThumbnail()

            // 触感のプレビュー
            findPreference<Preference>(getString(R.string.pref_haptic))
                ?.setOnPreferenceChangeListener { _, haptic ->
                    ViewCompat.performHapticFeedback(
                        requireActivity().window.decorView,
                        haptic as Int
                    )
                    true
                }

            findPreference<Preference>(getString(R.string.pref_background_image))?.apply {
                setOnPreferenceClickListener {
                    if (skkPrefs.backgroundImage == null)
                        pickMedia()
                    else AlertDialog.Builder(requireContext())
                        .setTitle(title)
                        .setItems(arrayOf("ファイルを選択する", "画像なしにする")) { _, which ->
                            when (which) {
                                0 -> pickMedia()
                                1 -> {
                                    skkPrefs.backgroundImage = null
                                    updateThumbnail()
                                }
                            }
                        }.show()
                    true
                }
            }
        }

        private fun pickMedia() =
            pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

        private val pickMediaLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri ?: return@registerForActivityResult

                lifecycleScope.launch {
                    val isValid = withContext(Dispatchers.IO) {
                        runCatching {
                            val resolver = requireContext().contentResolver
                            resolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            CroppedDrawable.decode(resolver, uri, 100, 100) != null
                        }.getOrElse { tr ->
                            SKKLog.w("Invalid image selected: $uri", tr)
                            false
                        }
                    }

                    if (isValid) {
                        skkPrefs.backgroundImage = uri.toString()
                        updateThumbnail()
                    } else Toast.makeText(
                        requireContext(), R.string.error_invalid_image, Toast.LENGTH_LONG
                    ).show()
                }
            }

        private fun updateThumbnail() = MainScope().launch {
            findPreference<Preference>(getString(R.string.pref_background_image))?.apply {
                icon = skkPrefs.backgroundImage?.toUri()?.let { uri ->
                    summary = withContext(Dispatchers.IO) { getFileName(uri) }
                    withContext(Dispatchers.IO) {
                        val size = (48 * resources.displayMetrics.density).toInt()
                        CroppedDrawable.decode(requireContext().contentResolver, uri, size, size)
                            ?.toDrawable(resources)
                    }
                }
                if (icon == null) summary = "なし"
            }
        }

        private fun getFileName(uri: Uri): String {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            return cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) it.getString(index) else null
                } else null
            } ?: uri.lastPathSegment ?: uri.toString()
        }
    }

    class SettingsHardKeyFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_hard_key, rootKey)

            registerKeyPref(R.string.pref_kana_key, skkPrefs.kanaKey)
            registerKeyPref(R.string.pref_cancel_key, skkPrefs.cancelKey)
            registerKeyPref(R.string.pref_katakana_key, skkPrefs.katakanaKey)
            registerKeyPref(R.string.pref_ascii_key, skkPrefs.asciiKey)
            registerKeyPref(R.string.pref_zenkaku_key, skkPrefs.zenkakuKey)
            registerKeyPref(R.string.pref_abbrev_key, skkPrefs.abbrevKey)
            registerKeyPref(R.string.pref_hankaku_kana_key, skkPrefs.hankakuKanaKey)
            registerKeyPref(R.string.pref_nav_line_start_key, skkPrefs.navLineStartKey)
            registerKeyPref(R.string.pref_nav_line_end_key, skkPrefs.navLineEndKey)
            registerKeyPref(R.string.pref_nav_forward_key, skkPrefs.navForwardKey)
            registerKeyPref(R.string.pref_nav_backward_key, skkPrefs.navBackwardKey)
        }

        private fun registerKeyPref(prefKeyResId: Int, currentValue: Int) {
            findPreference<Preference>(getString(prefKeyResId))?.apply {
                summary =
                    getKeyName(currentValue).ifEmpty { getString(R.string.label_disabled_key) }
                setOnPreferenceClickListener {
                    summary = getString(R.string.label_push_any)
                    (requireActivity() as SKKSettingsActivity).keyPref = this
                    true
                }
            }
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference)
            : Boolean {
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, pref.fragment!!)
            .apply { arguments = pref.extras }

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(
                android.R.anim.slide_in_left, android.R.anim.slide_out_right,
                android.R.anim.slide_in_left, android.R.anim.slide_out_right
            )
            replace(binding.content.id, fragment)
            addToBackStack(null)
        }

        title = pref.title
        return true
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

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(binding.content.id, SettingsMainFragment())
            }
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.label_pref_activity)
            }
        }

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyPref.let { pref ->
        if (pref == null) return super.dispatchKeyEvent(event)
        SKKLog.d("dispatchKeyEvent($event)")
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> false
            KeyEvent.KEYCODE_HOME -> true
            KeyEvent.KEYCODE_ESCAPE -> if (event.action == KeyEvent.ACTION_DOWN) {
                PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
                    putInt(pref.key, 0)
                }
                pref.setSummary(getString(R.string.label_disabled_key))
                MainScope().launch(Dispatchers.Default) {
                    delay(500.milliseconds)
                    keyPref = null
                }
                true
            } else false

            else -> if (event.action == KeyEvent.ACTION_DOWN) {
                val key = encodeKey(event)
                val name = getKeyName(key)
                if (name.isEmpty()) return false
                PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
                    putInt(pref.key, key)
                }
                pref.setSummary(name)
                MainScope().launch(Dispatchers.Default) {
                    delay(500.milliseconds) // UP で何か実行されてしまわないように少し待つ
                    keyPref = null
                }
                true
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
