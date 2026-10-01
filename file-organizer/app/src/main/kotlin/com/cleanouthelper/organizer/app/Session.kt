package com.cleanouthelper.organizer.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.cleanouthelper.organizer.ApplyResult
import com.cleanouthelper.organizer.OrganizeOptions
import com.cleanouthelper.organizer.Organizer
import com.cleanouthelper.organizer.Plan
import com.cleanouthelper.organizer.Planner
import com.cleanouthelper.organizer.ProgressListener
import com.cleanouthelper.organizer.UndoResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

class OrganizerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}

/**
 * The work in progress (scanning, organizing, undoing). It lives outside the screens so it carries
 * on when the phone is rotated or the app is in the background.
 */
object Session {
    enum class Stage { IDLE, SCANNING, PLANNED, APPLYING, DONE, UNDOING, UNDONE, FAILED }

    @Volatile var stage = Stage.IDLE
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    @Volatile var message = ""
        private set
    var plan: Plan? = null
        private set
    var applyResult: ApplyResult? = null
        private set
    var undoResult: UndoResult? = null
        private set
    var error: String? = null
        private set

    val isBusy get() = stage == Stage.SCANNING || stage == Stage.APPLYING || stage == Stage.UNDOING

    @Volatile private var cancelled = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastPost = 0L

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun changed(force: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPost < 150) return
        lastPost = now
        main.post { listeners.forEach { it() } }
    }

    private val progress = ProgressListener { d, t, m ->
        done = d
        total = t
        message = m
        changed(force = false)
    }

    @Suppress("DEPRECATION")
    fun storageRoot(): File = Environment.getExternalStorageDirectory()

    fun options(context: Context): OrganizeOptions = Settings(context).options()

    fun organizer(context: Context): Organizer {
        val app = context.applicationContext
        return Organizer(File(storageRoot(), options(app).outputFolderName)) { files -> updateMediaLibrary(app, files) }
    }

    fun cancel() {
        cancelled = true
    }

    fun reset() {
        if (isBusy) return
        stage = Stage.IDLE
        plan = null
        applyResult = null
        undoResult = null
        error = null
        changed()
    }

    fun startScan(context: Context, options: OrganizeOptions, folders: Set<String>, includeLooseFiles: Boolean) {
        runInBackground(context, Stage.SCANNING, "Finding your files…") { app ->
            val planner = Planner(storageRoot(), options, listOf(AndroidAnalyzer(app)))
            val files = planner.listFiles(folders, includeLooseFiles) { cancelled }
            progress.onProgress(0, files.size, "Found ${files.size} files")
            val p = planner.plan(files, progress) { cancelled }
            if (cancelled) Stage.IDLE else {
                plan = p
                Stage.PLANNED
            }
        }
    }

    fun applyPlan(context: Context) {
        val p = plan ?: return
        runInBackground(context, Stage.APPLYING, "Organizing…") { app ->
            applyResult = organizer(app).apply(p, progress) { cancelled }
            plan = null
            Stage.DONE
        }
    }

    fun undoLast(context: Context) {
        runInBackground(context, Stage.UNDOING, "Putting files back…") { app ->
            undoResult = organizer(app).undoLast(progress)
            Stage.UNDONE
        }
    }

    private fun runInBackground(context: Context, busyStage: Stage, firstMessage: String, work: (Context) -> Stage) {
        if (isBusy) return
        val app = context.applicationContext
        cancelled = false
        error = null
        stage = busyStage
        done = 0
        total = 0
        message = firstMessage
        changed()
        startService(app)
        thread(name = "organizer-work") {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileOrganizer:work").apply { acquire(6 * 60 * 60 * 1000L) }
            stage = try {
                work(app)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                Stage.FAILED
            } finally {
                if (lock.isHeld) lock.release()
            }
            changed()
        }
    }

    private fun startService(app: Context) {
        try {
            val intent = Intent(app, WorkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (_: Exception) {
            // Not fatal: the work still runs while the app is open.
        }
    }

    /** Tells the gallery, music and file apps which files moved. */
    private fun updateMediaLibrary(app: Context, files: List<File>) {
        files.map { it.path }.chunked(100).forEach { chunk ->
            try {
                MediaScannerConnection.scanFile(app, chunk.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
    }
}

/** Remembers the person's choices between visits. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun options() = OrganizeOptions(
        renameUnclearFiles = prefs.getBoolean("rename", true),
        readTextInPictures = prefs.getBoolean("ocr", true),
        describePhotos = prefs.getBoolean("describe", true),
        findDuplicates = prefs.getBoolean("duplicates", true),
    )

    fun save(o: OrganizeOptions) {
        prefs.edit()
            .putBoolean("rename", o.renameUnclearFiles)
            .putBoolean("ocr", o.readTextInPictures)
            .putBoolean("describe", o.describePhotos)
            .putBoolean("duplicates", o.findDuplicates)
            .apply()
    }

    var chosenFolders: Set<String>?
        get() = prefs.getStringSet("folders", null)
        set(v) = prefs.edit().putStringSet("folders", v).apply()

    var includeLooseFiles: Boolean
        get() = prefs.getBoolean("loose", true)
        set(v) = prefs.edit().putBoolean("loose", v).apply()
}
