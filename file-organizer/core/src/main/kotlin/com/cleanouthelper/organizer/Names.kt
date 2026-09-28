package com.cleanouthelper.organizer

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Decides whether a file name tells you what the file is, and builds clear names. */
object Names {
    /** Names that come from cameras, apps and downloads rather than from a person. */
    private val machinePatterns = listOf(
        Regex("^(img|vid|pxl|dsc|dscn|dscf|dcim|mvimg|pano|burst|photo|image|video|mov|gopr|gp|dji|sam|wp|p|pic|picture|snapchat|received|fb_img|inshot|capture|cam|camera)[-_ ]?[0-9].*", RegexOption.IGNORE_CASE),
        Regex("^(screenshot|screen[-_ ]?shot|screen[-_ ]?recording|screenrecord|screenrecorder|record|recording|voice|audio|aud|ptt|call|rec|sound|memo)[-_ ]?.*[0-9]{4,}.*", RegexOption.IGNORE_CASE),
        Regex("^(img|vid|aud|doc|ptt|stk)-[0-9]{8}-wa[0-9]+.*", RegexOption.IGNORE_CASE), // WhatsApp
        Regex("^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}.*", RegexOption.IGNORE_CASE), // UUID
        Regex("^[0-9a-f]{16,}$", RegexOption.IGNORE_CASE), // hashes
        Regex("^[0-9 _\\-.()]+$"), // only numbers, e.g. 1693847223.pdf or 20240512_143022
        Regex("^[a-z]{0,4}[_-]?[0-9]{5,}([_-][0-9a-z]+)*$", RegexOption.IGNORE_CASE), // doc_1234567, a12345678
        Regex("^[a-z0-9]{20,}$", RegexOption.IGNORE_CASE), // random strings
    )

    /** Words that don't say anything about the content on their own. */
    private val genericWords = setOf(
        "download", "downloads", "downloaded", "document", "documents", "doc", "docs", "file", "files", "scan",
        "scanned", "scanner", "camscanner", "image", "images", "img", "photo", "photos", "picture", "pic", "copy",
        "final", "new", "untitled", "unnamed", "attachment", "attachments", "pdf", "page", "pages", "screenshot",
        "screen", "shot", "video", "vid", "audio", "recording", "record", "voice", "memo", "temp", "tmp", "test",
        "export", "exported", "output", "print", "printed", "file", "data", "info", "item", "misc", "stuff", "sheet",
        "book", "draft", "version", "ver", "edit", "edited", "resized", "cropped", "compressed", "share", "shared",
        "received", "sent", "whatsapp", "telegram", "signal", "messenger", "snapchat", "instagram", "facebook", "fb",
        "note", "notes", "text", "untitled", "noname", "blank", "the", "and", "for", "from", "with", "wa", "mp", "jpg",
        "jpeg", "png", "mp4", "mp3", "docx", "xlsx", "pptx", "zip", "apk", "original", "large", "small", "thumb",
        "thumbnail", "cover", "clip", "movie", "track", "song", "sound", "app", "application", "invoice_id",
    )

    /**
     * Words that name a sort of document but not which one: "receipt", "invoice", "statement".
     * A name made only of these (plus generic words) doesn't tell you what's inside.
     */
    private val vagueWords = setOf(
        "receipt", "receipts", "invoice", "invoices", "statement", "statements", "bill", "bills", "letter", "form", "forms",
        "report", "list", "contract", "agreement", "summary", "details", "confirmation", "order", "ticket", "tickets",
        "booking", "application", "contacts", "contact", "invite", "invitation", "event", "calendar", "backup", "archive",
        "photo", "scan", "document", "presentation", "spreadsheet", "slides", "table", "chart", "image", "screenshot",
    )

    private val copySuffix = Regex("\\s*(\\(\\d+\\)|-\\s*copy(\\s*\\d+)?|\\s+copy(\\s*\\d+)?|_\\d{1,2}|-\\d{1,2})$", RegexOption.IGNORE_CASE)

    /** Splits a name into words: "myTaxReturn_2023-final" → [my, Tax, Return, 2023, final]. */
    fun words(name: String): List<String> = name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split(Regex("[^\\p{L}\\p{N}']+"))
        .filter { it.isNotBlank() }

    /** Removes download/copy suffixes like " (3)" or " - Copy". */
    fun stripCopySuffix(base: String): String {
        var b = base.trim()
        while (true) {
            val next = b.replace(copySuffix, "").trim()
            if (next == b || next.isEmpty()) return b
            b = next
        }
    }

    /**
     * True when the name (without extension) tells a person what the file is,
     * e.g. "Car insurance 2024" but not "IMG_20240512_143022" or "download (3)".
     */
    fun isMeaningful(baseName: String): Boolean {
        val base = stripCopySuffix(baseName)
        if (base.isEmpty()) return false
        if (machinePatterns.any { it.matches(base) }) return false
        val realWords = words(base).filter { w ->
            val lw = w.lowercase()
            lw.length >= 3 && lw.any { it.isLetter() } && lw !in genericWords && looksLikeWord(lw)
        }
        if (realWords.all { it.lowercase() in vagueWords }) return false
        if (realWords.size >= 2) return true
        if (realWords.size == 1) {
            // One real word is enough when it is long and the rest of the name isn't mostly numbers.
            val w = realWords[0]
            val letters = base.count { it.isLetter() }
            val digits = base.count { it.isDigit() }
            return w.length >= 4 && letters >= digits
        }
        return false
    }

    /** Rejects letter salad like "xkcdq" or "IMG" by requiring a vowel-ish letter mix. */
    private fun looksLikeWord(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        if (letters.any { it.code > 0x24F }) return true // non-Latin scripts: trust them
        val vowels = letters.count { it in "aeiouyàáâãäåèéêëìíîïòóôõöùúûüý" }
        if (vowels == 0) return false
        val digits = w.count { it.isDigit() }
        return digits <= letters.length
    }

    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Makes a string safe as a file name on phones, SD cards and computers. */
    fun sanitize(name: String, maxLength: Int = 90): String {
        var s = name.replace(illegal, " ")
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ', '-', '_', ',')
        if (s.length > maxLength) {
            s = s.take(maxLength)
            val cut = s.lastIndexOf(' ')
            if (cut > maxLength / 2) s = s.take(cut)
            s = s.trim('.', ' ', '-', '_', ',')
        }
        return s
    }

    /** Turns "WALMART SUPERCENTER" into "Walmart Supercenter"; leaves mixed-case text alone. */
    fun tidyCase(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return text
        return text.lowercase().split(' ').joinToString(" ") { w ->
            if (w.length <= 3 && w in setOf("of", "and", "the", "for", "to", "in", "on", "at", "a", "an", "or")) w
            else w.replaceFirstChar { it.titlecase() }
        }.replaceFirstChar { it.titlecase() }
    }

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val minuteFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")

    fun day(d: LocalDateTime): String = d.format(dayFormat)
    fun dayAndTime(d: LocalDateTime): String = d.format(minuteFormat)
}
