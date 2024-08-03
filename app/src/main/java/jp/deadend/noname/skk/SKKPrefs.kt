package jp.deadend.noname.skk

import android.content.Context
import androidx.preference.PreferenceManager

class SKKPrefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources

//    var prefsVersion: Int
//        get() = prefs.getInt(res.getString(R.string.pref_key_prefversion), -1)
//        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_prefversion), value).apply()

    var kutoutenType: String
        get() = prefs.getString(res.getString(R.string.prefkey_kutouten_type), null) ?: "en"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_kutouten_type), value).apply()

    var useCandidatesView: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_candidates_view), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_candidates_view), value).apply()

    var candidatesSize: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_candidates_size), 18)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_candidates_size), value).apply()

    var kanaKey: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_kana_key), 612) // 612はCtrl+j
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_kana_key), value).apply()

    var cancelKey: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_cancel_key), 564) // 564はCtrl+g
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_cancel_key), value).apply()

    var toggleKanaKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_toggle_kana_key), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_toggle_kana_key), value).apply()

    var flickSensitivity: String
        get() = prefs.getString(res.getString(R.string.prefkey_flick_sensitivity2), null) ?: "mid"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_flick_sensitivity2), value).apply()

    var curveSensitivity: String
        get() = prefs.getString(res.getString(R.string.prefkey_curve_sensitivity), null) ?: "high"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_curve_sensitivity), value).apply()

    var theme: String
        get() = prefs.getString(res.getString(R.string.prefkey_theme), null) ?: "default"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_theme), value).apply()

    var useInset: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_inset), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_inset), value).apply()

    var useSoftKey: String
        get() = prefs.getString(res.getString(R.string.prefkey_use_softkey), null) ?: "auto"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_use_softkey), value).apply()

    var usePopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_popup), value).apply()

    var useFixedPopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_fixed_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_fixed_popup), value).apply()

    var useSoftCancelKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_use_soft_cancel_key), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_use_soft_cancel_key), value).apply()

    var backgroundAlpha: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_background_alpha), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_background_alpha), value).apply()

    var keyHeightPort: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_height_port), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_height_port), value).apply()

    var keyHeightLand: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_height_land), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_height_land), value).apply()

    var keyWidthPort: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_width_port), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_width_port), value).apply()

    var keyWidthLand: Int
        get() = prefs.getInt(res.getString(R.string.prefkey_key_width_land), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.prefkey_key_width_land), value).apply()

    var keyPosition: String
        get() = prefs.getString(res.getString(R.string.prefkey_key_position), null) ?: "center"
        set(value) = prefs.edit().putString(res.getString(R.string.prefkey_key_position), value).apply()

    var useStickyMeta: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_sticky_meta), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_sticky_meta), value).apply()

    var useSandS: Boolean
        get() = prefs.getBoolean(res.getString(R.string.prefkey_sands), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.prefkey_sands), value).apply()

}
