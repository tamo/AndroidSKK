package jp.deadend.noname.skk

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class SKKPrefs(context: Context) {
    internal val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val res = context.resources

    // prefs_main
    var kutoutenType: String
        get() = prefs.getString(res.getString(R.string.pref_kutouten_type), null) ?: "jp"
        set(value) = prefs.edit {
            if (value == "jp") remove(res.getString(R.string.pref_kutouten_type))
            else putString(res.getString(R.string.pref_kutouten_type), value)
        }

    var font: String
        get() = prefs.getString(res.getString(R.string.pref_font), null) ?: "default"
        set(value) = prefs.edit {
            if (value == "default") remove(res.getString(R.string.pref_font))
            else putString(res.getString(R.string.pref_font), value)
        }

    internal val typeface: Typeface
        get() = when (font) {
            "sans_serif" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

    var prefixMark: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_prefix_mark), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_prefix_mark))
            else putBoolean(res.getString(R.string.pref_prefix_mark), false)
        }

    var candidatesSize: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_size), 30)
        set(value) = prefs.edit {
            if (value == 30) remove(res.getString(R.string.pref_candidates_size))
            else putInt(res.getString(R.string.pref_candidates_size), value)
        }

    val annotationRatio: Float = 0.5f

    var fuzzySuggestion: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_fuzzy_suggestion), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_fuzzy_suggestion))
            else putBoolean(res.getString(R.string.pref_fuzzy_suggestion), false)
        }

    var fuzzierSuggestion: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_fuzzier_suggestion), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_fuzzier_suggestion))
            else putBoolean(res.getString(R.string.pref_fuzzier_suggestion), true)
        }

    var completeOkuri: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_complete_okuri), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_complete_okuri))
            else putBoolean(res.getString(R.string.pref_complete_okuri), true)
        }

    var candidatesNormalLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_normal_lines), 2)
        set(value) = prefs.edit {
            if (value == 2) remove(res.getString(R.string.pref_candidates_normal_lines))
            else putInt(res.getString(R.string.pref_candidates_normal_lines), value)
        }

    var candidatesEmojiLines: Int
        get() = prefs.getInt(res.getString(R.string.pref_candidates_emoji_lines), 4)
        set(value) = prefs.edit {
            if (value == 4) remove(res.getString(R.string.pref_candidates_emoji_lines))
            else putInt(res.getString(R.string.pref_candidates_emoji_lines), value)
        }

    var candidatesMinHeight: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_candidates_min_height), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_candidates_min_height))
            else putBoolean(res.getString(R.string.pref_candidates_min_height), true)
        }

    var candidatesReserveLines: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_candidates_reserve_lines), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_candidates_reserve_lines))
            else putBoolean(res.getString(R.string.pref_candidates_reserve_lines), false)
        }

    var logPrivacy: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_log_privacy), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_log_privacy))
            else putBoolean(res.getString(R.string.pref_log_privacy), true)
        }

    // prefs_hard_key
    var useCandidatesView: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_candidates_view), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_use_candidates_view))
            else putBoolean(res.getString(R.string.pref_use_candidates_view), false)
        }

    var kanaKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_kana_key), CTRL_PRESSED or 'j'.code)
        set(value) = prefs.edit {
            if (value == CTRL_PRESSED or 'j'.code) remove(res.getString(R.string.pref_kana_key))
            else putInt(res.getString(R.string.pref_kana_key), value)
        }

    var toggleKanaKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_toggle_kana_key), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_toggle_kana_key))
            else putBoolean(res.getString(R.string.pref_toggle_kana_key), true)
        }

    var cancelKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_cancel_key), CTRL_PRESSED or 'g'.code)
        set(value) = prefs.edit {
            if (value == CTRL_PRESSED or 'g'.code) remove(res.getString(R.string.pref_cancel_key))
            else putInt(res.getString(R.string.pref_cancel_key), value)
        }

    var katakanaKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_katakana_key), 'q'.code)
        set(value) = prefs.edit {
            if (value == 'q'.code) remove(res.getString(R.string.pref_katakana_key))
            else putInt(res.getString(R.string.pref_katakana_key), value)
        }

    var asciiKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_ascii_key), 'l'.code)
        set(value) = prefs.edit {
            if (value == 'l'.code) remove(res.getString(R.string.pref_ascii_key))
            else putInt(res.getString(R.string.pref_ascii_key), value)
        }

    var zenkakuKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_zenkaku_key), SHIFT_PRESSED or 'l'.code)
        set(value) = prefs.edit {
            if (value == SHIFT_PRESSED or 'l'.code) remove(res.getString(R.string.pref_zenkaku_key))
            else putInt(res.getString(R.string.pref_zenkaku_key), value)
        }

    var abbrevKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_abbrev_key), '/'.code)
        set(value) = prefs.edit {
            if (value == '/'.code) remove(res.getString(R.string.pref_abbrev_key))
            else putInt(res.getString(R.string.pref_abbrev_key), value)
        }

    var hankakuKanaKey: Int
        get() = prefs.getInt(
            res.getString(R.string.pref_hankaku_kana_key),
            CTRL_PRESSED or 'q'.code
        )
        set(value) = prefs.edit {
            if (value == CTRL_PRESSED or 'q'.code) remove(res.getString(R.string.pref_hankaku_kana_key))
            else putInt(res.getString(R.string.pref_hankaku_kana_key), value)
        }

    fun isModeKey(keyCode: Int): Boolean = when (keyCode) {
        katakanaKey, asciiKey, zenkakuKey, abbrevKey, hankakuKanaKey -> true
        else -> false
    }

    var useStickyMeta: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sticky_meta), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_sticky_meta))
            else putBoolean(res.getString(R.string.pref_sticky_meta), true)
        }

    var useSandS: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sands), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_sands))
            else putBoolean(res.getString(R.string.pref_sands), true)
        }

    var sandSInAscii: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_sands_in_ascii), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_sands_in_ascii))
            else putBoolean(res.getString(R.string.pref_sands_in_ascii), true)
        }

    var navLineStartKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_nav_line_start_key), 0)
        set(value) = prefs.edit {
            if (value == 0) remove(res.getString(R.string.pref_nav_line_start_key))
            else putInt(res.getString(R.string.pref_nav_line_start_key), value)
        }

    var navLineEndKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_nav_line_end_key), 0)
        set(value) = prefs.edit {
            if (value == 0) remove(res.getString(R.string.pref_nav_line_end_key))
            else putInt(res.getString(R.string.pref_nav_line_end_key), value)
        }

    var navForwardKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_nav_forward_key), 0)
        set(value) = prefs.edit {
            if (value == 0) remove(res.getString(R.string.pref_nav_forward_key))
            else putInt(res.getString(R.string.pref_nav_forward_key), value)
        }

    var navBackwardKey: Int
        get() = prefs.getInt(res.getString(R.string.pref_nav_backward_key), 0)
        set(value) = prefs.edit {
            if (value == 0) remove(res.getString(R.string.pref_nav_backward_key))
            else putInt(res.getString(R.string.pref_nav_backward_key), value)
        }

    var emacsNavInAscii: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_emacs_nav_in_ascii), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_emacs_nav_in_ascii))
            else putBoolean(res.getString(R.string.pref_emacs_nav_in_ascii), true)
        }

    // prefs_soft_key
    var useSoftKey: String
        get() = prefs.getString(res.getString(R.string.pref_use_soft_key), null) ?: "auto"
        set(value) = prefs.edit {
            if (value == "auto") remove(res.getString(R.string.pref_use_soft_key))
            else putString(res.getString(R.string.pref_use_soft_key), value)
        }

    var softKeyboardType: String
        get() = prefs.getString(res.getString(R.string.pref_soft_keyboard_type), null) ?: "switch"
        set(value) = prefs.edit {
            if (value == "switch") remove(res.getString(R.string.pref_soft_keyboard_type))
            else putString(res.getString(R.string.pref_soft_keyboard_type), value)
        }

    var showStatusIcon: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_show_status_icon), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_show_status_icon))
            else putBoolean(res.getString(R.string.pref_show_status_icon), false)
        }


    var usePopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_popup), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_use_popup))
            else putBoolean(res.getString(R.string.pref_use_popup), false)
        }

    var popupOnPress: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_popup_on_press), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_popup_on_press))
            else putBoolean(res.getString(R.string.pref_popup_on_press), false)
        }

    var useFixedPopup: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_fixed_popup), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_fixed_popup))
            else putBoolean(res.getString(R.string.pref_fixed_popup), false)
        }

    var fingerOffset: Int
        get() = prefs.getInt(res.getString(R.string.pref_finger_offset), 70)
        set(value) = prefs.edit {
            if (value == 70) remove(res.getString(R.string.pref_finger_offset))
            else putInt(res.getString(R.string.pref_finger_offset), value)
        }


    var useSmallK: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_small_k), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_use_small_k))
            else putBoolean(res.getString(R.string.pref_use_small_k), true)
        }


    var longPressTimeout: Int
        get() = prefs.getInt(res.getString(R.string.pref_long_press_timeout), 500)
        set(value) = prefs.edit {
            if (value == 500) remove(res.getString(R.string.pref_long_press_timeout))
            else putInt(res.getString(R.string.pref_long_press_timeout), value)
        }

    var useMiniKey: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_mini_keyboard), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_mini_keyboard))
            else putBoolean(res.getString(R.string.pref_mini_keyboard), true)
        }

    var flickSensitivity: Int
        get() = prefs.getInt(res.getString(R.string.pref_flick_sensitivity), 24)
        set(value) = prefs.edit {
            if (value == 24) remove(res.getString(R.string.pref_flick_sensitivity))
            else putInt(res.getString(R.string.pref_flick_sensitivity), value)
        }

    var gestureInsets: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_gesture_insets), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_gesture_insets))
            else putBoolean(res.getString(R.string.pref_gesture_insets), true)
        }

    var keyLabelZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_label_zoom), 100)
        set(value) = prefs.edit {
            if (value == 100) remove(res.getString(R.string.pref_key_label_zoom))
            else putInt(res.getString(R.string.pref_key_label_zoom), value)
        }

    var keyHeightPort: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_port), 30)
        set(value) = prefs.edit {
            if (value == 30) remove(res.getString(R.string.pref_key_height_port))
            else putInt(res.getString(R.string.pref_key_height_port), value)
        }

    var keyHeightLand: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_height_land), 50)
        set(value) = prefs.edit {
            if (value == 50) remove(res.getString(R.string.pref_key_height_land))
            else putInt(res.getString(R.string.pref_key_height_land), value)
        }

    var keyPaddingBottom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_padding_bottom), 15)
        set(value) = prefs.edit {
            if (value == 15) remove(res.getString(R.string.pref_key_padding_bottom))
            else putInt(res.getString(R.string.pref_key_padding_bottom), value)
        }

    var keyWidthQwertyZoom: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_width_qwerty_zoom), 200)
        set(value) = prefs.edit {
            if (value == 200) remove(res.getString(R.string.pref_key_width_qwerty_zoom))
            else putInt(res.getString(R.string.pref_key_width_qwerty_zoom), value)
        }

    var theme: String
        get() = prefs.getString(res.getString(R.string.pref_theme), null) ?: "default"
        set(value) = prefs.edit {
            if (value == "default") remove(res.getString(R.string.pref_theme))
            else putString(res.getString(R.string.pref_theme), value)
        }

    var useInset: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_inset), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_use_inset))
            else putBoolean(res.getString(R.string.pref_use_inset), true)
        }

    var backgroundAlpha: Int
        get() = prefs.getInt(res.getString(R.string.pref_background_alpha), 100)
        set(value) = prefs.edit {
            if (value == 100) remove(res.getString(R.string.pref_background_alpha))
            else putInt(res.getString(R.string.pref_background_alpha), value)
        }

    var backgroundImage: String?
        get() = prefs.getString(res.getString(R.string.pref_background_image), null)
        set(value) = prefs.edit {
            if (value == null) remove(res.getString(R.string.pref_background_image))
            else putString(res.getString(R.string.pref_background_image), value)
        }

    val activeAlpha get() = (backgroundAlpha * 255 / 100) // backgroundAlpha と違い 0-255 の範囲
    val inactiveAlpha get() = (backgroundAlpha * 255 / 100 / 2) // 小さいほど薄く、存在感がなくなる

    var originalColor: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_ignore_high_contrast), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_ignore_high_contrast))
            else putBoolean(res.getString(R.string.pref_ignore_high_contrast), true)
        }

    var typeURI: String
        get() = prefs.getString(res.getString(R.string.pref_type_uri), null) ?: "ignore"
        set(value) = prefs.edit {
            if (value == "ignore") remove(res.getString(R.string.pref_type_uri))
            else putString(res.getString(R.string.pref_type_uri), value)
        }

    var typeNumber: String
        get() = prefs.getString(res.getString(R.string.pref_type_number), null) ?: "flick-num"
        set(value) = prefs.edit {
            if (value == "flick-num") remove(res.getString(R.string.pref_type_number))
            else putString(res.getString(R.string.pref_type_number), value)
        }

    var typePhone: String
        get() = prefs.getString(res.getString(R.string.pref_type_phone), null) ?: "flick-num"
        set(value) = prefs.edit {
            if (value == "flick-num") remove(res.getString(R.string.pref_type_phone))
            else putString(res.getString(R.string.pref_type_phone), value)
        }

    var typePassword: String
        get() = prefs.getString(res.getString(R.string.pref_type_password), null) ?: "qwerty"
        set(value) = prefs.edit {
            if (value == "qwerty") remove(res.getString(R.string.pref_type_password))
            else putString(res.getString(R.string.pref_type_password), value)
        }

    var typeText: String
        get() = prefs.getString(res.getString(R.string.pref_type_text), null) ?: "ignore"
        set(value) = prefs.edit {
            if (value == "ignore") remove(res.getString(R.string.pref_type_text))
            else putString(res.getString(R.string.pref_type_text), value)
        }

    var haptic: Int
        get() = prefs.getInt(res.getString(R.string.pref_haptic), 1)
        set(value) = prefs.edit {
            if (value == 1) remove(res.getString(R.string.pref_haptic))
            else putInt(res.getString(R.string.pref_haptic), value)
        }

    var moveOverEdge: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_over_edge), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_over_edge))
            else putBoolean(res.getString(R.string.pref_over_edge), true)
        }

    var forbidPaste: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_forbid_paste), false)
        set(value) = prefs.edit {
            if (!value) remove(res.getString(R.string.pref_forbid_paste))
            else putBoolean(res.getString(R.string.pref_forbid_paste), true)
        }

    var useDel: Boolean
        get() = prefs.getBoolean(res.getString(R.string.pref_use_del), true)
        set(value) = prefs.edit {
            if (value) remove(res.getString(R.string.pref_use_del))
            else putBoolean(res.getString(R.string.pref_use_del), false)
        }

    // CandidatesViewContainer
    var keyCenterPort: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_port), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit {
            val v = value.coerceIn(0f, 1f)
            if (v == 0.5f) remove(res.getString(R.string.pref_key_center_port))
            else putFloat(res.getString(R.string.pref_key_center_port), v)
        }

    var keyCenterLand: Float
        get() = prefs.getFloat(res.getString(R.string.pref_key_center_land), 0.5f)
            .coerceIn(0f, 1f)
        set(value) = prefs.edit {
            val v = value.coerceIn(0f, 1f)
            if (v == 0.5f) remove(res.getString(R.string.pref_key_center_land))
            else putFloat(res.getString(R.string.pref_key_center_land), v)
        }

    var keyWidthPort: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_width_port), -1)
        set(value) = prefs.edit {
            if (value == -1) remove(res.getString(R.string.pref_key_width_port))
            else putInt(res.getString(R.string.pref_key_width_port), value)
        }

    var keyWidthLand: Int
        get() = prefs.getInt(res.getString(R.string.pref_key_width_land), -1)
        set(value) = prefs.edit {
            if (value == -1) remove(res.getString(R.string.pref_key_width_land))
            else putInt(res.getString(R.string.pref_key_width_land), value)
        }

    // 辞書管理
    val defaultDictOrder =
        "ユーザー辞書/${res.getString(R.string.dict_name_user)}/"
    var dictOrder: String
        get() = prefs.getString(res.getString(R.string.pref_dict_order), defaultDictOrder)
            ?.replace("絵文字辞書/${res.getString(R.string.dict_name_emoji)}/", "")
        // 168ac9ef5731a7e6c6490c0ef0629aad0f5cec55 の後始末なので、そのうち消す↑
            ?: defaultDictOrder
        set(value) = prefs.edit {
            if (value == defaultDictOrder) remove(res.getString(R.string.pref_dict_order))
            else putString(res.getString(R.string.pref_dict_order), value)
        }

}
