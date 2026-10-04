package com.dsh.codepocket.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * File operations for the app workspace.
 *
 * The workspace lives in the app's external files dir, which needs no permission
 * and is still reachable from a PC:
 *   adb pull /sdcard/Android/data/com.dsh.codepocket/files/workspace
 */
class FileRepository(val root: File) {

    init {
        if (!root.exists()) root.mkdirs()
    }

    fun list(directory: File): List<FileEntry> {
        val children = directory.listFiles() ?: return emptyList()
        return children
            .map {
                FileEntry(
                    file = it,
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0L,
                    modified = it.lastModified(),
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.file.name.lowercase(Locale.US) }))
    }

    fun read(file: File): String = if (file.exists()) file.readText() else ""

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun createDirectory(parent: File, name: String): File {
        val target = File(parent, name)
        target.mkdirs()
        return target
    }

    fun createFile(parent: File, name: String): File {
        val target = File(parent, name)
        target.parentFile?.mkdirs()
        if (!target.exists()) target.createNewFile()
        return target
    }

    fun delete(target: File): Boolean = target.deleteRecursively()

    fun rename(target: File, newName: String): File? {
        val dest = File(target.parentFile, newName)
        if (dest.exists()) return null
        return if (target.renameTo(dest)) dest else null
    }

    /** Path shown in the UI: relative to the workspace root. */
    fun displayPath(directory: File): String {
        val rootPath = root.absolutePath
        val path = directory.absolutePath
        return when {
            path == rootPath -> "/"
            path.startsWith(rootPath) -> path.removePrefix(rootPath)
            else -> path
        }
    }

    fun isRoot(directory: File): Boolean = directory.absolutePath == root.absolutePath

    fun parentOf(directory: File): File? {
        if (isRoot(directory)) return null
        val parent = directory.parentFile ?: return null
        return if (parent.absolutePath.startsWith(root.absolutePath)) parent else root
    }

    companion object {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }

        fun formatTime(millis: Long): String =
            if (millis <= 0) "" else timeFormat.format(Date(millis))

        /** CodeMirror mode for a file name. */
        fun languageFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "py", "pyw" -> "python"
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "java", "kt", "kts", "cs", "go", "rs" -> "clike"
            "js", "mjs", "cjs", "ts", "json" -> "javascript"
            "xml", "html", "htm", "svg", "gradle", "kts" -> "xml"
            "sh", "bash", "zsh" -> "shell"
            "md", "markdown" -> "markdown"
            else -> "clike"
        }
    }
}
