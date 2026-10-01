package com.cleanouthelper.organizer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small but real files (Word, Excel, …) for the tests. */
object TestFiles {
    fun zip(file: File, entries: Map<String, String>): File {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    fun docx(file: File, paragraphs: List<String>, title: String? = null): File = zip(
        file,
        buildMap {
            put(
                "word/document.xml",
                "<?xml version=\"1.0\"?><w:document><w:body>" +
                    paragraphs.joinToString("") { "<w:p><w:r><w:t>${it.replace("&", "&amp;")}</w:t></w:r></w:p>" } +
                    "</w:body></w:document>",
            )
            if (title != null) put("docProps/core.xml", "<cp:coreProperties><dc:title>$title</dc:title><dc:creator>Microsoft Office User</dc:creator></cp:coreProperties>")
        },
    )

    fun xlsx(file: File, sheets: List<String>, strings: List<String>): File = zip(
        file,
        mapOf(
            "xl/workbook.xml" to "<workbook><sheets>" + sheets.joinToString("") { "<sheet name=\"$it\" sheetId=\"1\"/>" } + "</sheets></workbook>",
            "xl/sharedStrings.xml" to "<sst>" + strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>",
        ),
    )

    fun pptx(file: File, slides: List<String>): File = zip(
        file,
        slides.mapIndexed { i, s -> "ppt/slides/slide${i + 1}.xml" to "<p:sld><a:p><a:r><a:t>$s</a:t></a:r></a:p></p:sld>" }.toMap(),
    )

    fun write(file: File, text: String, modified: Long? = null): File {
        file.parentFile.mkdirs()
        file.writeText(text)
        modified?.let { file.setLastModified(it) }
        return file
    }

    fun bytes(file: File, content: ByteArray): File {
        file.parentFile.mkdirs()
        file.writeBytes(content)
        return file
    }
}
