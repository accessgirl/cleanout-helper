package com.cleanouthelper.organizer

import java.io.File
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipFile

/**
 * Reads titles and text from common file types without any extra libraries:
 * Word/Excel/PowerPoint (.docx .xlsx .pptx), OpenDocument, text, CSV, RTF, web pages,
 * emails, contacts, calendar events, eBooks, and lists what's inside zip files.
 * (PDFs, photos, music and videos are read by the phone app, which has the tools for them.)
 */
object DocumentReaders : FileAnalyzer {
    private const val MAX_TEXT = 6000
    private const val MAX_READ_BYTES = 4L * 1024 * 1024

    override fun analyze(facts: FileFacts, options: OrganizeOptions) {
        val ext = facts.file.extension.lowercase()
        try {
            when (ext) {
                "txt", "md", "markdown", "text" -> readPlainText(facts)
                "csv", "tsv" -> readCsv(facts, if (ext == "tsv") '\t' else ',')
                "rtf" -> readRtf(facts)
                "docx" -> readOfficeZip(facts, listOf("word/document.xml"), "w:p")
                "xlsx", "xlsm" -> readXlsx(facts)
                "pptx" -> readPptx(facts)
                "odt", "ods", "odp" -> readOpenDocument(facts)
                "html", "htm" -> readHtml(facts)
                "eml" -> readEmail(facts)
                "vcf", "vcard" -> readContacts(facts)
                "ics", "vcs" -> readCalendar(facts)
                "epub" -> readEpub(facts)
                "zip" -> readZipListing(facts)
            }
        } catch (e: Exception) {
            // A damaged or unusual file: keep going with what we know.
        }
        if (facts.kind.isDocument && facts.date == null) {
            facts.text?.let { t -> Dates.fromText(t)?.let { facts.offerDate(it.atTime(LocalTime.NOON), DateSource.CONTENT) } }
        }
    }

    // ---------- plain text ----------

    private fun readHead(file: File, bytes: Int = 64 * 1024): String {
        val buf = file.inputStream().use { input ->
            val out = ByteArray(minOf(bytes.toLong(), file.length()).toInt())
            var read = 0
            while (read < out.size) {
                val n = input.read(out, read, out.size - read)
                if (n < 0) break
                read += n
            }
            out.copyOf(read)
        }
        return decode(buf)
    }

    /** UTF-8 if it looks like UTF-8 (or has a BOM), otherwise Windows-1252. */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.count { it == '�' } > 3) String(bytes, Charset.forName("windows-1252")) else utf8.removePrefix("﻿")
    }

    private fun readPlainText(facts: FileFacts) {
        val text = readHead(facts.file)
        facts.text = text.take(MAX_TEXT)
        val md = Regex("^#{1,3}\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        facts.title = md?.let { cleanLine(it) } ?: TextTitles.firstGoodLine(text)
    }

    private fun readCsv(facts: FileFacts, sep: Char) {
        val text = readHead(facts.file)
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split(sep)?.map { it.trim().trim('"') }?.filter { it.isNotBlank() }.orEmpty()
        if (header.isNotEmpty()) {
            facts.title = "Table of " + header.take(3).joinToString(", ")
            facts.details += "columns: " + header.take(8).joinToString(", ")
        }
        facts.details += "about ${lines.size - 1} rows" + if (facts.file.length() > 64 * 1024) "+" else ""
        facts.text = text.take(MAX_TEXT)
    }

    private fun readRtf(facts: FileFacts) {
        val raw = readHead(facts.file, 128 * 1024)
        val text = raw
            .replace(Regex("\\{\\\\\\*[^{}]*\\}"), " ")
            .replace(Regex("\\\\(par|line)\\b"), "\n")
            .replace(Regex("\\\\'[0-9a-f]{2}"), "")
            .replace(Regex("\\\\[a-zA-Z]+-?[0-9]* ?"), "")
            .replace(Regex("[{}]"), "")
        facts.text = text.trim().take(MAX_TEXT)
        facts.title = TextTitles.firstGoodLine(text)
    }

    // ---------- Office / OpenDocument ----------

    private fun ZipFile.readEntry(name: String, limit: Long = MAX_READ_BYTES): String? {
        val entry = getEntry(name) ?: return null
        return getInputStream(entry).use { input ->
            val bytes = input.readNBytesCompat(limit.toInt())
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Office "core properties": title, author, created date. */
    private fun readCoreProps(zip: ZipFile, facts: FileFacts) {
        val core = zip.readEntry("docProps/core.xml") ?: return
        Xml.tag(core, "dc:title")?.let { t -> if (TextTitles.isUsefulTitle(t)) facts.title = cleanLine(t) }
        Xml.tag(core, "dc:creator")?.let { a -> if (a.isNotBlank() && TextTitles.isPersonName(a)) facts.author = cleanLine(a) }
        Xml.tag(core, "dcterms:created")?.let { d -> parseIsoDate(d)?.let { facts.offerDate(it, DateSource.CONTENT) } }
    }

    private fun readOfficeZip(facts: FileFacts, parts: List<String>, paragraphTag: String) {
        ZipFile(facts.file).use { zip ->
            readCoreProps(zip, facts)
            val xml = parts.mapNotNull { zip.readEntry(it) }.joinToString("\n")
            val text = Xml.paragraphs(xml, paragraphTag)
            facts.text = text.take(MAX_TEXT)
            if (facts.title == null) facts.title = TextTitles.firstGoodLine(text)
            zip.readEntry("docProps/app.xml")?.let { app -> Xml.tag(app, "Pages")?.toIntOrNull()?.let { facts.pages = it } }
        }
    }

    private fun readXlsx(facts: FileFacts) {
        ZipFile(facts.file).use { zip ->
            readCoreProps(zip, facts)
            val sheets = zip.readEntry("xl/workbook.xml")?.let { wb ->
                Regex("<sheet [^>]*name=\"([^\"]+)\"").findAll(wb).map { Xml.unescape(it.groupValues[1]) }.toList()
            }.orEmpty()
            val strings = zip.readEntry("xl/sharedStrings.xml", 1024 * 1024)?.let { Xml.allTagText(it, "t") }.orEmpty()
            facts.text = strings.joinToString("\n").take(MAX_TEXT)
            val realSheets = sheets.filterNot { Regex("(?i)^(sheet|feuil|hoja|tabelle|blad)\\s*\\d+$").matches(it) }
            if (sheets.isNotEmpty()) facts.details += "sheets: " + sheets.take(5).joinToString(", ")
            if (facts.title == null) {
                facts.title = realSheets.firstOrNull { TextTitles.isUsefulTitle(it) }?.let { cleanLine(it) }
                    ?: TextTitles.firstGoodLine(strings.take(20).joinToString("\n"))
            }
        }
    }

    private fun readPptx(facts: FileFacts) {
        ZipFile(facts.file).use { zip ->
            readCoreProps(zip, facts)
            val slideNames = zip.entries().asSequence().map { it.name }
                .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                .toList()
            facts.pages = slideNames.size.takeIf { it > 0 }
            val text = slideNames.take(15).mapNotNull { zip.readEntry(it) }.joinToString("\n") { Xml.paragraphs(it, "a:p") }
            facts.text = text.take(MAX_TEXT)
            if (facts.title == null) facts.title = TextTitles.firstGoodLine(text)
        }
        facts.pages?.let { facts.details += if (it == 1) "1 slide" else "$it slides"; facts.pages = null }
    }

    private fun readOpenDocument(facts: FileFacts) {
        ZipFile(facts.file).use { zip ->
            zip.readEntry("meta.xml")?.let { meta ->
                Xml.tag(meta, "dc:title")?.let { if (TextTitles.isUsefulTitle(it)) facts.title = cleanLine(it) }
                Xml.tag(meta, "meta:initial-creator")?.let { if (TextTitles.isPersonName(it)) facts.author = cleanLine(it) }
                Xml.tag(meta, "meta:creation-date")?.let { d -> parseIsoDate(d)?.let { facts.offerDate(it, DateSource.CONTENT) } }
            }
            val content = zip.readEntry("content.xml").orEmpty()
            val text = Xml.paragraphs(content, "text:p", "text:h")
            facts.text = text.take(MAX_TEXT)
            if (facts.title == null) facts.title = TextTitles.firstGoodLine(text)
        }
    }

    // ---------- web, email, contacts, calendar, eBooks, zips ----------

    private fun readHtml(facts: FileFacts) {
        val html = readHead(facts.file, 256 * 1024)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { Xml.unescape(it) }
        val body = Xml.stripTags(html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " "))
        facts.text = body.take(MAX_TEXT)
        facts.title = title?.takeIf { TextTitles.isUsefulTitle(it) }?.let { cleanLine(it) } ?: TextTitles.firstGoodLine(body)
    }

    private fun readEmail(facts: FileFacts) {
        val raw = readHead(facts.file, 128 * 1024)
        val headerBlock = raw.substringBefore("\r\n\r\n").substringBefore("\n\n").replace(Regex("\r?\n[ \t]+"), " ")
        fun header(name: String) = Regex("(?im)^$name:\\s*(.+)$").find(headerBlock)?.groupValues?.get(1)?.trim()
        header("Subject")?.let { facts.title = cleanLine(MimeWords.decode(it)) }
        header("From")?.let { from ->
            val name = MimeWords.decode(from).substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>') }
            facts.author = name
        }
        header("Date")?.let { d ->
            try {
                val zdt = java.time.ZonedDateTime.parse(d.replace(Regex("\\s*\\(.*\\)$"), ""), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                facts.offerDate(zdt.toLocalDateTime(), DateSource.CONTENT)
            } catch (_: Exception) {
            }
        }
        facts.text = raw.substring(minOf(raw.length, headerBlock.length)).take(MAX_TEXT)
    }

    private fun readContacts(facts: FileFacts) {
        val text = readHead(facts.file, 512 * 1024)
        val names = Regex("(?m)^FN[^:]*:(.+)$").findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        when {
            names.size == 1 -> facts.title = names[0]
            names.size > 1 -> {
                facts.title = "${names.size} contacts"
                facts.details += "includes " + names.take(5).joinToString(", ") + if (names.size > 5) ", …" else ""
            }
        }
        facts.text = names.joinToString("\n").take(MAX_TEXT)
    }

    private fun readCalendar(facts: FileFacts) {
        val text = readHead(facts.file, 512 * 1024).replace(Regex("\r?\n[ \t]"), "")
        val summaries = Regex("(?m)^SUMMARY[^:]*:(.+)$").findAll(text).map { it.groupValues[1].trim().replace("\\,", ",") }.toList()
        val start = Regex("(?m)^DTSTART[^:]*:([0-9]{8})(T([0-9]{4}))?").find(text)
        start?.let { m ->
            val d = m.groupValues[1]
            val t = m.groupValues[3]
            try {
                val date = LocalDate.of(d.take(4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
                val time = if (t.isNotEmpty()) LocalTime.of(t.take(2).toInt(), t.drop(2).toInt()) else LocalTime.NOON
                facts.offerDate(LocalDateTime.of(date, time), DateSource.CONTENT)
            } catch (_: Exception) {
            }
        }
        facts.title = when {
            summaries.size == 1 -> summaries[0]
            summaries.size > 1 -> "${summaries.size} events"
            else -> null
        }
        if (summaries.size > 1) facts.details += "includes " + summaries.take(4).joinToString(", ")
        facts.text = summaries.joinToString("\n").take(MAX_TEXT)
    }

    private fun readEpub(facts: FileFacts) {
        ZipFile(facts.file).use { zip ->
            val opfName = zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf", ignoreCase = true) } ?: return
            val opf = zip.readEntry(opfName) ?: return
            Xml.tag(opf, "dc:title")?.let { facts.title = cleanLine(it) }
            Xml.tag(opf, "dc:creator")?.let { facts.author = cleanLine(it) }
        }
    }

    private fun readZipListing(facts: FileFacts) {
        ZipFile(facts.file).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            if (names.isEmpty()) return
            facts.details += if (names.size == 1) "1 file inside" else "${names.size} files inside"
            val topFolders = names.map { it.substringBefore('/', "") }.filter { it.isNotEmpty() }.distinct()
            val kinds = names.groupingBy { FileKind.of(File(it)).label }.eachCount().maxByOrNull { it.value }
            if (topFolders.size == 1 && Names.isMeaningful(topFolders[0])) facts.title = topFolders[0]
            else if (names.size == 1 && Names.isMeaningful(File(names[0]).nameWithoutExtension)) facts.title = File(names[0]).nameWithoutExtension
            if (kinds != null && kinds.key != FileKind.OTHER.label && kinds.value * 2 > names.size) facts.details += "mostly ${kinds.key.lowercase()}s"
            facts.text = names.take(200).joinToString("\n")
        }
    }

    private fun cleanLine(s: String) = Names.tidyCase(Xml.unescape(s).replace(Regex("\\s+"), " ").trim())

    private fun parseIsoDate(s: String): LocalDateTime? = try {
        java.time.OffsetDateTime.parse(s.trim()).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s.trim().removeSuffix("Z").substringBefore('.'))
        } catch (_: Exception) {
            null
        }
    }
}

/** Tiny helpers for pulling text out of XML without a full parser. */
object Xml {
    fun unescape(s: String): String = s
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#([0-9]+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    fun tag(xml: String, name: String): String? =
        Regex("(?s)<${Regex.escape(name)}(\\s[^>]*)?>(.*?)</${Regex.escape(name)}>").find(xml)?.groupValues?.get(2)
            ?.let { unescape(stripTags(it)).trim() }?.takeIf { it.isNotEmpty() }

    fun allTagText(xml: String, name: String): List<String> =
        Regex("(?s)<${Regex.escape(name)}(\\s[^>]*)?>(.*?)</${Regex.escape(name)}>").findAll(xml)
            .map { unescape(it.groupValues[2]).trim() }.filter { it.isNotEmpty() }.toList()

    fun stripTags(s: String): String = unescape(
        s.replace(Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>"), "\n").replace(Regex("<[^>]+>"), " "),
    ).replace(Regex("[ \t]+"), " ").replace(Regex("\n\\s*\n+"), "\n").trim()

    /** Text of each paragraph element (e.g. <w:p>), one per line. */
    fun paragraphs(xml: String, vararg paragraphTags: String): String {
        val alternation = paragraphTags.joinToString("|") { Regex.escape(it) }
        return Regex("(?s)<($alternation)(\\s[^>]*)?>(.*?)</\\1>").findAll(xml)
            .map { m -> unescape(m.groupValues[3].replace(Regex("<(w:tab|text:tab|w:br|a:br)[^>]*/>"), " ").replace(Regex("<[^>]+>"), "")).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}

/** Decodes "=?UTF-8?B?...?=" words in email headers. */
object MimeWords {
    private val word = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
    fun decode(s: String): String = word.replace(s.replace(Regex("\\?=\\s+=\\?"), "?==?")) { m ->
        try {
            val charset = Charset.forName(m.groupValues[1])
            val bytes = if (m.groupValues[2].equals("B", true)) {
                java.util.Base64.getDecoder().decode(m.groupValues[3])
            } else {
                val q = m.groupValues[3].replace('_', ' ')
                val out = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < q.length) {
                    if (q[i] == '=' && i + 2 < q.length) {
                        out.write(q.substring(i + 1, i + 3).toInt(16)); i += 3
                    } else {
                        out.write(q[i].code); i++
                    }
                }
                out.toByteArray()
            }
            String(bytes, charset)
        } catch (_: Exception) {
            m.value
        }
    }
}
