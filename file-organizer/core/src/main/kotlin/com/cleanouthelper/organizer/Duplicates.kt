package com.cleanouthelper.organizer

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Finds files that are exact copies of each other (same bytes, whatever their names).
 * Only files of the same size are compared, first by a quick fingerprint of their start and
 * end, then by a full SHA-256 checksum, so this stays fast even on a full phone.
 */
object Duplicates {
    private const val QUICK_BYTES = 64 * 1024

    fun findGroups(
        files: List<File>,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<List<File>> {
        val bySize = files.filter { it.length() > 0 }.groupBy { it.length() }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val groups = mutableListOf<List<File>>()
        for (sameSize in bySize) {
            if (isCancelled()) break
            val byQuick = sameSize.groupBy { quickFingerprint(it) }.values.filter { it.size > 1 }
            for (candidates in byQuick) {
                val byFull = if (candidates[0].length() <= 2L * QUICK_BYTES) listOf(candidates)
                else candidates.groupBy { sha256(it) }.values.filter { it.size > 1 }
                groups += byFull
            }
            done += sameSize.size
            onProgress(done, total)
        }
        return groups
    }

    /** Hash of the first and last 64 KB plus the size. Whole file if it is small. */
    private fun quickFingerprint(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val buf = ByteArray(QUICK_BYTES)
            fun readAt(pos: Long) {
                raf.seek(pos)
                var total = 0
                while (total < buf.size) {
                    val n = raf.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                md.update(buf, 0, total)
            }
            readAt(0)
            if (len > QUICK_BYTES) readAt(maxOf(QUICK_BYTES.toLong(), len - QUICK_BYTES))
            md.update(len.toString().toByteArray())
        }
        md.digest().toHex()
    } catch (e: Exception) {
        "unreadable:" + file.path // never matches anything else
    }

    fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    } catch (e: Exception) {
        "unreadable:" + file.path
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
