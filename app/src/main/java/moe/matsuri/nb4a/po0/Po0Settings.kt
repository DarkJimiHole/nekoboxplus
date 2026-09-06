package moe.matsuri.nb4a.po0

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException

object Po0Settings {
    const val ENABLED = "po0WhitelistEnabled"
    const val CONFIGURATIONS = "po0WhitelistConfigurations"
    const val STATUS = "po0WhitelistStatus"
    const val REFRESH = "po0WhitelistRefresh"

    private const val FORMAT_VERSION = 1
    private const val MAX_FILE_CHARS = 131072
    private val gson = Gson()

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "po0-api-config.json"))

    private data class StoredConfiguration(
        val version: Int = FORMAT_VERSION,
        val items: List<Po0Protocol.Endpoint>? = emptyList(),
    )

    /** Read the current versioned configuration. Older text formats are intentionally unsupported. */
    fun readEndpoints(context: Context): List<Po0Protocol.Endpoint> {
        val raw = try {
            file(context).openRead().bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        require(raw.length <= MAX_FILE_CHARS) { "Invalid API configuration" }
        if (raw.isBlank()) return emptyList()

        val stored = try { gson.fromJson(raw, StoredConfiguration::class.java) }
        catch (_: Exception) { null }
        require(stored != null && stored.version == FORMAT_VERSION) { "Invalid API configuration" }
        return normalize(stored.items ?: throw IllegalArgumentException("Invalid API configuration"))
    }

    fun writeEndpoints(context: Context, endpoints: List<Po0Protocol.Endpoint>) {
        val normalized = normalize(endpoints)
        val bytes = gson.toJson(StoredConfiguration(items = normalized)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_CHARS) { "API configuration is too large" }
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    private fun normalize(endpoints: List<Po0Protocol.Endpoint>): List<Po0Protocol.Endpoint> {
        require(endpoints.size <= Po0Protocol.MAX_ENDPOINTS) { "Too many API addresses" }
        val normalized = endpoints.map { Po0Protocol.endpoint(it.url, it.slot) }
        require(normalized.map { it.url }.distinct().size == normalized.size) { "Duplicate API address" }
        return normalized
    }
}
