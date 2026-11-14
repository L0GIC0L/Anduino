package com.l0gic0l.anduino.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.coroutines.coroutineContext

object Downloader {
    /**
     * Downloads [url] to [dest] (via a .part file). [onProgress] gets
     * (bytesSoFar, totalOrMinus1).
     */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var conn = open(url)
        // HttpURLConnection does not follow redirects across hosts; do it by hand.
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = URL(URL(conn.url, "."), conn.getHeaderField("Location")).toString()
            conn.disconnect()
            conn = open(next)
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        onProgress(done, total)
                        lastReport = done
                    }
                }
                onProgress(done, total)
            }
        }
        conn.disconnect()
        if (!part.renameTo(dest)) throw IOException("Could not move download into place")
    }

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Anduino")
        }
}

/**
 * Extracts .tar.gz archives preserving symlinks and permissions. Hard links are
 * materialised as copies because Android's app sandbox forbids link(2).
 */
object TarGz {
    suspend fun extract(
        archive: File,
        destination: File,
        onEntry: (String) -> Unit = {},
        /** Maps an entry name to a relative output path, or null to skip it. */
        mapName: (String) -> String? = { it },
    ) = withContext(Dispatchers.IO) {
        destination.mkdirs()
        val root = destination.canonicalFile
        archive.inputStream().use { raw ->
            extractStream(raw, root, onEntry, mapName)
        }
    }

    private suspend fun extractStream(raw: InputStream, root: File, onEntry: (String) -> Unit, mapName: (String) -> String?) {
        val tar = TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(raw, 256 * 1024)))
        var count = 0
        while (true) {
            coroutineContext.ensureActive()
            val entry = tar.nextEntry ?: break
            val name = normalize(entry.name) ?: continue
            val mapped = mapName(name) ?: continue
            val out = File(root, mapped).normalize()
            // Lexical check: rejects "../" tricks without resolving symlinks that point
            // at absolute paths meant for the Linux side (those resolve on Android otherwise).
            if (!out.path.startsWith(root.path + File.separator)) {
                throw IOException("Archive entry escapes destination: ${entry.name}")
            }
            if (++count % 200 == 0) onEntry(name)
            when {
                entry.isDirectory -> {
                    out.mkdirs()
                    chmod(out, entry.mode or 0b111_000_000)
                }
                entry.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    val path = out.toPath()
                    Files.deleteIfExists(path)
                    Files.createSymbolicLink(path, Paths.get(entry.linkName))
                }
                entry.isLink -> { // hard link -> copy the already extracted target
                    val targetName = normalize(entry.linkName)?.let(mapName) ?: continue
                    val target = File(root, targetName)
                    out.parentFile?.mkdirs()
                    Files.deleteIfExists(out.toPath())
                    if (target.exists()) {
                        target.copyTo(out, overwrite = true)
                        chmod(out, entry.mode or 0b110_000_000)
                    }
                }
                entry.isFile -> {
                    out.parentFile?.mkdirs()
                    Files.deleteIfExists(out.toPath()) // may be a symlink from an earlier entry
                    out.outputStream().use { tar.copyTo(it) }
                    chmod(out, entry.mode or 0b110_000_000)
                }
                else -> Unit // device nodes, FIFOs: not possible (or needed) in an app sandbox
            }
        }
    }

    private fun normalize(name: String): String? {
        val n = name.removePrefix("./").trimEnd('/')
        return if (n.isEmpty() || n == ".") null else n
    }

    fun chmod(file: File, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val bits = listOf(
            0b100_000_000 to PosixFilePermission.OWNER_READ,
            0b010_000_000 to PosixFilePermission.OWNER_WRITE,
            0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
            0b000_100_000 to PosixFilePermission.GROUP_READ,
            0b000_010_000 to PosixFilePermission.GROUP_WRITE,
            0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
            0b000_000_100 to PosixFilePermission.OTHERS_READ,
            0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
            0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
        )
        for ((bit, p) in bits) if (mode and bit != 0) perms += p
        try {
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: Exception) {
            // Best effort; a few file systems refuse some bits.
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file.setExecutable(mode and 0b001_000_000 != 0)
        }
    }
}
