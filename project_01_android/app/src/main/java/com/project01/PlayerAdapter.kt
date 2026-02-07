package com.project01

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.project01.session.Player

class PlayerAdapter(private val clickListener: (Player) -> Unit) :
    ListAdapter<Player, PlayerAdapter.PlayerViewHolder>(PlayerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(getItem(position), clickListener)
    }

    class PlayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playerName: TextView = itemView.findViewById(R.id.player_name)
        private val gameMasterLabel: TextView = itemView.findViewById(R.id.game_master_label)

        fun bind(player: Player, clickListener: (Player) -> Unit) {
            playerName.text = player.name
            if (player.isGameMaster) {
                gameMasterLabel.visibility = View.VISIBLE
            } else {
                gameMasterLabel.visibility = View.GONE
            }
            itemView.setOnClickListener { clickListener(player) }
        }
    }

    private class PlayerDiffCallback : DiffUtil.ItemCallback<Player>() {
        override fun areItemsTheSame(oldItem: Player, newItem: Player): Boolean {
            return oldItem.device.deviceAddress == newItem.device.deviceAddress
        }

        override fun areContentsTheSame(oldItem: Player, newItem: Player): Boolean {
            return oldItem == newItem
        }
    }
}
