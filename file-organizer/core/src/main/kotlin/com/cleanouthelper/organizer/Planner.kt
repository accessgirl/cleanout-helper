package com.cleanouthelper.organizer

import java.io.File

/** A top-level folder on the phone that the person can include or leave out. */
data class FolderChoice(val name: String, val selectedByDefault: Boolean, val note: String?)

/** One file's planned move. [target] is where it will go (inside the organized folder). */
class PlannedMove(
    val facts: FileFacts,
    var target: File,
    val renamed: Boolean,
    /** For an extra copy of a duplicate: the copy that is being kept. */
    val duplicateOf: File? = null,
) {
    val source: File get() = facts.file
    val isDuplicate: Boolean get() = duplicateOf != null
}

class DuplicateGroup(val folder: File, val kept: File, val extras: List<PlannedMove>) {
    val wastedBytes: Long get() = extras.sumOf { it.facts.size }
}

class Plan(
    val storageRoot: File,
    val outputRoot: File,
    val moves: List<PlannedMove>,
    val duplicateGroups: List<DuplicateGroup>,
) {
    val renamedCount get() = moves.count { it.renamed }
    val duplicateCount get() = moves.count { it.isDuplicate }
    val wastedBytes get() = duplicateGroups.sumOf { it.wastedBytes }

    /** How many files go to each top-level folder, e.g. "Photos" → 812. */
    fun countsByFolder(): Map<String, Int> = moves.groupingBy {
        it.target.relativeTo(outputRoot).invariantSeparatorsPath.substringBefore('/')
    }.eachCount().toSortedMap()
}

/** Progress messages while working. */
fun interface ProgressListener {
    fun onProgress(done: Int, total: Int, message: String)
}

class Planner(
    private val storageRoot: File,
    private val options: OrganizeOptions,
    /** Extra readers, e.g. the phone app's PDF, photo and music readers. Run after the built-in ones. */
    private val analyzers: List<FileAnalyzer> = emptyList(),
) {
    val outputRoot = File(storageRoot, options.outputFolderName)

    companion object {
        /** Standard phone folders that are safe to organize. Everything else is left unticked at first. */
        val STANDARD_FOLDERS = setOf(
            "download", "downloads", "documents", "dcim", "pictures", "movies", "music", "recordings",
            "audiobooks", "podcasts", "screenshots", "screen recordings", "voice recorder", "sounds", "bluetooth",
        )

        /** Folders the organizer never touches. */
        val NEVER = setOf("android", "alarms", "notifications", "ringtones", "lost.dir", "lost+found")

        fun isIndexFile(name: String) = name.startsWith(IndexWriter.INDEX_PREFIX) || name.startsWith(IndexWriter.MASTER_PREFIX)
    }

    /** The top-level folders on the phone, with sensible starting choices. */
    fun folderChoices(): List<FolderChoice> {
        val dirs = storageRoot.listFiles()?.filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") }.orEmpty()
        return dirs.filter { it.name.lowercase() !in NEVER && it.name != options.outputFolderName }
            .sortedBy { it.name.lowercase() }
            .map { dir ->
                val standard = dir.name.lowercase() in STANDARD_FOLDERS
                FolderChoice(dir.name, standard, if (standard) null else "made by an app - moving its files may hide them from that app")
            }
    }

    /** Every file in the chosen folders (and loose files at the top level, if asked). Hidden files are skipped. */
    fun listFiles(chosenFolders: Set<String>, includeLooseFiles: Boolean, isCancelled: () -> Boolean = { false }): List<File> {
        val result = mutableListOf<File>()
        val top = storageRoot.listFiles().orEmpty()
        if (includeLooseFiles) result += top.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 }
        for (dir in top.filter { it.isDirectory && it.name in chosenFolders }) {
            if (dir.name.lowercase() in NEVER || dir.name == options.outputFolderName) continue
            walk(dir, result, isCancelled)
        }
        return result
    }

    private fun walk(dir: File, into: MutableList<File>, isCancelled: () -> Boolean) {
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty() && !isCancelled()) {
            val d = stack.removeLast()
            // Folders marked ".nomedia" inside app folders often hold app data (caches, stickers), but in
            // standard folders people keep real files there too, so they are still included.
            for (f in d.listFiles().orEmpty()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) stack.add(f) else if (f.length() > 0) into += f
            }
        }
    }

    /** Files already in the organized folder (used so new copies of them are recognised as duplicates). */
    fun alreadyOrganized(): List<File> {
        if (!outputRoot.isDirectory) return emptyList()
        return outputRoot.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && !isIndexFile(it.name) && !it.name.startsWith(".") }
            .toList()
    }

    fun relative(file: File): String = file.relativeTo(storageRoot).invariantSeparatorsPath

    /** Reads every file and works out where it should go. Nothing is moved yet. */
    fun plan(files: List<File>, progress: ProgressListener, isCancelled: () -> Boolean = { false }): Plan {
        // 1. Duplicates first: extra copies don't need reading, which saves time.
        val existing = if (options.findDuplicates) alreadyOrganized() else emptyList()
        val existingSet = existing.toHashSet()
        val groups = if (options.findDuplicates) {
            progress.onProgress(0, files.size, "Looking for duplicate copies…")
            Duplicates.findGroups(files + existing, isCancelled) { done, total ->
                progress.onProgress(done, total, "Looking for duplicate copies…")
            }
        } else emptyList()

        // For each group, keep one copy: one already organized, else the best-named, oldest one.
        val keeperOf = HashMap<File, File>()
        val reviewFolder = File(outputRoot, Placement.DUPLICATES_FOLDER).path + File.separator
        for (group in groups) {
            // Prefer the copy already filed in its proper place over copies waiting in the review folder.
            val keeper = group.filter { it in existingSet }.minByOrNull { if (it.path.startsWith(reviewFolder)) 1 else 0 }
                ?: group.sortedWith(
                    compareByDescending<File> { Names.isMeaningful(it.nameWithoutExtension) }
                        .thenBy { Names.stripCopySuffix(it.nameWithoutExtension).length != it.nameWithoutExtension.length }
                        .thenBy { it.lastModified() }
                        .thenBy { it.path.count { c -> c == '/' } },
                ).first()
            group.filter { it != keeper && it !in existingSet }.forEach { keeperOf[it] = keeper }
        }

        // 2. Read the files that will be kept.
        val toRead = files.filter { it !in keeperOf }
        val factsOf = HashMap<File, FileFacts>()
        toRead.forEachIndexed { i, file ->
            if (isCancelled()) return@forEachIndexed
            progress.onProgress(i, toRead.size, "Reading ${file.name}")
            factsOf[file] = analyze(file)
        }
        if (isCancelled()) return Plan(storageRoot, outputRoot, emptyList(), emptyList())

        // 3. Work out new homes and names.
        val taken = UniqueNames()
        existing.forEach { taken.reserve(it) }
        val moves = mutableListOf<PlannedMove>()
        val keptTarget = HashMap<File, File>()
        for (file in toRead) {
            val facts = factsOf.getValue(file)
            val folder = File(outputRoot, Placement.folderFor(facts))
            val newBase = Placement.newBaseName(facts, options)
            val ext = file.extension.let { if (it.isEmpty()) "" else "." + it.lowercase() }
            val target = taken.claim(folder, if (newBase != null) newBase + ext else file.name)
            moves += PlannedMove(facts, target, renamed = newBase != null)
            keptTarget[file] = target
        }

        // 4. Extra copies go to numbered review folders, named after the copy that is kept.
        val dupRoot = File(outputRoot, Placement.DUPLICATES_FOLDER)
        var groupNumber = dupRoot.listFiles()?.count { it.isDirectory } ?: 0
        val duplicateGroups = mutableListOf<DuplicateGroup>()
        for ((keeper, extras) in keeperOf.entries.groupBy({ it.value }, { it.key })) {
            val kept = keptTarget[keeper] ?: keeper
            groupNumber++
            val folder = File(dupRoot, Names.sanitize("%03d - %s".format(groupNumber, kept.nameWithoutExtension), 80))
            val extraMoves = extras.sortedBy { it.path }.map { extra ->
                val facts = FileFacts(extra, relative(extra), FileKind.of(extra, relative(extra)))
                facts.offerDate(Dates.fromFileName(extra.name), DateSource.FILE_NAME)
                PlannedMove(facts, taken.claim(folder, extra.name), renamed = false, duplicateOf = kept)
            }
            moves += extraMoves
            duplicateGroups += DuplicateGroup(folder, kept, extraMoves)
        }
        progress.onProgress(files.size, files.size, "Plan ready")
        return Plan(storageRoot, outputRoot, moves, duplicateGroups)
    }

    /** Learns everything it can about one file. */
    fun analyze(file: File): FileFacts {
        val rel = relative(file)
        val facts = FileFacts(file, rel, FileKind.of(file, rel))
        facts.offerDate(Dates.fromFileName(file.name), DateSource.FILE_NAME)
        for (a in listOf<FileAnalyzer>(DocumentReaders) + analyzers) {
            try {
                a.analyze(facts, options)
            } catch (e: Exception) {
                // One unreadable file must never stop the whole run.
            } catch (e: OutOfMemoryError) {
                System.gc()
            }
        }
        classifyTopic(facts)
        return facts
    }

    private fun classifyTopic(facts: FileFacts) {
        val text = facts.text ?: return
        when {
            facts.kind.isDocument -> Topics.classify(facts.title, text)?.let { m ->
                facts.topic = m.folder
                if (facts.docLabel == null) facts.docLabel = m.label
            }
            facts.kind == FileKind.PHOTO -> {
                // Only a photo with plenty of writing on it is treated as a scanned document.
                val words = Names.words(text).count { w -> w.count(Char::isLetter) >= 3 }
                if (words >= 20) Topics.classify(facts.title, text)?.takeIf { it.score >= 6 }?.let { m ->
                    facts.topic = m.folder
                    facts.docLabel = m.label ?: "Scanned document"
                }
            }
            facts.kind == FileKind.SCREENSHOT -> Topics.classify(facts.title, text)?.takeIf { it.score >= 6 }?.let { m ->
                if (facts.docLabel == null) facts.docLabel = m.label?.let { "Screenshot of ${it.lowercase()}" }
            }
            else -> {}
        }
    }
}

/** Hands out file names that don't clash with each other or with files already there: "Name (2).pdf". */
class UniqueNames {
    private val used = HashSet<String>()

    private fun key(f: File) = f.path.lowercase()

    fun reserve(f: File) {
        used += key(f)
    }

    fun claim(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = if ('.' in fileName) "." + fileName.substringAfterLast('.') else ""
        var candidate = File(folder, fileName)
        var n = 2
        while (key(candidate) in used || candidate.exists()) {
            candidate = File(folder, "$base ($n)$ext")
            n++
        }
        used += key(candidate)
        return candidate
    }
}
