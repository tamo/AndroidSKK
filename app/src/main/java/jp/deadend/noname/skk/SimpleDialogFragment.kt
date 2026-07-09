package jp.deadend.noname.skk

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class SimpleDialogFragment : DialogFragment() {
    private var mListener: Listener? = null
    private var mEditText: EditText? = null

    interface Listener {
        fun onPositiveClick() {}
        fun onPositiveClick(result: String) {}
        fun onNegativeClick() {}
    }

    fun setListener(listener: Listener) {
        this.mListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = arguments?.getString(ARG_MESSAGE)
        val hasNegativeButton = arguments?.getBoolean(ARG_HAS_NEGATIVE_BUTTON, false) == true
        val hasTextInput = arguments?.getBoolean(ARG_HAS_TEXT_INPUT, false) == true
        val isSingleLine = arguments?.getBoolean(ARG_IS_SINGLE_LINE, false) == true
        val placeHolder = arguments?.getString(ARG_PLACE_HOLDER) ?: ""

        val builder = AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (hasTextInput) {
                    mListener?.onPositiveClick(mEditText?.text?.toString() ?: "")
                } else {
                    mListener?.onPositiveClick()
                }
                dismiss()
            }

        if (hasNegativeButton || hasTextInput) {
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                mListener?.onNegativeClick()
                dismiss()
            }
        }

        if (hasTextInput) {
            mEditText = EditText(activity).apply {
                if (isSingleLine) setSingleLine()
                text.append(placeHolder)
            }
            builder.setView(mEditText)
        }

        return builder.create()
    }

    companion object {
        private const val ARG_MESSAGE = "message"
        private const val ARG_HAS_NEGATIVE_BUTTON = "has_negative_button"
        private const val ARG_HAS_TEXT_INPUT = "has_text_input"
        private const val ARG_IS_SINGLE_LINE = "is_single_line"
        private const val ARG_PLACE_HOLDER = "place_holder"

        fun newInstance(
            message: String,
            hasNegativeButton: Boolean = false,
            hasTextInput: Boolean = false,
            isSingleLine: Boolean = false,
            placeHolder: String = ""
        ): SimpleDialogFragment {
            return SimpleDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_HAS_NEGATIVE_BUTTON, hasNegativeButton)
                    putBoolean(ARG_HAS_TEXT_INPUT, hasTextInput)
                    putBoolean(ARG_IS_SINGLE_LINE, isSingleLine)
                    putString(ARG_PLACE_HOLDER, placeHolder)
                }
            }
        }
    }
}
