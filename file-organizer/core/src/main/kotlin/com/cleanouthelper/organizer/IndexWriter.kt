package com.cleanouthelper.organizer

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a plain-text index into every organized folder, named "000 INDEX - <folder>.txt" so it
 * sorts to the top. It lists each file with its date, size, a description, the first words inside,
 * and its old name, so you can find things without opening them. The top folder gets a master index
 * of everything.
 */
class IndexWriter(private val outputRoot: File, private val catalog: Catalog) {
    companion object {
        const val INDEX_PREFIX = "000 INDEX - "
        const val MASTER_PREFIX = "000 MASTER INDEX"
        const val MASTER_NAME = "000 MASTER INDEX - All Files.txt"
        private const val RULE = "────────────────────────────────"
    }

    private val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

    fun writeAll() {
        if (!outputRoot.isDirectory) return
        val dirs = outputRoot.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory }.toList()
        for (dir in dirs) {
            if (dir == outputRoot) writeMaster() else writeFolderIndex(dir)
        }
    }

    private fun filesIn(dir: File) = dir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && !Planner.isIndexFile(it.name) }
        .sortedBy { it.name.lowercase() }

    private fun subfoldersIn(dir: File) = dir.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }

    private fun countFilesUnder(dir: File): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        dir.walkTopDown().onEnter { !it.name.startsWith(".") }.forEach { f ->
            if (f.isFile && !Planner.isIndexFile(f.name) && !f.name.startsWith(".")) {
                n++; bytes += f.length()
            }
        }
        return n to bytes
    }

    private fun rel(f: File) = f.relativeTo(outputRoot).invariantSeparatorsPath

    private fun writeFolderIndex(dir: File) {
        // Remove old index files (the folder may have been renamed).
        dir.listFiles { f -> f.isFile && f.name.startsWith(INDEX_PREFIX) }?.forEach { it.delete() }
        val files = filesIn(dir)
        val subs = subfoldersIn(dir)
        if (files.isEmpty() && subs.isEmpty()) return
        val (total, bytes) = countFilesUnder(dir)
        val sb = StringBuilder()
        sb.appendLine("INDEX OF THIS FOLDER: ${dir.name}")
        sb.appendLine("Where: ${outputRoot.name} › ${rel(dir).replace("/", " › ")}")
        sb.appendLine("$total file${if (total == 1) "" else "s"} · ${formatSize(bytes)} · updated $now")
        sb.appendLine(RULE)
        folderAdvice(dir)?.let { sb.appendLine(it).appendLine(RULE) }
        if (subs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FOLDERS IN HERE")
            for (s in subs) {
                val (n, b) = countFilesUnder(s)
                sb.appendLine("  📁 ${s.name}  ($n file${if (n == 1) "" else "s"}, ${formatSize(b)})")
            }
        }
        if (files.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FILES IN HERE (A to Z)")
            files.forEachIndexed { i, f -> appendFile(sb, i + 1, f) }
        }
        sb.appendLine()
        sb.appendLine(RULE)
        sb.appendLine("Made by File Organizer. This list is rebuilt every time you organize.")
        File(dir, Names.sanitize(INDEX_PREFIX + dir.name, 120) + ".txt").writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun appendFile(sb: StringBuilder, number: Int, f: File) {
        val e = catalog[rel(f)]
        sb.appendLine()
        sb.appendLine("$number. ${f.name}")
        val date = e?.date?.takeIf { it.isNotEmpty() }?.substringBefore(' ')
            ?: Names.day(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()), java.time.ZoneId.systemDefault()))
        val what = e?.description?.takeIf { it.isNotEmpty() } ?: FileKind.of(f).label
        sb.appendLine("   $date · ${formatSize(f.length())} · $what")
        e?.preview?.takeIf { it.isNotEmpty() }?.let { sb.appendLine("   Inside: \"$it\"") }
        e?.note?.takeIf { it.isNotEmpty() }?.let { sb.appendLine("   ⚠ $it") }
        e?.originalPath?.takeIf { it.isNotEmpty() }?.let { orig ->
            if (File(orig).name != f.name) sb.appendLine("   Old name: $orig")
            else sb.appendLine("   Came from: " + orig.substringBeforeLast('/', "the top of your storage"))
        }
    }

    private fun folderAdvice(dir: File): String? {
        val r = rel(dir)
        return when {
            r == Placement.DUPLICATES_FOLDER ->
                "These are EXTRA COPIES of files that are identical, byte for byte, to a file that was kept.\n" +
                    "Each numbered folder is one set of copies and is named after the copy that was kept.\n" +
                    "Nothing has been deleted. Look through them, then delete the copies you don't need\n" +
                    "(or the whole folder). If you want to keep a copy, move it to wherever you like."
            r.startsWith(Placement.DUPLICATES_FOLDER + "/") -> {
                val kept = filesIn(dir).firstNotNullOfOrNull { catalog[rel(it)]?.note }?.removePrefix("Identical copy of: ")
                "The files in this folder are exact copies of:\n  $kept\n" +
                    "That copy was kept. You can delete these if you don't need them."
            }
            else -> null
        }
    }

    private fun writeMaster() {
        outputRoot.listFiles { f -> f.isFile && f.name.startsWith(MASTER_PREFIX) }?.forEach { it.delete() }
        val (total, bytes) = countFilesUnder(outputRoot)
        val sb = StringBuilder()
        sb.appendLine("MASTER INDEX: EVERYTHING IN \"${outputRoot.name}\"")
        sb.appendLine("$total files · ${formatSize(bytes)} · updated $now")
        sb.appendLine(RULE)
        sb.appendLine("Tip: use Find/Search in your text viewer to look for a word, a name, a shop,")
        sb.appendLine("a date (like 2024-05) or an old file name. Every folder also has its own")
        sb.appendLine("\"000 INDEX\" file at the top. The File Organizer app can search inside files too.")
        sb.appendLine(RULE)
        sb.appendLine()
        sb.appendLine("MAIN FOLDERS")
        for (s in subfoldersIn(outputRoot)) {
            val (n, b) = countFilesUnder(s)
            sb.appendLine("  📁 ${s.name}  ($n file${if (n == 1) "" else "s"}, ${formatSize(b)})")
        }
        val dirs = outputRoot.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory }.toList()
            .sortedBy { rel(it).lowercase() }
        for (dir in dirs) {
            val files = filesIn(dir)
            if (files.isEmpty()) continue
            sb.appendLine()
            sb.appendLine("━━ ${if (dir == outputRoot) outputRoot.name else rel(dir).replace("/", " › ")} ━━")
            for (f in files) {
                val e = catalog[rel(f)]
                val bits = listOfNotNull(
                    e?.date?.substringBefore(' ')?.takeIf { it.isNotEmpty() },
                    e?.description?.takeIf { it.isNotEmpty() },
                    e?.preview?.takeIf { it.isNotEmpty() }?.let { "\"${it.take(80)}\"" },
                    e?.originalPath?.takeIf { it.isNotEmpty() && File(it).name != f.name }?.let { "was ${File(it).name}" },
                )
                sb.appendLine("• ${f.name}" + if (bits.isEmpty()) "" else "  (${bits.joinToString(" · ")})")
            }
        }
        File(outputRoot, MASTER_NAME).writeText(sb.toString(), Charsets.UTF_8)
    }
}
