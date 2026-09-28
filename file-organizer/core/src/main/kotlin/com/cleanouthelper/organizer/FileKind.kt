package com.cleanouthelper.organizer

import java.io.File

/** What sort of file something is. Decides which folder it goes in and how it gets named. */
enum class FileKind(val label: String) {
    PHOTO("Photo"),
    SCREENSHOT("Screenshot"),
    VIDEO("Video"),
    MUSIC("Music"),
    VOICE_RECORDING("Voice recording"),
    PDF("PDF"),
    WORD("Word document"),
    TEXT("Note"),
    SPREADSHEET("Spreadsheet"),
    PRESENTATION("Presentation"),
    EBOOK("eBook"),
    CONTACT("Contact"),
    CALENDAR("Calendar event"),
    EMAIL("Email"),
    WEB_PAGE("Web page"),
    ARCHIVE("Zip file"),
    APP_INSTALLER("App installer"),
    OTHER("File");

    /** Kinds whose contents are words, and which are sorted by topic. */
    val isDocument: Boolean
        get() = this in setOf(PDF, WORD, TEXT, SPREADSHEET, PRESENTATION, EMAIL, WEB_PAGE)

    val isImage: Boolean get() = this == PHOTO || this == SCREENSHOT

    companion object {
        private val byExtension: Map<String, FileKind> = buildMap {
            fun put(kind: FileKind, vararg ext: String) = ext.forEach { put(it, kind) }
            put(PHOTO, "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "tif", "tiff", "avif")
            put(VIDEO, "mp4", "mov", "mkv", "3gp", "3g2", "webm", "avi", "m4v", "wmv", "mpg", "mpeg", "ts")
            put(MUSIC, "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma", "mid", "midi", "amr", "awb", "m4b")
            put(PDF, "pdf")
            put(WORD, "doc", "docx", "odt", "rtf", "pages", "wps")
            put(TEXT, "txt", "md", "markdown", "text")
            put(SPREADSHEET, "xls", "xlsx", "xlsm", "ods", "csv", "tsv", "numbers")
            put(PRESENTATION, "ppt", "pptx", "odp", "key")
            put(EBOOK, "epub", "mobi", "azw", "azw3", "fb2")
            put(CONTACT, "vcf", "vcard")
            put(CALENDAR, "ics", "vcs")
            put(EMAIL, "eml", "msg")
            put(WEB_PAGE, "html", "htm", "mhtml", "mht")
            put(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
            put(APP_INSTALLER, "apk", "xapk", "apks", "apkm")
        }

        private val voiceHints = Regex(
            "(^|[^a-z])(voice|recording|recorder|record|memo|ptt|call|audio[-_ ]?rec|sound[-_ ]?rec)([^a-z]|$)",
        )
        private val voiceOnlyExtensions = setOf("amr", "awb", "3ga")

        /** Works out the kind from the file name and the folders it is in. */
        fun of(file: File, relativePath: String = file.path): FileKind {
            val ext = file.extension.lowercase()
            val lowerPath = relativePath.lowercase().replace('\\', '/')
            val lowerName = file.name.lowercase()
            val kind = byExtension[ext] ?: return OTHER
            return when (kind) {
                PHOTO -> if ("screenshot" in lowerPath || "screen_shot" in lowerPath || "screen shot" in lowerPath) SCREENSHOT else PHOTO
                VIDEO -> VIDEO
                MUSIC -> when {
                    ext in voiceOnlyExtensions -> VOICE_RECORDING
                    lowerPath.split('/').dropLast(1).any { voiceHints.containsMatchIn(it) } -> VOICE_RECORDING
                    voiceHints.containsMatchIn(lowerName) -> VOICE_RECORDING
                    else -> MUSIC
                }
                else -> kind
            }
        }

        fun isKnownExtension(ext: String) = ext.lowercase() in byExtension
    }
}
