package jp.deadend.noname.skk

import android.content.Context
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min

class SKKPrefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources

    // prefs_main
    var kutoutenType: String
        get() = prefs.getString(res.getString(R.string.pref_kutouten_type), null) ?: "jp"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_kutouten_type), value)
            .apply()

    var prefixMark: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_prefix_mark), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_prefix_mark), value)
            .apply()

    var candidatesSize: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_size), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_candidates_size), value)
            .apply()

    var candidatesNormalLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_normal_lines), 2)
        set(value) = prefs.edit()
            .putInt(res.getString(R.string.pref_candidates_normal_lines), value).apply()

    var candidatesEmojiLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_emoji_lines), 4)
        set(value) = prefs.edit()
            .putInt(res.getString(R.string.pref_candidates_emoji_lines), value).apply()

    // 辞書管理
    val defaultDictOrder =
        "ユーザー辞書/${res.getString(R.string.dict_name_user)}/絵文字辞書/${res.getString(R.string.dict_name_emoji)}/"
    var dictOrder: String
        get() = prefs.getString(res.getString(R.string.pref_dict_order), defaultDictOrder)
            ?: defaultDictOrder
        set(value) = prefs.edit().putString(res.getString(R.string.pref_dict_order), value).apply()

    // prefs_hard_key
    var kanaKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_kana_key), 612) // 612はCtrl+j
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_kana_key), value).apply()

    var toggleKanaKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_toggle_kana_key), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_toggle_kana_key), value)
            .apply()

    var cancelKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_cancel_key), 564) // 564はCtrl+g
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_cancel_key), value).apply()

    var useCandidatesView: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_candidates_view), true)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_use_candidates_view), value).apply()

    var useStickyMeta: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sticky_meta), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_sticky_meta), value)
            .apply()

    var useSandS: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sands), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_sands), value).apply()

    // prefs_soft_key
    var useSoftKey: String
        get() = prefs.getString(res.getString(R.string.pref_use_soft_key), null) ?: "auto"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_use_soft_key), value)
            .apply()

    var showStatusIcon: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_show_status_icon), true)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_show_status_icon), value).apply()

    var haptic: Int
        get() = prefs.getInt(res.getString(R.string.pref_haptic), 1)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_haptic), value).apply()

    var preferFlick: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_flick), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_use_flick), value)
            .apply()

    var preferGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_godan), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_use_godan), value)
            .apply()

    var simpleGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_godan_simple), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_godan_simple), value)
            .apply()

    var swapQCxl: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_godan_swap_qc), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_godan_swap_qc), value)
            .apply()

    var usePopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_use_popup), value)
            .apply()

    var useFixedPopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_fixed_popup), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_fixed_popup), value)
            .apply()

    var useSoftCancelKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_soft_cancel_key), false)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_use_soft_cancel_key), value).apply()

    var useSoftTransKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_soft_trans_key), true)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_use_soft_trans_key), value).apply()

    var changeShift: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_exchange_shift_kana), true)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_exchange_shift_kana), value).apply()

    var flickSensitivity: Int
        get() = prefs.getInt(res.getString(R.string.pref_flick_sensitivity), 24)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_flick_sensitivity), value)
            .apply()

    var useMiniKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_mini_keyboard), true)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_mini_keyboard), value)
            .apply()

    var theme: String
        get() = prefs.getString(res.getString(R.string.pref_theme), null) ?: "default"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_theme), value).apply()

    var useInset: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_inset), false)
        set(value) = prefs.edit().putBoolean(res.getString(R.string.pref_use_inset), value)
            .apply()

    var backgroundAlpha: Int
        get() = prefs.getInt(res.getString(R.string.pref_background_alpha), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_background_alpha), value)
            .apply()

    val activeAlpha = 255 // いつか可変にしたくなるかもしれないのでここに入れておく
    val inactiveAlpha = 96 // 不透明度なので、小さいほど薄く、存在感がなくなる

    var originalColor: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_ignore_high_contrast), false)
        set(value) = prefs.edit()
            .putBoolean(res.getString(R.string.pref_ignore_high_contrast), value).apply()

    var keyLabelZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_label_zoom), 100)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_label_zoom), value)
            .apply()

    var keyHeightPort: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_port), 30)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_height_port), value)
            .apply()

    var keyHeightLand: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_land), 50)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_height_land), value)
            .apply()

    var keyPaddingBottom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_padding_bottom), 0)
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_padding_bottom), value)
            .apply()

    var keyWidthQwertyZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_width_qwerty_zoom), 200)
        set(value) = prefs.edit()
            .putInt(res.getString(R.string.pref_key_width_qwerty_zoom), value).apply()

    var typeURI: String
        get() = prefs.getString(res.getString(R.string.pref_type_uri), null) ?: "ignore"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_type_uri), value).apply()

    var typeNumber: String
        get() = prefs.getString(res.getString(R.string.pref_type_number), null) ?: "flick-num"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_type_number), value)
            .apply()

    var typePhone: String
        get() = prefs.getString(res.getString(R.string.pref_type_phone), null) ?: "flick-num"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_type_phone), value)
            .apply()

    var typePassword: String
        get() = prefs.getString(res.getString(R.string.pref_type_password), null) ?: "qwerty"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_type_password), value)
            .apply()

    var typeText: String
        get() = prefs.getString(res.getString(R.string.pref_type_text), null) ?: "ignore"
        set(value) = prefs.edit().putString(res.getString(R.string.pref_type_text), value)
            .apply()

    // CandidatesViewContainer
    var keyCenterPort: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_port), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit()
            .putFloat(res.getString(R.string.pref_key_center_port), value.coerceIn(0f, 1f))
            .apply()

    var keyCenterLand: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_land), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit()
            .putFloat(res.getString(R.string.pref_key_center_land), value.coerceIn(0f, 1f))
            .apply()

    var keyWidthPort: Int
        get() {
            val screenWidth = res.displayMetrics.run { min(widthPixels, heightPixels) }
            return prefs.getInt(res.getString(R.string.pref_key_width_port), screenWidth)
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_width_port), value)
            .apply()

    var keyWidthLand: Int
        get() {
            val screenWidth = res.displayMetrics.run { max(widthPixels, heightPixels) }
            return prefs.getInt(
                res.getString(R.string.pref_key_width_land),
                screenWidth * 3 / 10
            )
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit().putInt(res.getString(R.string.pref_key_width_land), value)
            .apply()

}
