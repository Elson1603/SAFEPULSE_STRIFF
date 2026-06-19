package com.safepulse.ui.map

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk-based tile cache for offline map support.
 * Tiles from OpenStreetMap are cached to app's cache directory.
 * Uses z/x/y path structure for efficient lookup.
 *
 * Cache eviction: LRU based on file last-modified time, max 200MB.
 */
class TileCacheManager(context: Context) {

    private val cacheDir: File = File(context.cacheDir, "map_tiles").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val TAG = "TileCacheManager"
        private const val MAX_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB
        private const val TILE_EXTENSION = ".png"
    }

    /**
     * Get cached tile file for a given URL. Returns null if not cached.
     */
    fun getCachedTile(url: String): File? {
        val file = tileFile(url)
        return if (file.exists()) {
            // Touch file to mark as recently used
            file.setLastModified(System.currentTimeMillis())
            file
        } else null
    }

    /**
     * Read cached tile bytes. Returns null if not cached.
     */
    fun getCachedTileBytes(url: String): ByteArray? {
        val file = getCachedTile(url) ?: return null
        return try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached tile: ${e.message}")
            null
        }
    }

    /**
     * Save tile bytes to cache.
     */
    fun cacheTile(url: String, data: ByteArray) {
        try {
            val file = tileFile(url)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(data) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache tile: ${e.message}")
        }
    }

    /**
     * Check if a tile is cached.
     */
    fun isCached(url: String): Boolean = tileFile(url).exists()

    /**
     * Get cache size in bytes.
     */
    fun getCacheSizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Get cache size as human-readable string.
     */
    fun getCacheSizeFormatted(): String {
        val bytes = getCacheSizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    /**
     * Evict old tiles when cache exceeds max size. Removes oldest-accessed tiles first.
     */
    fun evictIfNeeded() {
        val currentSize = getCacheSizeBytes()
        if (currentSize <= MAX_CACHE_BYTES) return

        Log.d(TAG, "Cache size ${getCacheSizeFormatted()} exceeds limit, evicting...")

        val files = cacheDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var size = currentSize
        val target = MAX_CACHE_BYTES * 3 / 4 // Evict down to 75%

        for (file in files) {
            if (size <= target) break
            val fileSize = file.length()
            if (file.delete()) {
                size -= fileSize
            }
        }

        // Clean empty directories
        cacheDir.walkBottomUp()
            .filter { it.isDirectory && it != cacheDir && (it.listFiles()?.isEmpty() == true) }
            .forEach { it.delete() }

        Log.d(TAG, "Cache evicted to ${getCacheSizeFormatted()}")
    }

    /**
     * Clear entire tile cache.
     */
    fun clearCache() {
        cacheDir.walkBottomUp().forEach { it.delete() }
        cacheDir.mkdirs()
        Log.d(TAG, "Tile cache cleared")
    }

    /**
     * Map a tile URL to a local cache file path.
     * OSM URLs: https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png
     * We hash the URL to a safe filename within a z-level directory.
     */
    private fun tileFile(url: String): File {
        // Try to extract z/x/y from OSM-style URL
        val regex = Regex("""/(\d+)/(\d+)/(\d+)\.png""")
        val match = regex.find(url)

        return if (match != null) {
            val (z, x, y) = match.destructured
            File(cacheDir, "$z/$x/$y$TILE_EXTENSION")
        } else {
            // Fallback: hash the full URL
            val hash = md5(url)
            File(cacheDir, "other/$hash$TILE_EXTENSION")
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
