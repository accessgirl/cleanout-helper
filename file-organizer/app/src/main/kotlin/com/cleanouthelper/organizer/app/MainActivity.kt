package com.cleanouthelper.organizer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cleanouthelper.organizer.Placement
import com.cleanouthelper.organizer.Plan
import com.cleanouthelper.organizer.PlannedMove
import com.cleanouthelper.organizer.Planner
import com.cleanouthelper.organizer.formatSize
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private var shownStage: Session.Stage? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var progressCount: TextView? = null
    private val listener: () -> Unit = { onSessionChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        root = FrameLayout(this)
        setContentView(root)
        // Keep content clear of the status bar and navigation bar (the app draws edge to edge on new phones).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        Session.addListener(listener)
        shownStage = null
        render()
    }

    override fun onPause() {
        Session.removeListener(listener)
        super.onPause()
    }

    private fun onSessionChanged() {
        if (Session.stage == shownStage && Session.isBusy) updateProgress() else render()
    }

    // ---------- permission ----------

    private fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun askForAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        shownStage = null
        render()
    }

    // ---------- screens ----------

    private fun render() {
        val stage = Session.stage
        shownStage = stage
        progressBar = null
        if (Session.isBusy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val content: View = when (stage) {
            Session.Stage.IDLE -> if (hasAccess()) setupScreen() else permissionScreen()
            Session.Stage.SCANNING, Session.Stage.APPLYING, Session.Stage.UNDOING -> progressScreen(stage)
            Session.Stage.PLANNED -> Session.plan?.let { planScreen(it) } ?: setupScreen()
            Session.Stage.DONE -> doneScreen()
            Session.Stage.UNDONE -> undoneScreen()
            Session.Stage.FAILED -> messageScreen("Something went wrong", Session.error ?: "Unknown problem")
        }
        root.removeAllViews()
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun scroll(vararg children: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(ui.column(16, *children))
    }

    private fun permissionScreen() = scroll(
        ui.title("File Organizer"),
        ui.note("Tidies up every file on your phone:", top = 8),
        ui.label(
            "• Sorts files into clear folders (Photos, Documents › Bills & Receipts, Videos, Music …)\n" +
                "• Gives files like IMG_2931.jpg or download (3).pdf a name that says what they are\n" +
                "• Gathers duplicate copies in one folder so you decide what to keep\n" +
                "• Puts an index at the top of every folder, so you can find things without opening them",
            top = 8,
        ),
        ui.card(
            ui.heading("First, allow access to your files"),
            ui.note(
                "To sort your files, the app needs the \"Allow access to manage all files\" permission. " +
                    "On the next screen, turn on the switch for File Organizer, then come back.\n\n" +
                    "Your files stay on your phone. Nothing is uploaded, and nothing is moved until you have seen the plan and said yes.",
            ),
            ui.button("Allow access") { askForAccess() },
        ),
    )

    private fun setupScreen(): View {
        val settings = Settings(this)
        var options = settings.options()
        val planner = Planner(Session.storageRoot(), options)
        val choices = planner.folderChoices()
        val saved = settings.chosenFolders
        val chosen = choices.filter { saved?.contains(it.name) ?: it.selectedByDefault }.map { it.name }.toMutableSet()
        var loose = settings.includeLooseFiles
        val organizer = Session.organizer(this)

        val folderChecks = ui.column(0)
        for (c in choices) {
            folderChecks.addView(ui.check(c.name, c.name in chosen, c.note?.let { "⚠ $it" }) { on -> if (on) chosen.add(c.name) else chosen.remove(c.name) })
        }
        folderChecks.addView(ui.check("Loose files at the top of your storage", loose) { loose = it })

        val findCard = if (planner.outputRoot.isDirectory) ui.card(
            ui.heading("Find a file"),
            ui.note("Search by name, by words inside the file, by what's in a photo, or by the old file name."),
            ui.button("Search my files") { startActivity(Intent(this, SearchActivity::class.java)) },
        ) else null

        val undoCard = organizer.lastRun()?.let {
            ui.card(
                ui.heading("Changed your mind?"),
                ui.note("Put every file from the last organize back where it was, with its old name."),
                ui.button("Undo the last organize", primary = false) { confirmUndo() },
                ui.button("Rebuild the index files", primary = false) {
                    thread {
                        organizer.refreshIndexes()
                        runOnUiThread { Toast.makeText(this, "Index files updated", Toast.LENGTH_SHORT).show() }
                    }
                },
            )
        }

        val views = listOfNotNull(
            ui.title("File Organizer"),
            ui.note("Everything goes into the \"${options.outputFolderName}\" folder on your phone. Nothing is moved until you've seen the plan."),
            findCard,
            ui.card(
                ui.heading("1. Which folders?"),
                ui.note("Ticked folders are sorted. Folders made by apps (WhatsApp, Telegram …) are left alone unless you tick them."),
                folderChecks,
            ),
            ui.card(
                ui.heading("2. What should it do?"),
                ui.check("Give unclear files clear names", options.renameUnclearFiles, "IMG_2931.jpg → 2024-05-12 14.30 Beach & Dog - Brighton.jpg") {
                    options = options.copy(renameUnclearFiles = it)
                },
                ui.check("Read the words in pictures and scanned PDFs", options.readTextInPictures, "Names screenshots and scans by what they say, and lets you search inside them. Slower.") {
                    options = options.copy(readTextInPictures = it)
                },
                ui.check("Describe photos and videos", options.describePhotos, "Uses what's in the picture and the town it was taken in. Slower.") {
                    options = options.copy(describePhotos = it)
                },
                ui.check("Find duplicate copies", options.findDuplicates, "Extra copies go to \"${Placement.DUPLICATES_FOLDER}\" for you to decide. Nothing is deleted.") {
                    options = options.copy(findDuplicates = it)
                },
            ),
            ui.button("Scan and show me the plan") {
                if (chosen.isEmpty() && !loose) {
                    Toast.makeText(this, "Tick at least one folder", Toast.LENGTH_SHORT).show()
                    return@button
                }
                settings.save(options)
                settings.chosenFolders = chosen
                settings.includeLooseFiles = loose
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                Session.startScan(this, options, chosen.toSet(), loose)
            },
            undoCard,
        )
        return scroll(*views.toTypedArray())
    }

    private fun progressScreen(stage: Session.Stage): View {
        val (title, explain) = when (stage) {
            Session.Stage.SCANNING -> "Looking through your files" to
                "Reading each file to work out what it is. Nothing is being moved yet. You can use your phone meanwhile; progress also shows in a notification."
            Session.Stage.APPLYING -> "Organizing" to "Moving and renaming files and writing the index files. Please keep the app installed until this finishes."
            else -> "Putting files back" to "Returning every file to where it was, with its old name."
        }
        val bar = ui.progressBar()
        val msg = ui.note("", top = 10)
        val count = ui.note("", top = 2)
        progressBar = bar
        progressText = msg
        progressCount = count
        val children = mutableListOf<View>(ui.title(title), ui.note(explain, top = 8), bar, msg, count)
        if (stage != Session.Stage.UNDOING) {
            children += ui.button("Stop", primary = false) {
                Session.cancel()
                Toast.makeText(this, if (stage == Session.Stage.APPLYING) "Stopping. Files already moved can be undone." else "Stopping…", Toast.LENGTH_LONG).show()
            }
        }
        updateProgress()
        return scroll(*children.toTypedArray())
    }

    private fun updateProgress() {
        val bar = progressBar ?: return
        val total = Session.total
        bar.isIndeterminate = total == 0
        if (total > 0) {
            bar.max = total
            bar.progress = Session.done
        }
        progressText?.text = Session.message
        progressCount?.text = if (total > 0) "${Session.done} of $total" else ""
    }

    private fun planScreen(plan: Plan): View {
        val storage = Session.storageRoot()
        var rows: List<PlannedMove> = plan.moves

        val summary = ui.card(
            ui.heading("The plan"),
            ui.label("${plan.moves.size} files will be sorted into \"${plan.outputRoot.name}\".", top = 6),
            ui.label(
                plan.countsByFolder().entries.joinToString("\n") { (folder, n) -> "📁 $folder: $n" },
                top = 8,
            ),
            ui.label("✏️ ${plan.renamedCount} files get clearer names", top = 8, color = ui.good),
            ui.label(
                if (plan.duplicateCount == 0) "No duplicate copies found"
                else "🗂 ${plan.duplicateCount} duplicate copies (${formatSize(plan.wastedBytes)}) go to \"${Placement.DUPLICATES_FOLDER}\" for you to review",
                top = 4,
                color = if (plan.duplicateCount == 0) ui.muted else ui.warn,
            ),
            ui.note("Every folder will get a \"000 INDEX\" file at the top listing what's in it. You can undo all of this afterwards.", top = 8),
        )

        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: ui.column(0).apply {
                    setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                    addView(ui.note("", top = 0))
                    addView(ui.label("", 14.5f, top = 2))
                }
                val m = rows[position]
                (row.getChildAt(0) as TextView).text = m.source.relativeTo(storage).invariantSeparatorsPath
                (row.getChildAt(1) as TextView).apply {
                    text = "→ " + m.target.relativeTo(plan.outputRoot).invariantSeparatorsPath
                    setTextColor(if (m.isDuplicate) ui.warn else if (m.renamed) ui.good else ui.text)
                }
                return row
            }
        }

        val tabs = ui.row()
        val tabNames = listOf("All ${plan.moves.size}", "Renamed ${plan.renamedCount}", "Duplicates ${plan.duplicateCount}")
        val tabViews = tabNames.map { name ->
            ui.label(name, 14f, bold = true).apply {
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
        }
        fun selectTab(i: Int) {
            rows = when (i) {
                1 -> plan.moves.filter { it.renamed }
                2 -> plan.moves.filter { it.isDuplicate }
                else -> plan.moves
            }
            tabViews.forEachIndexed { j, t -> t.setTextColor(if (j == i) ui.brand else ui.muted) }
            adapter.notifyDataSetChanged()
        }
        tabViews.forEachIndexed { i, t ->
            t.setOnClickListener { selectTab(i) }
            tabs.addView(t)
        }
        selectTab(0)

        val list = ListView(this).apply {
            divider = null
            addHeaderView(ui.column(16, summary, ui.label("Every change", 17f, bold = true, top = 16), tabs), null, false)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val buttons = ui.column(16).apply {
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12))
            addView(ui.button("Organize now") { Session.applyPlan(this@MainActivity) })
            addView(ui.button("Cancel", primary = false) { Session.reset() })
        }
        return ui.column(0, list, buttons)
    }

    private fun doneScreen(): View {
        val r = Session.applyResult
        val out = Session.options(this).outputFolderName
        val children = mutableListOf<View>(
            ui.title("All done ✅"),
            ui.label("${r?.moved ?: 0} files organized.", top = 8),
            ui.card(
                ui.heading("Where to find them"),
                ui.note(
                    "Open your phone's Files app and go to \"$out\". Each folder has a \"000 INDEX\" file at the top that lists " +
                        "every file in it, with what's inside and its old name. The \"000 MASTER INDEX\" in \"$out\" lists everything.\n\n" +
                        "Duplicate copies are in \"${Placement.DUPLICATES_FOLDER}\". Nothing was deleted: look through them and delete what you don't need.",
                ),
                ui.button("Search my files") { startActivity(Intent(this, SearchActivity::class.java)) },
            ),
        )
        r?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
            children += ui.card(
                ui.heading("${failed.size} files couldn't be moved"),
                ui.note(failed.take(30).joinToString("\n") { (f, why) -> "• ${f.name}: $why" } + if (failed.size > 30) "\n…" else ""),
            )
        }
        children += ui.button("Undo this", primary = false) { confirmUndo() }
        children += ui.button("Back to start", primary = false) { Session.reset() }
        return scroll(*children.toTypedArray())
    }

    private fun undoneScreen(): View {
        val r = Session.undoResult
        val children = mutableListOf<View>(
            ui.title("Files put back"),
            ui.label("${r?.restored ?: 0} files are back where they were, with their old names.", top = 8),
        )
        r?.failed?.takeIf { it.isNotEmpty() }?.let { failed ->
            children += ui.card(
                ui.heading("${failed.size} files couldn't be put back"),
                ui.note(failed.take(30).joinToString("\n") { (f, why) -> "• ${f.name}: $why" }),
            )
        }
        children += ui.button("Back to start") { Session.reset() }
        return scroll(*children.toTypedArray())
    }

    private fun messageScreen(title: String, message: String) = scroll(
        ui.title(title),
        ui.note(message, top = 8),
        ui.button("Back to start") { Session.reset() },
    )

    private fun confirmUndo() {
        AlertDialog.Builder(this)
            .setTitle("Undo the last organize?")
            .setMessage("Every file moved last time goes back to where it was, with its old name. Files you've changed or deleted since are skipped.")
            .setPositiveButton("Undo") { _, _ -> Session.undoLast(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
