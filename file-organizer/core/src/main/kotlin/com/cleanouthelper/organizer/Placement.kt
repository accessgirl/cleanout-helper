package com.cleanouthelper.organizer

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Decides the folder each file belongs in and, for unclear names, a clear new name. */
object Placement {
    const val DUPLICATES_FOLDER = "Duplicates - Review"

    /** Folder names for documents whose topic couldn't be worked out. */
    private fun documentTypeFolder(kind: FileKind) = when (kind) {
        FileKind.PDF -> "PDFs"
        FileKind.WORD -> "Word Documents"
        FileKind.TEXT -> "Notes & Text Files"
        FileKind.SPREADSHEET -> "Spreadsheets"
        FileKind.PRESENTATION -> "Presentations"
        FileKind.EMAIL -> "Saved Emails"
        FileKind.WEB_PAGE -> "Saved Web Pages"
        else -> "Other Documents"
    }

    fun bestDate(f: FileFacts): LocalDateTime =
        f.date ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(f.modified.takeIf { it > 0 } ?: System.currentTimeMillis()), ZoneId.systemDefault())

    /** Folder inside the organized folder, using "/" between levels, e.g. "Photos/2024/05 - May". */
    fun folderFor(f: FileFacts): String {
        val d = bestDate(f)
        val year = d.year.toString()
        // A photo of a receipt, letter or form is filed with the documents.
        if (f.kind == FileKind.PHOTO && f.topic != null) return "Documents/${f.topic}"
        return when (f.kind) {
            FileKind.PHOTO -> "Photos/$year/${Dates.monthFolder(d)}"
            FileKind.SCREENSHOT -> "Screenshots/$year"
            FileKind.VIDEO -> "Videos/$year"
            FileKind.VOICE_RECORDING -> "Voice Recordings/$year"
            FileKind.MUSIC -> f.author?.let { "Music/" + Names.sanitize(it, 60) }?.takeIf { it != "Music/" } ?: "Music"
            FileKind.EBOOK -> "eBooks"
            FileKind.CONTACT -> "Contacts & Calendar"
            FileKind.CALENDAR -> "Contacts & Calendar"
            FileKind.ARCHIVE -> "Zip Files"
            FileKind.APP_INSTALLER -> "App Installers"
            FileKind.OTHER -> {
                val ext = f.file.extension.uppercase()
                if (ext.isEmpty()) "Other Files/No file type" else "Other Files/$ext files"
            }
            else -> "Documents/" + (f.topic ?: documentTypeFolder(f.kind))
        }
    }

    /**
     * A clear name (without extension) for a file whose current name doesn't say what it is,
     * or null to keep the current name.
     */
    fun newBaseName(f: FileFacts, options: OrganizeOptions): String? {
        if (!options.renameUnclearFiles) return null
        if (Names.isMeaningful(f.file.nameWithoutExtension)) return null
        val built = buildName(f) ?: return null
        val clean = Names.sanitize(built)
        if (clean.isEmpty() || clean.equals(f.file.nameWithoutExtension, ignoreCase = true)) return null
        return clean
    }

    private fun buildName(f: FileFacts): String? {
        val d = bestDate(f)
        val day = Names.day(d)
        val stamp = if (f.dateSource == DateSource.MODIFIED && f.kind.isDocument) day else Names.dayAndTime(d)
        val title = f.title?.let { Names.sanitize(it, 60) }?.takeIf { it.isNotEmpty() }
        val place = f.place?.let { " - $it" }.orEmpty()
        val things = f.tags.take(2).joinToString(" & ")
        return when (f.kind) {
            FileKind.PHOTO -> if (f.topic != null) documentName(f, title, day) else {
                val what = title ?: things.ifEmpty { "Photo" }
                "$stamp $what$place"
            }
            FileKind.SCREENSHOT -> "$stamp Screenshot" + (title?.let { " - $it" } ?: "")
            FileKind.VIDEO -> {
                val what = if (things.isNotEmpty()) "Video of $things" else "Video"
                "$stamp $what$place" + (f.durationSeconds?.let { " (${formatDuration(it)})" } ?: "")
            }
            FileKind.VOICE_RECORDING -> "$stamp " + (title ?: "Voice recording") + (f.durationSeconds?.let { " (${formatDuration(it)})" } ?: "")
            FileKind.MUSIC -> when {
                title != null && f.author != null -> "${f.author} - $title"
                title != null -> title
                else -> null
            }
            FileKind.EBOOK -> title?.let { t -> f.author?.let { "$t - $it" } ?: t }
            FileKind.CONTACT -> title?.let { if (it.endsWith("contacts")) "$it ($day)" else "Contact - $it" }
            FileKind.CALENDAR -> title?.let { "Event - $it ($day)" }
            FileKind.APP_INSTALLER -> title?.let { "App installer - $it" }
            FileKind.ARCHIVE -> "Zip - " + (title ?: "files") + " ($day)"
            FileKind.OTHER -> null
            else -> documentName(f, title, day)
        }
    }

    /** "Receipt - Walmart Supercenter (2024-05-12)", "Bank statement (2024-05-12)", "Road trip packing list (2024-05-12)". */
    private fun documentName(f: FileFacts, title: String?, day: String): String {
        val label = f.docLabel
        val lead = when {
            f.kind == FileKind.EMAIL -> "Email" + (title?.let { " - $it" } ?: "")
            f.kind == FileKind.WEB_PAGE -> "Web page" + (title?.let { " - $it" } ?: "")
            label != null && title != null && !title.contains(label, ignoreCase = true) -> "$label - $title"
            title != null -> title
            label != null -> label
            f.kind == FileKind.PHOTO -> "Scanned document"
            else -> f.kind.label
        }
        return "$lead ($day)"
    }
}
