package com.cleanouthelper.organizer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.cleanouthelper.organizer.DateSource
import com.cleanouthelper.organizer.FileAnalyzer
import com.cleanouthelper.organizer.FileFacts
import com.cleanouthelper.organizer.FileKind
import com.cleanouthelper.organizer.Names
import com.cleanouthelper.organizer.OrganizeOptions
import com.cleanouthelper.organizer.TextTitles
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads what only a phone can read: photo details (date taken, where), the words in pictures and
 * scanned PDFs, what a photo shows, PDF text, music tags, video details and app installer names.
 * Everything runs on the phone; nothing is uploaded.
 */
class AndroidAnalyzer(private val context: Context) : FileAnalyzer {
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.72f).build())
    }
    private val placeCache = HashMap<String, String?>()

    /** Labels that are true of almost any picture and don't help anyone find it. */
    private val boringLabels = setOf(
        "Font", "Rectangle", "Pattern", "Circle", "Material", "Metal", "Wood", "Paper", "Fun", "Smile", "Event", "Room",
        "Musical instrument", "Poster", "Screenshot", "Cool", "Moustache", "Sleeve", "Jersey", "Selfie", "Pier", "Vehicle",
        "Bag", "Shelf", "Cabinetry", "Jacket", "Denim", "Hand", "Nail", "Flesh", "Eyelash", "Skin", "Hair",
    )

    override fun analyze(facts: FileFacts, options: OrganizeOptions) {
        when (facts.kind) {
            FileKind.PHOTO, FileKind.SCREENSHOT -> image(facts, options)
            FileKind.VIDEO -> video(facts, options)
            FileKind.MUSIC, FileKind.VOICE_RECORDING -> audio(facts)
            FileKind.PDF -> pdf(facts, options)
            FileKind.APP_INSTALLER -> apk(facts)
            else -> {}
        }
    }

    // ---------- photos and screenshots ----------

    private fun image(facts: FileFacts, options: OrganizeOptions) {
        val path = facts.file.path
        var rotation = 0
        try {
            val exif = ExifInterface(path)
            val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            parseExifDate(taken)?.let { facts.offerDate(it, DateSource.CAPTURED) }
            exif.latLong?.let { (lat, lon) -> facts.place = placeName(lat, lon) }
            rotation = exif.rotationDegrees
        } catch (_: Exception) {
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth > 0) {
            facts.width = if (rotation % 180 == 0) bounds.outWidth else bounds.outHeight
            facts.height = if (rotation % 180 == 0) bounds.outHeight else bounds.outWidth
        }

        val wantText = options.readTextInPictures
        val wantLabels = options.describePhotos && facts.kind == FileKind.PHOTO
        if (!wantText && !wantLabels) return
        val bitmap = decodeScaled(path, bounds, 2400) ?: return
        try {
            val input = InputImage.fromBitmap(bitmap, rotation)
            if (wantText) {
                val text = readText(input)
                if (text.isNotBlank()) {
                    facts.text = text.take(6000)
                    val words = Names.words(text).count { w -> w.count(Char::isLetter) >= 3 }
                    // A photo only gets a title from its words when it's mostly writing (a letter, a
                    // receipt), not when it just has a shop sign in the background.
                    if (facts.kind == FileKind.SCREENSHOT && words >= 2 || words >= 20) {
                        facts.title = TextTitles.firstGoodLine(text, maxWords = 8)
                    }
                }
            }
            if (wantLabels) facts.tags = labels(input)
        } finally {
            bitmap.recycle()
        }
    }

    private fun readText(input: InputImage): String {
        val result = Tasks.await(textRecognizer.process(input), 30, TimeUnit.SECONDS)
        // Top to bottom, then left to right, so the title comes first.
        return result.textBlocks
            .sortedWith(compareBy({ (it.boundingBox?.top ?: 0) / 40 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n") { block -> block.lines.joinToString("\n") { it.text } }
    }

    private fun labels(input: InputImage): List<String> = try {
        Tasks.await(labeler.process(input), 30, TimeUnit.SECONDS)
            .sortedByDescending { it.confidence }
            .map { it.text }
            .filter { it !in boringLabels }
            .take(3)
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeScaled(path: String, bounds: BitmapFactory.Options, maxSide: Int): Bitmap? {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun parseExifDate(s: String?): LocalDateTime? = try {
        if (s.isNullOrBlank() || s.startsWith("0000")) null
        else LocalDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
    } catch (_: Exception) {
        null
    }

    /** The town or area for a spot on the map, using the phone's own lookup. Remembered, so it's asked once per area. */
    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String? {
        if (lat == 0.0 && lon == 0.0) return null
        val key = "%.2f,%.2f".format(Locale.ROOT, lat, lon)
        return placeCache.getOrPut(key) {
            try {
                if (!Geocoder.isPresent()) return@getOrPut null
                val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@getOrPut null
                (a.locality ?: a.subAdminArea ?: a.adminArea ?: a.countryName)?.let { Names.sanitize(it, 40) }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ---------- video and audio ----------

    private fun video(facts: FileFacts, options: OrganizeOptions) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(facts.file.path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { facts.durationSeconds = it / 1000 }
            parseMediaDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))?.let { facts.offerDate(it, DateSource.CAPTURED) }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { parseIso6709(it) }?.let { (lat, lon) ->
                facts.place = placeName(lat, lon)
            }
            facts.width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            facts.height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (options.describePhotos) {
                val frameAt = minOf(2_000_000L, (facts.durationSeconds ?: 0) * 500_000L)
                mmr.getFrameAtTime(frameAt, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val small = scaleDown(frame, 1024)
                    try {
                        facts.tags = labels(InputImage.fromBitmap(small, 0))
                    } finally {
                        if (small !== frame) small.recycle()
                        frame.recycle()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun audio(facts: FileFacts) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(facts.file.path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { facts.durationSeconds = it / 1000 }
            if (facts.kind == FileKind.MUSIC) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { facts.title = it.trim() }
                (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                    ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }?.let { facts.author = it.trim() }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { facts.album = it.trim() }
            }
            parseMediaDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))?.let { facts.offerDate(it, DateSource.CAPTURED) }
        } catch (_: Exception) {
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    /** "20240512T143022.000Z" (in UTC) → local date and time. */
    private fun parseMediaDate(s: String?): LocalDateTime? = try {
        if (s.isNullOrBlank() || s.startsWith("1904") || s.startsWith("1970")) null
        else {
            val utc = LocalDateTime.parse(s.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }
    } catch (_: Exception) {
        null
    }

    /** "+51.5074-000.1278/" → (51.5074, -0.1278) */
    private fun parseIso6709(s: String): Pair<Double, Double>? {
        val m = Regex("([+-][0-9.]+)([+-][0-9.]+)").find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= maxSide) return b
        val f = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    // ---------- PDFs ----------

    private fun pdf(facts: FileFacts, options: OrganizeOptions) {
        var text = ""
        try {
            PDDocument.load(facts.file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                facts.pages = doc.numberOfPages
                val info = doc.documentInformation
                info?.title?.takeIf { TextTitles.isUsefulTitle(it) }?.let { facts.title = Names.tidyCase(it.trim()) }
                info?.author?.takeIf { TextTitles.isPersonName(it) }?.let { facts.author = it.trim() }
                info?.creationDate?.let { cal ->
                    facts.offerDate(LocalDateTime.ofInstant(cal.toInstant(), ZoneId.systemDefault()), DateSource.CONTENT)
                }
                text = PDFTextStripper().apply {
                    startPage = 1
                    endPage = 3
                }.getText(doc)
            }
        } catch (e: Exception) {
            if (e.javaClass.simpleName.contains("Password")) facts.details += "password protected"
        } catch (_: OutOfMemoryError) {
        }
        // Scanned PDFs have no text, only pictures of pages: read the first page with OCR.
        if (text.isBlank() && options.readTextInPictures) text = ocrFirstPdfPage(facts.file)
        if (text.isNotBlank()) {
            facts.text = text.take(6000)
            if (facts.title == null) facts.title = TextTitles.firstGoodLine(text)
        }
        // A date written in the document beats the date the PDF file was made.
        com.cleanouthelper.organizer.Dates.fromText(text)?.let {
            if (facts.dateSource != DateSource.FILE_NAME) {
                facts.date = null
                facts.offerDate(it.atTime(12, 0), DateSource.CONTENT)
            }
        }
    }

    private fun ocrFirstPdfPage(file: File): String = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) "" else renderer.openPage(0).use { page ->
                    val scale = 1600f / maxOf(page.width, 1)
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    try {
                        readText(InputImage.fromBitmap(bmp, 0))
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }
    } catch (_: Exception) {
        ""
    } catch (_: OutOfMemoryError) {
        ""
    }

    // ---------- app installers ----------

    @Suppress("DEPRECATION")
    private fun apk(facts: FileFacts) {
        try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(facts.file.path, 0) ?: return
            val app = info.applicationInfo ?: return
            app.sourceDir = facts.file.path
            app.publicSourceDir = facts.file.path
            val label = pm.getApplicationLabel(app).toString()
            facts.title = listOfNotNull(label, info.versionName).joinToString(" ")
            facts.details += "app: ${info.packageName}"
        } catch (_: Exception) {
        }
    }
}
