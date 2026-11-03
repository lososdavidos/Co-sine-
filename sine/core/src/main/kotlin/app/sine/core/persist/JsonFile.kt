package app.sine.core.persist

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * A value persisted as one JSON file, written atomically (temp file + rename)
 * so a crash mid-write never leaves half a file behind. A corrupt or missing
 * file reads as [default].
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val tmp get() = File(file.parentFile, file.name + ".tmp")

    fun read(): T {
        // No file but a whole temp one: a write that stopped just before its move.
        val from = if (!file.exists() && tmp.exists()) tmp else file
        if (!from.exists()) return default()
        return try {
            StoreJson.decodeFromString(serializer, from.readText())
        } catch (e: Exception) {
            default()
        }
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = tmp
        tmp.writeText(StoreJson.encodeToString(serializer, value))
        // One step, even on Windows, where renameTo won't replace a file: never a moment with neither.
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
