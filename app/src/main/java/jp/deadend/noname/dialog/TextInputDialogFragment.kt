package jp.deadend.noname.dialog

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class TextInputDialogFragment : DialogFragment() {
    private var mListener: Listener? = null
    private lateinit var mEditText: EditText
    private var mSingleLine = false
    private var mPlaceHolder = ""

    interface Listener {
        fun onPositiveClick(result: String)
        fun onNegativeClick()
    }

    fun setListener(listener: Listener) {
        this.mListener = listener
    }

    fun setSingleLine(value: Boolean) {
        mSingleLine = value
    }

    fun setPlaceHolder(value: String) {
        mPlaceHolder = value
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        mEditText = EditText(activity)
        if (mSingleLine) {
            mEditText.setSingleLine()
        }
        mEditText.text.append(mPlaceHolder)

        return AlertDialog.Builder(requireContext())
            .setMessage(arguments?.getString("message"))
            .setView(mEditText)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mListener?.onPositiveClick(mEditText.text.toString())
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                mListener?.onNegativeClick()
                dismiss()
            }
            .create()
    }

    companion object {
        fun newInstance(message: String): TextInputDialogFragment {
            val frag = TextInputDialogFragment()
            val args = Bundle()
            args.putString("message", message)
            frag.arguments = args
            return frag
        }
    }
}
