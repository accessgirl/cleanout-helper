package com.cleanouthelper.organizer

import java.io.File

/**
 * Runs the organizer on a computer, e.g. on a phone's storage copied to a PC or plugged in over USB.
 *
 *   organize <folder> [--dry-run] [--all-folders] [--no-rename] [--no-duplicates]
 *   organize <folder> --undo
 *   organize <folder> --search "words"
 */
fun main(args: Array<String>) {
    val root = args.firstOrNull { !it.startsWith("--") }?.let { File(it) }
    if (root == null || !root.isDirectory) {
        println("Usage: organize <folder> [--dry-run] [--all-folders] [--no-rename] [--no-duplicates] [--undo] [--search words]")
        return
    }
    val flags = args.filter { it.startsWith("--") }.toSet()
    val options = OrganizeOptions(
        renameUnclearFiles = "--no-rename" !in flags,
        findDuplicates = "--no-duplicates" !in flags,
    )
    val planner = Planner(root, options)
    val organizer = Organizer(planner.outputRoot)
    val progress = ProgressListener { done, total, message ->
        if (total > 0 && (done % 250 == 0 || done == total)) println("  [$done/$total] $message")
    }

    if ("--undo" in flags) {
        val r = organizer.undoLast(progress)
        println("Put back ${r.restored} files.")
        r.failed.forEach { (f, why) -> println("  couldn't put back ${f.name}: $why") }
        return
    }
    if ("--search" in flags) {
        val query = args.dropWhile { it != "--search" }.drop(1).joinToString(" ")
        organizer.search(query).forEach { println("${it.path}\n    ${it.description}  ${it.preview.take(100)}") }
        return
    }

    val choices = planner.folderChoices()
    val chosen = choices.filter { it.selectedByDefault || "--all-folders" in flags }.map { it.name }.toSet()
    println("Folders: " + choices.joinToString { (if (it.name in chosen) "[x] " else "[ ] ") + it.name })
    val files = planner.listFiles(chosen, includeLooseFiles = true)
    println("Found ${files.size} files.")
    val plan = planner.plan(files, progress)
    println()
    println("PLAN: ${plan.moves.size} files → ${plan.outputRoot}")
    plan.countsByFolder().forEach { (folder, n) -> println("  $folder: $n") }
    println("  ${plan.renamedCount} get clearer names, ${plan.duplicateCount} duplicate copies (${formatSize(plan.wastedBytes)}) go to review.")
    println()
    for (m in plan.moves) {
        println("  ${planner.relative(m.source)}\n     → ${m.target.relativeTo(root).invariantSeparatorsPath}")
    }
    if ("--dry-run" in flags) {
        println("\nDry run: nothing was moved.")
        return
    }
    val result = organizer.apply(plan, progress)
    println("\nMoved ${result.moved} files. Undo with: organize ${root.path} --undo")
    result.failed.forEach { (f, why) -> println("  couldn't move ${f.name}: $why") }
}
