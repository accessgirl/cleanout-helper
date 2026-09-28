package com.cleanouthelper.organizer

import java.io.File
import java.time.LocalDateTime

/** Where a file's date came from, best first. */
enum class DateSource { CAPTURED, CONTENT, FILE_NAME, MODIFIED }

/**
 * Everything learned about one file. Analyzers fill this in; the planner uses it to choose
 * the folder and the new name, and the index files use it to describe the file.
 */
class FileFacts(val file: File, val relativePath: String, var kind: FileKind) {
    /** A short human title, e.g. "Walmart Supercenter" or "Beach holiday packing list". */
    var title: String? = null
    /** What sort of document it is, e.g. "Receipt" or "Bank statement". */
    var docLabel: String? = null
    /** Topic folder for documents, e.g. "Bills & Receipts". */
    var topic: String? = null
    var date: LocalDateTime? = null
    var dateSource: DateSource = DateSource.MODIFIED
    /** Town or area the photo/video was taken in. */
    var place: String? = null
    /** Things seen in a photo or video, e.g. "Beach", "Dog". */
    var tags: List<String> = emptyList()
    /** Words read from the file (document text or text found in a picture). Kept short. */
    var text: String? = null
    var author: String? = null
    var album: String? = null
    var durationSeconds: Long? = null
    var pages: Int? = null
    var width: Int? = null
    var height: Int? = null
    /** Extra details for the index, e.g. "12 files inside" or "version 2.3". */
    val details = mutableListOf<String>()

    val size: Long = file.length()
    val modified: Long = file.lastModified()

    /** Sets the date if the new source is better than the one we have. */
    fun offerDate(value: LocalDateTime?, source: DateSource) {
        if (value == null) return
        if (date == null || source.ordinal < dateSource.ordinal) {
            date = value
            dateSource = source
        }
    }

    /** One-line summary shown in the index files and the search screen. */
    fun describe(): String = buildList {
        docLabel?.let { add(it) } ?: add(kind.label)
        pages?.let { add(if (it == 1) "1 page" else "$it pages") }
        durationSeconds?.let { add(formatDuration(it)) }
        if (width != null && height != null) add("${width}×$height")
        author?.let { add("by $it") }
        album?.let { add("album: $it") }
        if (tags.isNotEmpty()) add("shows: " + tags.joinToString(", "))
        place?.let { add("taken in $it") }
        addAll(details)
    }.joinToString(" · ")

    /** First words of the text, for the index ("What's inside"). */
    fun preview(maxChars: Int = 160): String? {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length <= maxChars) t else t.take(maxChars).substringBeforeLast(' ') + "…"
    }
}

/** Something that can learn facts about a file (read its text, its photo details, …). */
fun interface FileAnalyzer {
    fun analyze(facts: FileFacts, options: OrganizeOptions)
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds sec"
    seconds < 3600 -> "${(seconds + 30) / 60} min"
    else -> "${seconds / 3600} hr ${(seconds % 3600) / 60} min"
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    else -> String.format("%.2f GB", bytes / 1073741824.0)
}
