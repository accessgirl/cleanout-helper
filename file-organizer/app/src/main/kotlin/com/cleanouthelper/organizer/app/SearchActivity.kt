package com.cleanouthelper.organizer.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cleanouthelper.organizer.CatalogEntry
import com.cleanouthelper.organizer.Organizer
import com.cleanouthelper.organizer.formatSize
import java.io.File
import java.util.concurrent.Executors

/**
 * Finds organized files by name, folder, the words inside them, what's in a photo, where it was
 * taken, or the old file name. Tap a result to open it.
 */
class SearchActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var organizer: Organizer
    private var results: List<CatalogEntry> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var searchNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        organizer = Session.organizer(this)

        val status = ui.note("Type a word: a shop, a person, a place, \"receipt\", \"2024-05\", or part of an old file name.", top = 8)
        val box = EditText(this).apply {
            hint = "Search my files"
            textSize = 17f
            setSingleLine()
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            requestFocus()
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = results.size
            override fun getItem(position: Int) = results[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: ui.column(0).apply {
                    setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                    addView(ui.label("", 16f, bold = true))
                    addView(ui.note("", top = 2))
                    addView(ui.label("", 14f, top = 2))
                    addView(ui.note("", top = 2))
                }
                val e = results[position]
                (row.getChildAt(0) as TextView).text = e.name
                (row.getChildAt(1) as TextView).text = "📁 " + e.folder.replace("/", " › ")
                (row.getChildAt(2) as TextView).apply {
                    text = listOf(e.date.substringBefore(' '), formatSize(e.size), e.description).filter { it.isNotBlank() }.joinToString(" · ")
                }
                (row.getChildAt(3) as TextView).apply {
                    text = if (e.preview.isNotBlank()) "\"${e.preview}\"" else ""
                    visibility = if (e.preview.isNotBlank()) View.VISIBLE else View.GONE
                }
                return row
            }
        }

        val list = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, position, _ -> open(results[position]) }
        }

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                val number = ++searchNumber
                main.removeCallbacksAndMessages(null)
                main.postDelayed({
                    worker.execute {
                        val found = if (query.isBlank()) emptyList() else organizer.search(query)
                        main.post {
                            if (number != searchNumber) return@post
                            results = found
                            adapter.notifyDataSetChanged()
                            status.text = when {
                                query.isBlank() -> ""
                                found.isEmpty() -> "Nothing found for \"$query\""
                                else -> "${found.size} found. Tap one to open it."
                            }
                        }
                    }
                }, 200)
            }
        })

        val root = ui.column(16, ui.title("Search my files"), box, status, list)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left + ui.dp(16), bars.top + ui.dp(16), bars.right + ui.dp(16), bars.bottom + ui.dp(8))
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun open(e: CatalogEntry) {
        val file = File(organizer.outputRoot, e.path)
        if (!file.exists()) {
            Toast.makeText(this, "That file has been moved or deleted", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, file.name))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this kind of file", Toast.LENGTH_LONG).show()
        }
    }
}
