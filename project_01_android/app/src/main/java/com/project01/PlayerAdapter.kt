package com.project01

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.project01.session.Player

class PlayerAdapter(var players: List<Player>, private val clickListener: (Player) -> Unit) :
    RecyclerView.Adapter<PlayerAdapter.PlayerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player, parent, false)
        return PlayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        holder.bind(players[position], clickListener)
    }

    override fun getItemCount(): Int {
        return players.size
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
}

