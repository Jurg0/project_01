package com.project01

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.project01.session.Video

class VideoAdapter(
    /** When true, each row shows move-up / move-down / delete buttons. Lobby-only. */
    var editable: Boolean,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onVideoSelected: (Video) -> Unit
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    private val progressMap = mutableMapOf<String, Int>()
    private val failedTransfers = mutableSetOf<String>()

    /** -1 = no current item (lobby / non-session). */
    private var currentIndex: Int = -1
    private var currentIsPlaying: Boolean = false

    override fun onCurrentListChanged(previousList: MutableList<Video>, currentList: MutableList<Video>) {
        val currentTitles = currentList.map { it.title }.toSet()
        progressMap.keys.retainAll(currentTitles)
        failedTransfers.retainAll(currentTitles)
    }

    /**
     * Sets which row shows the now-playing indicator. Pass `index = -1` to
     * clear (lobby, non-session, non-GM). Only the previous and new rows are
     * refreshed.
     */
    fun setCurrent(index: Int, isPlaying: Boolean) {
        if (index == currentIndex && isPlaying == currentIsPlaying) return
        val previous = currentIndex
        currentIndex = index
        currentIsPlaying = isPlaying
        if (previous != index) {
            if (previous in 0 until itemCount) notifyItemChanged(previous)
            if (index in 0 until itemCount) notifyItemChanged(index)
        } else if (index in 0 until itemCount) {
            // Same row, only the play/pause variant changed.
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position)
        holder.bind(video, position, editable, onMoveUp, onMoveDown, onRemove, onVideoSelected)
        val progress = progressMap[video.title] ?: 0
        holder.updateProgress(progress)
        holder.updateFailedState(video.title in failedTransfers)
        holder.updateNowPlaying(position == currentIndex, currentIsPlaying)
    }

    fun updateProgress(videoTitle: String, progress: Int) {
        progressMap[videoTitle] = progress
        failedTransfers.remove(videoTitle)
        val index = currentList.indexOfFirst { it.title == videoTitle }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun markFailed(videoTitle: String) {
        failedTransfers.add(videoTitle)
        val index = currentList.indexOfFirst { it.title == videoTitle }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val videoTitle: TextView = itemView.findViewById(R.id.video_title)
        private val moveUpButton: ImageButton = itemView.findViewById(R.id.move_up_button)
        private val moveDownButton: ImageButton = itemView.findViewById(R.id.move_down_button)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val errorIcon: ImageView = itemView.findViewById(R.id.error_icon)
        private val nowPlayingIcon: ImageView = itemView.findViewById(R.id.now_playing_icon)

        fun bind(
            video: Video,
            position: Int,
            editable: Boolean,
            onMoveUp: (Int) -> Unit,
            onMoveDown: (Int) -> Unit,
            onRemove: (Int) -> Unit,
            onVideoSelected: (Video) -> Unit
        ) {
            videoTitle.text = video.title
            itemView.setOnClickListener { onVideoSelected(video) }

            if (editable) {
                moveUpButton.visibility = View.VISIBLE
                moveDownButton.visibility = View.VISIBLE
                removeButton.visibility = View.VISIBLE

                moveUpButton.setOnClickListener { onMoveUp(bindingAdapterPosition) }
                moveDownButton.setOnClickListener { onMoveDown(bindingAdapterPosition) }
                removeButton.setOnClickListener { onRemove(bindingAdapterPosition) }
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

        fun updateFailedState(failed: Boolean) {
            errorIcon.visibility = if (failed) View.VISIBLE else View.GONE
        }

        fun updateNowPlaying(isCurrent: Boolean, isPlaying: Boolean) {
            if (!isCurrent) {
                nowPlayingIcon.visibility = View.GONE
                return
            }
            nowPlayingIcon.setImageResource(
                if (isPlaying) R.drawable.ic_now_playing else R.drawable.ic_now_paused
            )
            nowPlayingIcon.contentDescription =
                if (isPlaying) "Currently playing" else "Queued on blue safe-screen"
            nowPlayingIcon.visibility = View.VISIBLE
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
