package jp.deadend.noname.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog

class SimpleMessageDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
                .setMessage(arguments?.getString("message"))
                .setPositiveButton(android.R.string.ok) { _, _ -> dismiss() }
                .create()
    }

    companion object {
        fun newInstance(message: String): SimpleMessageDialogFragment {
            val frag = SimpleMessageDialogFragment()
            val args = Bundle()
            args.putString("message", message)
            frag.arguments = args
            return frag
        }
    }
}
