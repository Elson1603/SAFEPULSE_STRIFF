package com.safepulse.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.lang.reflect.Type
import java.security.MessageDigest

/**
 * Bounded JSON cache for map-related data such as nearby safety results and routes.
 * Stored in the app cache directory so Android can reclaim it if storage is tight.
 */
class MapDataCacheManager(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val cacheDir: File = File(context.cacheDir, CACHE_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    fun <T> get(
        namespace: String,
        key: String,
        type: Type,
        ttlMillis: Long? = null
    ): T? {
        val file = cacheFile(namespace, key)
        if (!file.exists()) return null

        if (ttlMillis != null && System.currentTimeMillis() - file.lastModified() > ttlMillis) {
            file.delete()
            return null
        }

        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.bufferedReader().use { gson.fromJson<T>(it, type) }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read map data cache entry: ${error.message}")
            file.delete()
            null
        }
    }

    fun put(namespace: String, key: String, value: Any) {
        runCatching {
            val file = cacheFile(namespace, key)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.bufferedWriter().use { writer -> gson.toJson(value, writer) }
            if (file.exists()) file.delete()
            temp.renameTo(file)
            file.setLastModified(System.currentTimeMillis())
            evictIfNeeded()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write map data cache entry: ${error.message}")
        }
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getCacheSizeFormatted(): String {
        val bytes = getCacheSizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    fun clearCache() {
        cacheDir.walkBottomUp().forEach { it.delete() }
        cacheDir.mkdirs()
    }

    private fun evictIfNeeded() {
        val currentSize = getCacheSizeBytes()
        if (currentSize <= MAX_CACHE_BYTES) return

        val files = cacheDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var size = currentSize
        val target = MAX_CACHE_BYTES * 4 / 5
        for (file in files) {
            if (size <= target) break
            val fileSize = file.length()
            if (file.delete()) {
                size -= fileSize
            }
        }

        cacheDir.walkBottomUp()
            .filter { it.isDirectory && it != cacheDir && (it.listFiles()?.isEmpty() == true) }
            .forEach { it.delete() }
    }

    private fun cacheFile(namespace: String, key: String): File {
        val safeNamespace = namespace.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(cacheDir, safeNamespace), "${sha256(key)}.json")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "MapDataCacheManager"
        private const val CACHE_DIR_NAME = "map_data"
        private const val MAX_CACHE_BYTES = 100L * 1024L * 1024L
    }
}
