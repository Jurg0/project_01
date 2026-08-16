package com.project01.session

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.project01.R

/**
 * A password-only prompt used for BOTH joining and the undercover CREATE action, so an
 * onlooker cannot tell them apart. The positive-button label is deliberately identical
 * ("Join") in both cases. The player name is auto-generated (see GameViewModel.join), so
 * this dialog only collects a password.
 */
class PasswordPromptDialogFragment(
    private val onSubmit: (String) -> Unit,
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            // No explicit theme: this resolves ?attr/alertDialogTheme from the app theme, the
            // same path every other dialog takes, so button colours stay consistent.
            val builder = AlertDialog.Builder(it)
            val view = requireActivity().layoutInflater.inflate(R.layout.dialog_create_game, null)
            val passwordEditText = view.findViewById<EditText>(R.id.password)

            builder.setView(view)
                .setPositiveButton("Join") { _, _ ->
                    onSubmit(passwordEditText.text.toString())
                }
                .setNegativeButton("Cancel") { _, _ ->
                    dialog?.cancel()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
