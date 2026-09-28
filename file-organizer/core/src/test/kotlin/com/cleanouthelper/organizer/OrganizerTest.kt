package com.cleanouthelper.organizer

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Organizes a pretend phone from start to finish, then undoes it. */
class OrganizerTest {
    private lateinit var phone: File
    private val quiet = ProgressListener { _, _, _ -> }
    private val may2024 = java.time.LocalDateTime.of(2024, 5, 20, 9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @BeforeTest
    fun makePhone() {
        phone = Files.createTempDirectory("phone").toFile()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "camera photo".toByteArray()
        TestFiles.bytes(File(phone, "DCIM/Camera/IMG_20240512_143022.jpg"), jpeg)
        TestFiles.bytes(File(phone, "DCIM/Camera/Beach day with Sam.jpg"), jpeg + "2".toByteArray())
        TestFiles.bytes(File(phone, "Pictures/Screenshots/Screenshot_20240601-101010_Chrome.png"), "png".toByteArray())
        TestFiles.docx(
            File(phone, "Download/download (3).docx"),
            listOf("WALMART SUPERCENTER", "Store #1234", "May 12, 2024", "Milk 3.49", "Subtotal 12.00", "Total 13.00", "Thank you for your purchase"),
        )
        // The same receipt downloaded again, under another name: a duplicate.
        Files.copy(File(phone, "Download/download (3).docx").toPath(), File(phone, "Download/1715524222.docx").toPath())
        File(phone, "Documents").mkdirs()
        Files.copy(File(phone, "Download/download (3).docx").toPath(), File(phone, "Documents/receipt copy.docx").toPath())
        TestFiles.write(File(phone, "Documents/Car insurance 2024.txt"), "Policy number 12345. Premium due.")
        TestFiles.write(File(phone, "Documents/notes.txt"), "Packing list for Spain\n- passport\n- sun cream", may2024)
        TestFiles.xlsx(File(phone, "Download/export_20240301.xlsx"), listOf("Budget 2024", "Sheet2"), listOf("Month", "Rent", "Food"))
        TestFiles.pptx(File(phone, "Download/doc_99812345.pptx"), listOf("Quarterly Sales Review", "Numbers"))
        TestFiles.write(File(phone, "Download/contacts.vcf"), "BEGIN:VCARD\nFN:Jane Smith\nEND:VCARD\nBEGIN:VCARD\nFN:Bob Jones\nEND:VCARD\n")
        TestFiles.write(File(phone, "Download/invite.ics"), "BEGIN:VEVENT\nSUMMARY:Dentist appointment\nDTSTART:20240704T093000\nEND:VEVENT")
        TestFiles.bytes(File(phone, "Recordings/Voice 001.m4a"), "voice".toByteArray())
        TestFiles.bytes(File(phone, "Music/Favourite Song.mp3"), "song".toByteArray())
        TestFiles.bytes(File(phone, "Download/app-release.apk"), "apk".toByteArray())
        TestFiles.bytes(File(phone, "Download/data.bin"), "binary".toByteArray())
        TestFiles.bytes(File(phone, "WhatsApp/Media/IMG-20240101-WA0003.jpg"), "wa".toByteArray())
        TestFiles.bytes(File(phone, "Android/data/com.app/cache.jpg"), "cache".toByteArray())
        TestFiles.bytes(File(phone, "DCIM/.thumbnails/1.jpg"), "thumb".toByteArray())
        TestFiles.write(File(phone, "loose.txt"), "Shopping list\nEggs")
    }

    @AfterTest
    fun cleanUp() {
        phone.deleteRecursively()
    }

    private fun organized(path: String) = File(phone, "${OrganizeOptions.DEFAULT_OUTPUT_FOLDER}/$path")

    @Test
    fun `organizes, indexes, finds duplicates, searches and undoes`() {
        val options = OrganizeOptions()
        val planner = Planner(phone, options)
        val choices = planner.folderChoices().associateBy { it.name }
        assertTrue(choices.getValue("DCIM").selectedByDefault)
        assertFalse(choices.getValue("WhatsApp").selectedByDefault, "app folders are left out unless ticked")
        assertFalse("Android" in choices, "the Android system folder is never offered")

        val files = planner.listFiles(choices.values.filter { it.selectedByDefault }.map { it.name }.toSet(), includeLooseFiles = true)
        assertFalse(files.any { "WhatsApp" in it.path || "/Android/" in it.path || ".thumbnails" in it.path })

        val plan = planner.plan(files, quiet)
        val targets = plan.moves.associate { planner.relative(it.source) to it.target.relativeTo(planner.outputRoot).invariantSeparatorsPath }
        targets.forEach { (from, to) -> println("$from -> $to") }

        assertEquals("Photos/2024/05 - ${Dates.monthFolder(java.time.LocalDateTime.of(2024, 5, 1, 0, 0)).substringAfter("- ")}/2024-05-12 14.30 Photo.jpg", targets["DCIM/Camera/IMG_20240512_143022.jpg"])
        assertTrue(targets.getValue("DCIM/Camera/Beach day with Sam.jpg").endsWith("/Beach day with Sam.jpg"), "clear names are kept")
        assertEquals("Screenshots/2024/2024-06-01 10.10 Screenshot.png", targets["Pictures/Screenshots/Screenshot_20240601-101010_Chrome.png"])
        assertEquals("Documents/Bills & Receipts/Receipt - Walmart Supercenter (2024-05-12).docx", targets["Documents/receipt copy.docx"].let {
            // Whichever copy is kept, the kept one gets the clear name.
            targets.values.first { t -> t.startsWith("Documents/Bills & Receipts/") }
        })
        assertEquals("Documents/Insurance/Car insurance 2024.txt", targets["Documents/Car insurance 2024.txt"])
        assertEquals("Documents/Notes & Text Files/Packing list for Spain (2024-05-20).txt", targets["Documents/notes.txt"])
        assertEquals("Documents/Spreadsheets/Budget 2024 (2024-03-01).xlsx", targets["Download/export_20240301.xlsx"])
        assertEquals("Documents/Presentations/Quarterly Sales Review (${Names.day(Placement.bestDate(plan.moves.first { it.source.name == "doc_99812345.pptx" }.facts))}).pptx", targets["Download/doc_99812345.pptx"])
        assertTrue(targets.getValue("Download/contacts.vcf").startsWith("Contacts & Calendar/"))
        assertEquals("Contacts & Calendar/Event - Dentist appointment (2024-07-04).ics", targets["Download/invite.ics"])
        assertTrue(targets.getValue("Recordings/Voice 001.m4a").startsWith("Voice Recordings/"))
        assertEquals("Music/Favourite Song.mp3", targets["Music/Favourite Song.mp3"])
        assertEquals("App Installers/app-release.apk", targets["Download/app-release.apk"])
        assertEquals("Other Files/BIN files/data.bin", targets["Download/data.bin"])

        // Three identical receipts: one kept, two in the review folder.
        assertEquals(1, plan.duplicateGroups.size)
        assertEquals(2, plan.duplicateCount)
        val dupTargets = plan.moves.filter { it.isDuplicate }.map { it.target.relativeTo(planner.outputRoot).invariantSeparatorsPath }
        assertTrue(dupTargets.all { it.startsWith("Duplicates - Review/001 - Receipt - Walmart Supercenter") }, dupTargets.toString())
        val keptSource = plan.moves.first { it.target.path.contains("Bills & Receipts") }.source.name
        assertTrue(keptSource != "download (3).docx", "a numbered download copy isn't the one kept")

        // Apply it.
        val organizer = Organizer(planner.outputRoot)
        val result = organizer.apply(plan, quiet)
        assertEquals(plan.moves.size, result.moved)
        assertTrue(result.failed.isEmpty())
        assertTrue(organized("Documents/Bills & Receipts/Receipt - Walmart Supercenter (2024-05-12).docx").exists())
        assertFalse(File(phone, "Download/download (3).docx").exists())
        assertTrue(File(phone, "WhatsApp/Media/IMG-20240101-WA0003.jpg").exists(), "unticked folders are untouched")

        // Every folder has an index at the top, and the top folder has a master index.
        val folders = planner.outputRoot.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory && it != planner.outputRoot }.toList()
        for (dir in folders) {
            val index = dir.listFiles()!!.filter { it.name.startsWith(IndexWriter.INDEX_PREFIX) }
            assertEquals(1, index.size, "index in ${dir.path}")
            assertEquals(index[0].name, dir.listFiles()!!.map { it.name }.sorted().first(), "index sorts first in ${dir.name}")
        }
        val receiptsIndex = organized("Documents/Bills & Receipts/000 INDEX - Bills & Receipts.txt").readText()
        println(receiptsIndex)
        assertTrue("Receipt - Walmart Supercenter (2024-05-12).docx" in receiptsIndex)
        assertTrue("Inside: \"WALMART SUPERCENTER Store #1234" in receiptsIndex, receiptsIndex)
        assertTrue("Old name: " in receiptsIndex)
        val dupIndex = File(plan.duplicateGroups[0].folder, "000 INDEX - ${plan.duplicateGroups[0].folder.name}.txt").readText()
        println(dupIndex)
        assertTrue("Came from: Download" in dupIndex, dupIndex)
        assertTrue("exact copies of:\n  Documents/Bills & Receipts/Receipt - Walmart Supercenter (2024-05-12).docx" in dupIndex, dupIndex)
        val master = organized(IndexWriter.MASTER_NAME).readText()
        assertTrue("Budget 2024 (2024-03-01).xlsx" in master)

        // Search finds files by the words inside them and by their old names.
        assertEquals("Receipt - Walmart Supercenter (2024-05-12).docx", organizer.search("milk walmart").first().name)
        assertTrue(organizer.search("export_20240301").any { it.name.startsWith("Budget 2024") })
        assertTrue(organizer.search("spain passport").any { it.name.startsWith("Packing list") })

        // Running again finds nothing new to do, and a new copy of the receipt is caught as a duplicate.
        Files.copy(organized("Documents/Bills & Receipts/Receipt - Walmart Supercenter (2024-05-12).docx").toPath(), File(phone, "Download/receipt again.docx").toPath())
        val plan2 = planner.plan(planner.listFiles(setOf("Download", "DCIM", "Documents"), includeLooseFiles = false), quiet)
        assertEquals(1, plan2.moves.size)
        assertTrue(plan2.moves[0].isDuplicate)
        assertTrue(plan2.moves[0].target.path.contains("Duplicates - Review/002 - Receipt - Walmart"), plan2.moves[0].target.path)
        Organizer(planner.outputRoot).apply(plan2, quiet)

        // Undo the second run, then the first: everything goes back, the organized folder is emptied.
        val undo2 = Organizer(planner.outputRoot).undoLast(quiet)
        assertEquals(1, undo2.restored)
        assertTrue(File(phone, "Download/receipt again.docx").exists())
        val undo1 = Organizer(planner.outputRoot).undoLast(quiet)
        assertEquals(result.moved, undo1.restored)
        for (f in files) assertTrue(f.exists(), "put back: ${f.path}")
        val leftovers = planner.outputRoot.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && !Planner.isIndexFile(it.name) }.toList()
        assertTrue(leftovers.isEmpty(), leftovers.toString())
        assertNotNull(Organizer(planner.outputRoot).catalog)
    }

    @Test
    fun `dry run plan moves nothing and renaming can be switched off`() {
        val planner = Planner(phone, OrganizeOptions(renameUnclearFiles = false))
        val files = planner.listFiles(setOf("Download"), includeLooseFiles = false)
        val plan = planner.plan(files, quiet)
        assertEquals(0, plan.renamedCount)
        assertTrue(files.all { it.exists() })
        assertFalse(planner.outputRoot.exists())
    }
}
