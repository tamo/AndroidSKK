package jp.deadend.noname.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog

class ConfirmationDialogFragment : DialogFragment() {
    private var mListener: Listener? = null

    interface Listener {
        fun onPositiveClick()
        fun onNegativeClick()
    }

    fun setListener(listener: Listener) {
        this.mListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
                .setMessage(arguments?.getString("message"))
                .setCancelable(true)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mListener?.onPositiveClick()
                    dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    mListener?.onNegativeClick()
                    dismiss()
                }
                .create()
    }

    companion object {
        fun newInstance(message: String): ConfirmationDialogFragment {
            val frag = ConfirmationDialogFragment()
            val args = Bundle()
            args.putString("message", message)
            frag.arguments = args
            return frag
        }
    }
}
