package com.project01.session

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.project01.R

class JoinGameDialogFragment(private val joinGameListener: (String) -> Unit) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.dialog_join_game, null)
            val passwordEditText = view.findViewById<EditText>(R.id.password)

            builder.setView(view)
                .setPositiveButton("Join") { _, _ ->
                    joinGameListener(passwordEditText.text.toString())
                }
                .setNegativeButton("Cancel") { _, _ ->
                    dialog?.cancel()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
