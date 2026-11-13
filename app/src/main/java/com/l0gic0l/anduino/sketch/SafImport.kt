package com.l0gic0l.anduino.sketch

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Copies files the user picks with Android's file picker (Storage Access Framework) into
 * the Linux environment. Picked files can't be compiled in place: arduino-cli runs inside
 * the rootfs and can only see real files there.
 */
object SafImport {
    private const val MAX_BYTES = 50L * 1024 * 1024

    /** The file's name as the picker shows it (e.g. "secrets.h"). */
    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')

    /**
     * Copies the folder picked with OpenDocumentTree into [dest], which is named after
     * the folder. Hidden entries and IDE build output are skipped. Returns [dest].
     */
    fun copyTree(context: Context, treeUri: Uri, parent: File): File {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Can't read that folder")
        val name = root.name?.takeIf { it.isNotBlank() } ?: "imported"
        val dest = File(parent, name)
        dest.deleteRecursively()
        var copied = 0L
        fun copy(dir: DocumentFile, to: File) {
            to.mkdirs()
            for (child in dir.listFiles()) {
                val childName = child.name ?: continue
                if (childName.startsWith(".")) continue
                if (child.isDirectory) {
                    if (childName == "build") continue // Arduino IDE "Export compiled binary" output
                    copy(child, File(to, childName))
                } else if (child.isFile) {
                    copied += child.length()
                    if (copied > MAX_BYTES) throw IOException("That folder is larger than 50 MB; pick the sketch folder itself")
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(to, childName).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        copy(root, dest)
        return dest
    }
}
