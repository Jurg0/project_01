package com.project01.session

import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.project01.R

class BluetoothDevicesDialogFragment(
    private val devices: List<BluetoothDevice>,
    private val listener: (BluetoothDevice) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.dialog_bluetooth_devices, null)
            val listView = view.findViewById<ListView>(R.id.bluetooth_devices_list)

            val deviceNames = devices.map { device -> device.name ?: device.address }
            val adapter = ArrayAdapter(it, android.R.layout.simple_list_item_1, deviceNames)
            listView.adapter = adapter

            listView.setOnItemClickListener { _, _, position, _ ->
                listener(devices[position])
                dismiss()
            }

            builder.setView(view)
                .setTitle("Select a Bluetooth Device")
                .setNegativeButton("Cancel") { _, _ ->
                    dialog?.cancel()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
