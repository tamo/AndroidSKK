package jp.deadend.noname.skk

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min

class SKKPrefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources

    // prefs_main
    var kutoutenType: String
        get() = prefs.getString(res.getString(R.string.pref_kutouten_type), null) ?: "jp"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_kutouten_type), value)
        }

    var prefixMark: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_prefix_mark), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_prefix_mark), value)
        }

    var candidatesSize: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_size), 30)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_candidates_size), value)
        }

    var candidatesNormalLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_normal_lines), 2)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_candidates_normal_lines), value)
        }

    var candidatesEmojiLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_emoji_lines), 4)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_candidates_emoji_lines), value)
        }

    // 辞書管理
    val defaultDictOrder =
        "ユーザー辞書/${res.getString(R.string.dict_name_user)}/絵文字辞書/${res.getString(R.string.dict_name_emoji)}/"
    var dictOrder: String
        get() = prefs.getString(res.getString(R.string.pref_dict_order), defaultDictOrder)
            ?: defaultDictOrder
        set(value) = prefs.edit { putString(res.getString(R.string.pref_dict_order), value) }

    // prefs_hard_key
    var kanaKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_kana_key), 612) // 612はCtrl+j
        set(value) = prefs.edit { putInt(res.getString(R.string.pref_kana_key), value) }

    var toggleKanaKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_toggle_kana_key), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_toggle_kana_key), value)
        }

    var cancelKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_cancel_key), 564) // 564はCtrl+g
        set(value) = prefs.edit { putInt(res.getString(R.string.pref_cancel_key), value) }

    var useCandidatesView: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_candidates_view), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_candidates_view), value)
        }

    var useStickyMeta: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sticky_meta), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_sticky_meta), value)
        }

    var useSandS: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sands), false)
        set(value) = prefs.edit { putBoolean(res.getString(R.string.pref_sands), value) }

    // prefs_soft_key
    var useSoftKey: String
        get() = prefs.getString(res.getString(R.string.pref_use_soft_key), null) ?: "auto"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_use_soft_key), value)
        }

    var showStatusIcon: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_show_status_icon), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_show_status_icon), value)
        }

    var haptic: Int
        get() = prefs.getInt(res.getString(R.string.pref_haptic), 1)
        set(value) = prefs.edit { putInt(res.getString(R.string.pref_haptic), value) }

    var preferFlick: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_flick), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_flick), value)
        }

    var preferGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_godan), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_godan), value)
        }

    var simpleGodan: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_godan_simple), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_godan_simple), value)
        }

    var godanQwerty: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_godan_qwerty), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_godan_qwerty), value)
        }

    var swapQCxl: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_godan_swap_qc), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_godan_swap_qc), value)
        }

    var usePopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_popup), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_popup), value)
        }

    var useFixedPopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_fixed_popup), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_fixed_popup), value)
        }

    var useSoftCancelKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_soft_cancel_key), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_soft_cancel_key), value)
        }

    var useSoftTransKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_soft_trans_key), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_soft_trans_key), value)
        }

    var useSmallK: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_small_k), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_small_k), value)
        }

    var changeShift: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_exchange_shift_kana), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_exchange_shift_kana), value)
        }

    var flickSensitivity: Int
        get() = prefs.getInt(res.getString(R.string.pref_flick_sensitivity), 24)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_flick_sensitivity), value)
        }

    var useMiniKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_mini_keyboard), true)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_mini_keyboard), value)
        }

    var theme: String
        get() = prefs.getString(res.getString(R.string.pref_theme), null) ?: "default"
        set(value) = prefs.edit { putString(res.getString(R.string.pref_theme), value) }

    var useInset: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_inset), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_use_inset), value)
        }

    var backgroundAlpha: Int
        get() = prefs.getInt(res.getString(R.string.pref_background_alpha), 100)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_background_alpha), value)
        }

    val activeAlpha = 255 // いつか可変にしたくなるかもしれないのでここに入れておく
    val inactiveAlpha = 96 // 不透明度なので、小さいほど薄く、存在感がなくなる

    var originalColor: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_ignore_high_contrast), false)
        set(value) = prefs.edit {
            putBoolean(res.getString(R.string.pref_ignore_high_contrast), value)
        }

    var keyLabelZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_label_zoom), 100)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_label_zoom), value)
        }

    var keyHeightPort: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_port), 30)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_height_port), value)
        }

    var keyHeightLand: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_land), 50)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_height_land), value)
        }

    var keyPaddingBottom: Int
        get() = prefs.getInt(
            res.getString(R.string.pref_key_padding_bottom),
            if (Build.VERSION.SDK_INT >= 35) 15 else 0
        )
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_padding_bottom), value)
        }

    var keyWidthQwertyZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_width_qwerty_zoom), 200)
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_width_qwerty_zoom), value)
        }

    var typeURI: String
        get() = prefs.getString(res.getString(R.string.pref_type_uri), null) ?: "ignore"
        set(value) = prefs.edit { putString(res.getString(R.string.pref_type_uri), value) }

    var typeNumber: String
        get() = prefs.getString(res.getString(R.string.pref_type_number), null) ?: "flick-num"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_type_number), value)
        }

    var typePhone: String
        get() = prefs.getString(res.getString(R.string.pref_type_phone), null) ?: "flick-num"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_type_phone), value)
        }

    var typePassword: String
        get() = prefs.getString(res.getString(R.string.pref_type_password), null) ?: "qwerty"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_type_password), value)
        }

    var typeText: String
        get() = prefs.getString(res.getString(R.string.pref_type_text), null) ?: "ignore"
        set(value) = prefs.edit {
            putString(res.getString(R.string.pref_type_text), value)
        }

    // CandidatesViewContainer
    var keyCenterPort: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_port), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit {
            putFloat(res.getString(R.string.pref_key_center_port), value.coerceIn(0f, 1f))
        }

    var keyCenterLand: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_land), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit {
            putFloat(res.getString(R.string.pref_key_center_land), value.coerceIn(0f, 1f))
        }

    var keyWidthPort: Int
        get() {
            val screenWidth = res.displayMetrics.run { min(widthPixels, heightPixels) }
            return prefs.getInt(res.getString(R.string.pref_key_width_port), screenWidth)
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_width_port), value)
        }

    var keyWidthLand: Int
        get() {
            val screenWidth = res.displayMetrics.run { max(widthPixels, heightPixels) }
            return prefs.getInt(
                res.getString(R.string.pref_key_width_land),
                screenWidth * 3 / 10
            )
                .coerceAtLeast(res.getDimensionPixelSize(R.dimen.keyboard_minimum_width))
        }
        set(value) = prefs.edit {
            putInt(res.getString(R.string.pref_key_width_land), value)
        }

}
