package jp.deadend.noname.skk

import android.content.Context
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min

class SKKPrefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources

//    var prefsVersion: Int
//        get() = prefs.getInt(res.getString(R.string.pref_key_prefversion), -1)
//        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_prefversion), value).apply()

    // prefs_main
    var kutoutenType: String
        get() = prefs.getString(res.getString(R.string.prefkey_kutouten_type), null) ?: "jp"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_kutouten_type), value).apply()

    var prefixMark: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_prefix_mark), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_prefix_mark), value).apply()

    var candidatesSize: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_candidates_size), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_candidates_size), value).apply()

    var candidatesNormalLines: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_candidates_normal_lines), 1)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_candidates_normal_lines), value).apply()

    var candidatesEmojiLines: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_candidates_emoji_lines), 4)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_candidates_emoji_lines), value).apply()

    // prefs_hardkey
    var kanaKey: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_kana_key), 612) // 612はCtrl+j
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_kana_key), value).apply()

    var toggleKanaKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_toggle_kana_key), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_toggle_kana_key), value).apply()

    var cancelKey: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_cancel_key), 564) // 564はCtrl+g
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_cancel_key), value).apply()

    var useCandidatesView: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_candidates_view), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_candidates_view), value).apply()

    var useStickyMeta: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_sticky_meta), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_sticky_meta), value).apply()

    var useSandS: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_sands), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_sands), value).apply()

    // prefs_softkey
    var useSoftKey: String
        get() = prefs.getString(res.getString(R.string.prefkey_use_softkey), null) ?: "auto"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_use_softkey), value).apply()

    var showStatusIcon: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_show_status_icon), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_show_status_icon), value).apply()

    var preferFlick: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_flick), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_flick), value).apply()

    var preferGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_godan), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_godan), value).apply()

    var simpleGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_godan_simple), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_godan_simple), value).apply()

    var swapQCxl: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_godan_swap_qc), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_godan_swap_qc), value).apply()

    var usePopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_popup), value).apply()

    var useFixedPopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_fixed_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_fixed_popup), value).apply()

    var useSoftCancelKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_soft_cancel_key), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_soft_cancel_key), value).apply()

    var useSoftTransKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_soft_trans_key), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_soft_trans_key), value).apply()

    var changeShift: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_exchange_shift_kana), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_exchange_shift_kana), value).apply()

    var flickSensitivity: String
        get() = prefs.getString(res.getString(R.string.prefkey_flick_sensitivity2), null) ?: "high"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_flick_sensitivity2), value).apply()

    var useMiniKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_mini_keyboard), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_mini_keyboard), value).apply()

    var theme: String
        get() = prefs.getString(res.getString(R.string.prefkey_theme), null) ?: "default"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_theme), value).apply()

    var useInset: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_inset), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_inset), value).apply()

    var backgroundAlpha: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_background_alpha), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_background_alpha), value).apply()

    var originalColor: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_ignore_high_contrast), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_ignore_high_contrast), value).apply()

    var keyHeightPort: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_height_port), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_height_port), value).apply()

    var keyHeightLand: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_height_land), 50)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_height_land), value).apply()

    var keyPaddingBottom: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_padding_bottom), 0)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_padding_bottom), value).apply()

    var keyWidthQwertyZoom: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_width_qwerty_zoom), 200)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_width_qwerty_zoom), value).apply()

    var typeURI: String
        get() = prefs.getString(res.getString(R.string.prefkey_type_uri), null) ?: "ignore"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_type_uri), value).apply()

    var typeNumber: String
        get() = prefs.getString(res.getString(R.string.prefkey_type_number), null) ?: "flick-num"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_type_number), value).apply()

    var typePhone: String
        get() = prefs.getString(res.getString(R.string.prefkey_type_phone), null) ?: "flick-num"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_type_phone), value).apply()

    var typePassword: String
        get() = prefs.getString(res.getString(R.string.prefkey_type_password), null) ?: "qwerty"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_type_password), value).apply()

    var typeText: String
        get() = prefs.getString(res.getString(R.string.prefkey_type_text), null) ?: "ignore"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_type_text), value).apply()

    // CandidateViewContainer
    var keyCenterPort: Float
        get() = prefs.getFloat(res.getString(R.string.prefkey_key_center_port), 0.5f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(res.getString(R.string.prefkey_key_center_port), value.coerceIn(0f, 1f)).apply()

    var keyCenterLand: Float
        get() = prefs.getFloat(res.getString(R.string.prefkey_key_center_land), 0.5f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(res.getString(R.string.prefkey_key_center_land), value.coerceIn(0f, 1f)).apply()

    var keyWidthPort: Int
        get() {
            val screenWidth = res.displayMetrics.run { min(widthPixels, heightPixels) }
            return prefs.getInt(res.getString(R.string.prefkey_key_width_port), screenWidth)
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_width_port), value).apply()

    var keyWidthLand: Int
        get() {
            val screenWidth = res.displayMetrics.run { max(widthPixels, heightPixels) }
            return prefs.getInt(res.getString(R.string.prefkey_key_width_land), screenWidth * 3 / 10)
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_width_land), value).apply()

}
