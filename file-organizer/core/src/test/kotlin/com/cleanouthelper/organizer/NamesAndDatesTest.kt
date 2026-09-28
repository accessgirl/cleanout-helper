package com.cleanouthelper.organizer

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesAndDatesTest {
    @Test
    fun `unclear names are recognised`() {
        listOf(
            "IMG_20240512_143022", "IMG-20240101-WA0003", "PXL_20231224_180501123", "DSC01234", "Screenshot_20240601-101010_Chrome",
            "download (3)", "document", "1693847223", "doc_1234567", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "Scan 2024-05-12",
            "untitled", "file", "VID_20240101_120000", "PTT-20240312-WA0002", "Recording 42", "a8f5f167f44f4964e6c998dee827110c",
            "CamScanner 05-12-2024 14.30", "image (1)", "Voice 001", "20240512_143022", "New Document 3", "P1010023",
        ).forEach { assertFalse(Names.isMeaningful(it), "should be unclear: $it") }
    }

    @Test
    fun `clear names are kept`() {
        listOf(
            "Car insurance 2024", "Tax return 2023", "Resume", "Jane Smith CV", "wedding-photos", "Mortgage statement May",
            "packing list for Spain", "BirthdayParty", "Electric bill (2)", "Recipe - lasagne",
        ).forEach { assertTrue(Names.isMeaningful(it), "should be clear: $it") }
    }

    @Test
    fun `sanitize makes names safe`() {
        assertEquals("Invoice 12 34 Acme", Names.sanitize("Invoice 12/34: Acme?"))
        assertTrue(Names.sanitize("word ".repeat(40)).length <= 90)
        assertEquals("Walmart Supercenter", Names.tidyCase("WALMART SUPERCENTER"))
        assertEquals("iPhone notes", Names.tidyCase("iPhone notes"))
    }

    @Test
    fun `dates are read from file names`() {
        assertEquals(LocalDateTime.of(2024, 5, 12, 14, 30, 22), Dates.fromFileName("IMG_20240512_143022.jpg"))
        assertEquals(LocalDateTime.of(2024, 6, 1, 10, 10, 10), Dates.fromFileName("Screenshot_2024-06-01-10-10-10-123_com.android.chrome.png"))
        assertEquals(LocalDate.of(2024, 1, 1), Dates.fromFileName("IMG-20240101-WA0003.jpg")?.toLocalDate())
        assertNull(Dates.fromFileName("holiday.jpg"))
        assertNull(Dates.fromFileName("IMG_99999999.jpg"))
    }

    @Test
    fun `dates are read from text`() {
        assertEquals(LocalDate.of(2024, 5, 12), Dates.fromText("Statement date: May 12, 2024\nTotal"))
        assertEquals(LocalDate.of(2024, 5, 12), Dates.fromText("Issued 12th May 2024"))
        assertEquals(LocalDate.of(2024, 5, 12), Dates.fromText("Date 05/12/2024", Locale.US))
        assertEquals(LocalDate.of(2024, 12, 5), Dates.fromText("Date 05/12/2024", Locale.UK))
        assertEquals(LocalDate.of(2024, 5, 13), Dates.fromText("Date 13/05/2024", Locale.US))
    }

    @Test
    fun `topics are recognised`() {
        assertEquals("Bills & Receipts", Topics.classify(null, "WALMART\nSubtotal 12.00\nTax 1.00\nTotal 13.00\nThank you for your purchase")?.folder)
        assertEquals("Medical & Health", Topics.classify("Lab results", "Patient: Jane Doe\nPhysician: Dr Smith")?.folder)
        assertEquals("Travel & Tickets", Topics.classify(null, "BOARDING PASS\nPassenger SMITH/JANE\nFlight BA123 Gate 22 Seat 14C")?.folder)
        assertEquals("Boarding pass", Topics.classify(null, "BOARDING PASS\nPassenger SMITH/JANE\nFlight BA123 Gate 22 Seat 14C")?.label)
        assertEquals("Work & Career", Topics.classify("Jane Smith Resume", "Work experience\nReferences available")?.folder)
        assertNull(Topics.classify(null, "Had a lovely day at the park with the kids."))
    }

    @Test
    fun `first good line skips junk`() {
        assertEquals("Walmart Supercenter", TextTitles.firstGoodLine("Page 1 of 2\n\n05/12/2024\nWALMART SUPERCENTER\nStore #1234"))
        assertEquals("Program for the Spring Concert", TextTitles.firstGoodLine("www.school.org\nProgram for the Spring Concert"))
    }
}
