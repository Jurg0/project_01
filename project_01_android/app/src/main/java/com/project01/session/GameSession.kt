package com.project01.session

import android.net.wifi.p2p.WifiP2pDevice
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GameSession(
    val name: String,
    val password: String,
    val gameMaster: WifiP2pDevice,
    val players: List<WifiP2pDevice>
) : Parcelable
