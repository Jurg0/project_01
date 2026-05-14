package com.project01.session

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Stores named playlists as JSON files so the game master can prepare them in advance
 * and reload across app launches.
 *
 * The `_last` slot is reserved for the auto-saved current playlist (filtered out of
 * [listPlaylists]). Persistable URI permission must be taken at pick time
 * (see MainActivity.openDocumentLauncher) for the stored URIs to remain valid after
 * a restart.
 */
class PlaylistStore(private val playlistsDir: File) {

    fun savePlaylist(name: String, videos: List<Video>) {
        if (!isValidName(name)) return
        playlistsDir.mkdirs()
        val file = fileFor(name)
        val tempFile = File(playlistsDir, "${file.name}.tmp")
        tempFile.writeText(
            MessageEnvelope.json.encodeToString(
                ListSerializer(VideoDto.serializer()),
                videos.map { it.toDto() }
            )
        )
        tempFile.renameTo(file)
    }

    fun loadPlaylist(name: String): List<Video>? {
        val file = fileFor(name)
        if (!file.exists()) return null
        return try {
            MessageEnvelope.json.decodeFromString(
                ListSerializer(VideoDto.serializer()),
                file.readText()
            ).map { it.toVideo() }
        } catch (_: Exception) {
            null
        }
    }

    fun listPlaylists(): List<String> {
        if (!playlistsDir.exists()) return emptyList()
        return playlistsDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.filter { !it.startsWith("_") }
            ?.sorted()
            ?: emptyList()
    }

    fun deletePlaylist(name: String) {
        if (!isValidName(name)) return
        fileFor(name).delete()
    }

    private fun fileFor(name: String) = File(playlistsDir, "$name.json")

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\')

    companion object {
        /** Reserved slot for the auto-saved current playlist. */
        const val LAST_USED_NAME = "_last"
    }
}
