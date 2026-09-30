package app.sine.core.persist

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

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
internal class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun read(): T {
        if (!file.exists()) return default()
        return try {
            StoreJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            default()
        }
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StoreJson.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace $file" }
        }
    }
}
