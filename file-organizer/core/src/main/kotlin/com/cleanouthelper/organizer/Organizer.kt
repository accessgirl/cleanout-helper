package com.cleanouthelper.organizer

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ApplyResult(val moved: Int, val failed: List<Pair<File, String>>, val runId: String)
class UndoResult(val restored: Int, val failed: List<Pair<File, String>>)

/**
 * Carries out a [Plan]: moves and renames the files, remembers every move so it can be undone,
 * updates the catalog, and writes an index file into every folder.
 */
class Organizer(
    val outputRoot: File,
    /** Told about files that moved, so the phone's gallery and music apps can update. */
    private val onFilesChanged: (List<File>) -> Unit = {},
) {
    val dataDir = File(outputRoot, ".organizer")
    private val runsDir = File(dataDir, "runs")
    val catalog by lazy { Catalog(File(dataDir, "catalog.tsv")) }

    fun apply(plan: Plan, progress: ProgressListener, isCancelled: () -> Boolean = { false }): ApplyResult {
        runsDir.mkdirs()
        File(dataDir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        var runId = stamp
        var n = 2
        while (File(runsDir, "$runId.tsv").exists() || File(runsDir, "$runId.undone").exists()) runId = "$stamp-${n++}"
        val journal = File(runsDir, "$runId.tsv")
        val failed = mutableListOf<Pair<File, String>>()
        var moved = 0
        val changed = mutableListOf<File>()
        journal.bufferedWriter(Charsets.UTF_8).use { log ->
            plan.moves.forEachIndexed { i, move ->
                if (isCancelled()) return@forEachIndexed
                progress.onProgress(i, plan.moves.size, "Moving ${move.source.name}")
                val source = move.source
                if (!source.exists()) {
                    failed += source to "it was no longer there"
                    return@forEachIndexed
                }
                // If something new appeared at the target since the plan was made, pick a free name.
                val target = if (move.target.exists()) freeName(move.target) else move.target
                try {
                    moveFile(source, target)
                    log.write(Catalog.escape(source.path) + "\t" + Catalog.escape(target.path) + "\n")
                    log.flush() // written as we go, so even an interrupted run can be undone
                    moved++
                    changed += source
                    changed += target
                    catalog.put(entryFor(move, target, runId, plan.storageRoot))
                } catch (e: Exception) {
                    failed += source to (e.message ?: e.javaClass.simpleName)
                }
                if (changed.size >= 200) {
                    onFilesChanged(changed.toList()); changed.clear()
                }
            }
        }
        if (changed.isNotEmpty()) onFilesChanged(changed)
        if (moved == 0) journal.delete()
        catalog.prune(outputRoot)
        catalog.save()
        progress.onProgress(plan.moves.size, plan.moves.size, "Writing index files…")
        IndexWriter(outputRoot, catalog).writeAll()
        return ApplyResult(moved, failed, runId)
    }

    private fun entryFor(move: PlannedMove, target: File, runId: String, storageRoot: File): CatalogEntry {
        val f = move.facts
        val note = move.duplicateOf?.let { kept ->
            "Identical copy of: " + kept.relativeToOrSelf(outputRoot).invariantSeparatorsPath
        }.orEmpty()
        return CatalogEntry(
            path = target.relativeTo(outputRoot).invariantSeparatorsPath,
            originalPath = move.source.relativeToOrSelf(storageRoot).invariantSeparatorsPath,
            kind = f.kind.name,
            date = Names.dayAndTime(Placement.bestDate(f)),
            size = f.size,
            description = if (move.isDuplicate) "Duplicate copy" else f.describe(),
            preview = if (move.isDuplicate) "" else f.preview().orEmpty(),
            note = note,
            searchText = listOfNotNull(f.title, f.text?.take(1500), f.tags.joinToString(" "), f.place, f.author)
                .joinToString(" ").replace(Regex("\\s+"), " "),
            runId = runId,
        )
    }

    /** The most recent run that can still be undone, or null. */
    fun lastRun(): File? = runsDir.listFiles { f -> f.name.endsWith(".tsv") }?.maxByOrNull { it.name }

    /** Puts every file from the most recent run back where it was, with its old name. */
    fun undoLast(progress: ProgressListener): UndoResult {
        val journal = lastRun() ?: return UndoResult(0, emptyList())
        val runId = journal.nameWithoutExtension
        val lines = journal.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.reversed()
        val failed = mutableListOf<Pair<File, String>>()
        var restored = 0
        val changed = mutableListOf<File>()
        lines.forEachIndexed { i, line ->
            val parts = line.split('\t').map { Catalog.unescape(it) }
            if (parts.size < 2) return@forEachIndexed
            val original = File(parts[0])
            val now = File(parts[1])
            progress.onProgress(i, lines.size, "Putting back ${original.name}")
            if (!now.exists()) {
                failed += now to "it has been moved or deleted since"
                return@forEachIndexed
            }
            try {
                val dest = if (original.exists()) freeName(original) else original
                moveFile(now, dest)
                changed += now
                changed += dest
                restored++
                catalog.remove(now.relativeTo(outputRoot).invariantSeparatorsPath)
            } catch (e: Exception) {
                failed += now to (e.message ?: e.javaClass.simpleName)
            }
        }
        onFilesChanged(changed)
        journal.renameTo(File(runsDir, "$runId.undone"))
        removeEmptyFolders()
        catalog.prune(outputRoot)
        catalog.save()
        IndexWriter(outputRoot, catalog).writeAll()
        return UndoResult(restored, failed)
    }

    /** Rewrites all index files, e.g. after the person moved or deleted files themselves. */
    fun refreshIndexes() {
        catalog.prune(outputRoot)
        catalog.save()
        removeEmptyFolders()
        IndexWriter(outputRoot, catalog).writeAll()
    }

    /** Searches the catalog, plus any files the person added to the organized folder themselves. */
    fun search(query: String): List<CatalogEntry> {
        val found = catalog.search(query).toMutableList()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return found
        for (rel in untrackedFiles) {
            if (terms.all { it in rel.lowercase() }) {
                val f = File(outputRoot, rel)
                found += CatalogEntry(rel, "", FileKind.of(f).name, "", f.length(), FileKind.of(f).label, "", "", "", "")
            }
        }
        return found
    }

    /** Files in the organized folder that the organizer didn't put there (listed once per search session). */
    private val untrackedFiles: List<String> by lazy {
        if (!outputRoot.isDirectory) return@lazy emptyList()
        outputRoot.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && !Planner.isIndexFile(it.name) }
            .map { it.relativeTo(outputRoot).invariantSeparatorsPath }
            .filter { catalog[it] == null }
            .toList()
    }

    /** Deletes folders left empty inside the organized folder (apart from their index file). */
    private fun removeEmptyFolders() {
        if (!outputRoot.isDirectory) return
        outputRoot.walkBottomUp().filter { it.isDirectory && it != outputRoot && !it.path.startsWith(dataDir.path) }.forEach { dir ->
            val children = dir.listFiles().orEmpty()
            if (children.all { it.isFile && Planner.isIndexFile(it.name) }) {
                children.forEach { it.delete() }
                dir.delete()
            }
        }
    }

    companion object {
        fun freeName(file: File): File {
            val base = file.nameWithoutExtension
            val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
            var n = 2
            var candidate = file
            while (candidate.exists()) {
                candidate = File(file.parentFile, "$base ($n)$ext")
                n++
            }
            return candidate
        }

        /** Moves a file, falling back to copy-then-delete when a plain rename isn't possible. */
        fun moveFile(source: File, target: File) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) return
            try {
                Files.move(source.toPath(), target.toPath())
                return
            } catch (_: Exception) {
            }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            if (target.length() != source.length()) {
                target.delete()
                throw java.io.IOException("the copy didn't finish (is the phone full?)")
            }
            target.setLastModified(source.lastModified())
            if (!source.delete()) {
                target.delete()
                throw java.io.IOException("the original couldn't be removed")
            }
        }
    }
}
