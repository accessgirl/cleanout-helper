package com.cleanouthelper.organizer

import java.io.File

/** What the organizer knows about one organized file. [path] is relative to the organized folder. */
data class CatalogEntry(
    val path: String,
    val originalPath: String,
    val kind: String,
    val date: String,
    val size: Long,
    val description: String,
    val preview: String,
    val note: String,
    val searchText: String,
    val runId: String,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', "")
}

/**
 * The list of organized files, saved as a tab-separated file inside the hidden ".organizer" folder.
 * It powers the search screen and the "what's inside" lines in the index files.
 */
class Catalog(private val file: File) {
    private val entries = LinkedHashMap<String, CatalogEntry>()

    init {
        if (file.exists()) {
            file.forEachLine(Charsets.UTF_8) { line ->
                val f = line.split('\t').map { unescape(it) }
                if (f.size >= 10) {
                    val e = CatalogEntry(f[0], f[1], f[2], f[3], f[4].toLongOrNull() ?: 0, f[5], f[6], f[7], f[8], f[9])
                    entries[key(e.path)] = e
                }
            }
        }
    }

    val all: Collection<CatalogEntry> get() = entries.values

    operator fun get(path: String): CatalogEntry? = entries[key(path)]

    fun put(entry: CatalogEntry) {
        entries[key(entry.path)] = entry
    }

    fun remove(path: String) {
        entries.remove(key(path))
    }

    /** Drops entries for files that are no longer there (deleted or moved away by the person). */
    fun prune(outputRoot: File) {
        entries.values.removeIf { !File(outputRoot, it.path).exists() }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            for (e in entries.values) {
                w.write(
                    listOf(e.path, e.originalPath, e.kind, e.date, e.size.toString(), e.description, e.preview, e.note, e.searchText, e.runId)
                        .joinToString("\t") { escape(it) },
                )
                w.write("\n")
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /**
     * Files matching every word typed, best matches first. Looks at the name, folder, description,
     * words inside the file, and the file's old name.
     */
    fun search(query: String, limit: Int = 300): List<CatalogEntry> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return entries.values.mapNotNull { e ->
            val name = e.name.lowercase()
            val folder = e.folder.lowercase()
            val about = (e.description + " " + e.preview + " " + e.note).lowercase()
            val inside = e.searchText.lowercase()
            val old = e.originalPath.lowercase()
            var score = 0
            for (t in terms) {
                score += when {
                    t in name -> 10
                    t in folder -> 6
                    t in about -> 4
                    t in inside -> 2
                    t in old -> 1
                    else -> return@mapNotNull null
                }
            }
            e to score
        }.sortedWith(compareByDescending<Pair<CatalogEntry, Int>> { it.second }.thenByDescending { it.first.date })
            .take(limit)
            .map { it.first }
    }

    private fun key(path: String) = path.lowercase()

    companion object {
        fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
        fun unescape(s: String): String {
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
