package com.project01.session

import kotlinx.serialization.Serializable
import java.io.File

/**
 * A game the game master prepared in advance: a playlist coupled to the password
 * players will discover out-of-app and type to join. Persisted so the GM can set it
 * up (e.g. a day ahead) and trigger it on-site with a single discreet action.
 */
@Serializable
data class PreparedGame(
    val name: String,
    val password: String,
    val videos: List<VideoDto>,
)

/**
 * Persists [PreparedGame]s as one JSON file per game under [dir] (`filesDir/prepared/`).
 *
 * Deliberately a sibling of [PlaylistStore] rather than an extension of it: the working
 * `_last`/named-playlist pipeline stays untouched, and a brand-new directory has no legacy
 * files, so no schema migration is needed. Decoding is defensive — a corrupt or foreign
 * file is skipped (returns null / omitted from [list]), never fatal — mirroring
 * [PlaylistStore.loadPlaylist].
 *
 * As with [PlaylistStore], persistable URI permission must be taken at video-pick time
 * (see MainActivity.openDocumentLauncher) for the stored URIs to survive an app restart.
 */
class PreparedGameStore(private val dir: File) {

    fun save(game: PreparedGame) {
        if (!isValidName(game.name)) return
        dir.mkdirs()
        val file = fileFor(game.name)
        val tempFile = File(dir, "${file.name}.tmp")
        tempFile.writeText(MessageEnvelope.json.encodeToString(PreparedGame.serializer(), game))
        tempFile.renameTo(file)
    }

    fun load(name: String): PreparedGame? {
        val file = fileFor(name)
        if (!file.exists()) return null
        return try {
            MessageEnvelope.json.decodeFromString(PreparedGame.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** All prepared games, corrupt/foreign files skipped, ordered by name. */
    fun list(): List<PreparedGame> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching {
                MessageEnvelope.json.decodeFromString(PreparedGame.serializer(), it.readText())
            }.getOrNull() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun listNames(): List<String> = list().map { it.name }

    fun delete(name: String) {
        if (!isValidName(name)) return
        fileFor(name).delete()
    }

    /**
     * The prepared game whose password matches [password] exactly, or null. Used at CREATE
     * time to resolve the entered password to its playlist. Deterministic when two prepared
     * games share a password: returns the alphabetically-first (see [list] ordering).
     */
    fun findByPassword(password: String): PreparedGame? =
        list().firstOrNull { it.password == password }

    private fun fileFor(name: String) = File(dir, "$name.json")

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\')
}
