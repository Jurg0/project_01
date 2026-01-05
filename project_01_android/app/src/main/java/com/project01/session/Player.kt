package com.project01.session

import android.net.wifi.p2p.WifiP2pDevice
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Player(
    val device: WifiP2pDevice,
    val name: String,
    val isGameMaster: Boolean
) : Parcelable
