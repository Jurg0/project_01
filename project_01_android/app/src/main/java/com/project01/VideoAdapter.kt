package com.project01

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.project01.session.Video

class VideoAdapter(
    var isGameMaster: Boolean,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onVideoSelected: (Video) -> Unit
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    private val progressMap = mutableMapOf<String, Int>()

    override fun onCurrentListChanged(previousList: MutableList<Video>, currentList: MutableList<Video>) {
        val currentTitles = currentList.map { it.title }.toSet()
        progressMap.keys.retainAll(currentTitles)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position)
        holder.bind(video, position, isGameMaster, onMoveUp, onMoveDown, onRemove, onVideoSelected)
        val progress = progressMap[video.title] ?: 0
        holder.updateProgress(progress)
    }

    fun updateProgress(videoTitle: String, progress: Int) {
        progressMap[videoTitle] = progress
        val index = currentList.indexOfFirst { it.title == videoTitle }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val videoTitle: TextView = itemView.findViewById(R.id.video_title)
        private val moveUpButton: Button = itemView.findViewById(R.id.move_up_button)
        private val moveDownButton: Button = itemView.findViewById(R.id.move_down_button)
        private val removeButton: Button = itemView.findViewById(R.id.remove_button)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)

        fun bind(
            video: Video,
            position: Int,
            isGameMaster: Boolean,
            onMoveUp: (Int) -> Unit,
            onMoveDown: (Int) -> Unit,
            onRemove: (Int) -> Unit,
            onVideoSelected: (Video) -> Unit
        ) {
            videoTitle.text = video.title
            itemView.setOnClickListener { onVideoSelected(video) }

            if (isGameMaster) {
                moveUpButton.visibility = View.VISIBLE
                moveDownButton.visibility = View.VISIBLE
                removeButton.visibility = View.VISIBLE

                moveUpButton.setOnClickListener { onMoveUp(position) }
                moveDownButton.setOnClickListener { onMoveDown(position) }
                removeButton.setOnClickListener { onRemove(position) }
            } else {
                moveUpButton.visibility = View.GONE
                moveDownButton.visibility = View.GONE
                removeButton.visibility = View.GONE
            }
        }

        fun updateProgress(progress: Int) {
            if (progress > 0 && progress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progress
            } else {
                progressBar.visibility = View.GONE
            }
        }
    }

    private class VideoDiffCallback : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem == newItem
        }
    }
}
