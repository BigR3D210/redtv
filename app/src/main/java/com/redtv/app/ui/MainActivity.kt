package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityMainBinding
import com.redtv.app.model.Channel
import com.redtv.app.model.Section
import com.redtv.app.net.Updater
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var channelAdapter: ChannelAdapter

    private val sectionKeys = listOf(
        Section.LIVE, Section.MOVIES, Section.SERIES, Section.SPORTS, "fav", "recent", "continue"
    )
    private val sectionLabels = listOf(
        "Live TV", "Movies", "Series", "Sports/PPV", "Favorites", "Recent", "Continue"
    )
    private var currentSection = Section.LIVE
    private var categories: List<String> = emptyList()
    private var selectedCategory = 0
    private var displayed: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.hasSource()) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        channelAdapter = ChannelAdapter(prefs, { ch, _ -> openItem(ch) }, { ch -> showContextMenu(ch) })
        b.channelGrid.layoutManager = GridLayoutManager(this, 5)
        b.channelGrid.adapter = channelAdapter
        b.channelGrid.setHasFixedSize(true)
        b.channelGrid.setItemViewCacheSize(24)
        b.categoryList.layoutManager = LinearLayoutManager(this)
        b.sectionList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.sectionList.adapter = CategoryAdapter(sectionLabels, R.layout.item_section) { idx -> onSection(idx) }

        b.btnSettings.setOnClickListener { showSettingsMenu() }
        b.btnSources.setOnClickListener { showSourcePicker() }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        loadContent()
        checkForUpdate(silent = true)
    }

    override fun onResume() {
        super.onResume()
        if (ContentRepository.dirty) {
            ContentRepository.dirty = false
            loadContent()
        } else if (ContentRepository.hasAny()) applyFilter()
    }

    private fun loadContent() {
        b.progress.visibility = View.VISIBLE
        b.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            try {
                ContentRepository.reload(prefs)
                onSection(0)
                if (!ContentRepository.hasAny()) showEmpty(getString(R.string.no_channels))
            } catch (e: Exception) {
                showEmpty("Couldn't load: ${e.message ?: "unknown error"}")
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun onSection(idx: Int) {
        currentSection = sectionKeys[idx]
        val isVirtual = currentSection in listOf("fav", "recent", "continue")
        categories = if (isVirtual) listOf("All")
        else listOf("All") + ContentRepository.categoriesFor(currentSection)
        selectedCategory = 0
        b.categoryList.adapter = CategoryAdapter(categories) { i -> selectedCategory = i; applyFilter() }
        applyFilter()
    }

    private fun combinedAll(): List<Channel> =
        ContentRepository.live + ContentRepository.movies +
            ContentRepository.sports + ContentRepository.series

    private fun base(): List<Channel> {
        val hidden = prefs.hidden()
        var list = when (currentSection) {
            "fav" -> { val f = prefs.favorites(); combinedAll().filter { f.contains(it.id) } }
            "recent" -> { val all = combinedAll(); prefs.recents().mapNotNull { id -> all.firstOrNull { it.id == id } } }
            "continue" -> { val all = combinedAll(); prefs.continueIds().mapNotNull { id -> all.firstOrNull { it.id == id } } }
            else -> ContentRepository.sectionItems(currentSection)
        }.filterNot { hidden.contains(it.id) }
        if (prefs.hideDuplicates) {
            val seen = HashSet<String>()
            list = list.filter { seen.add(it.name.lowercase()) }
        }
        return list
    }

    private fun applyPins(list: List<Channel>): List<Channel> {
        val pins = prefs.pinned()
        if (pins.isEmpty()) return list
        val rank = pins.withIndex().associate { it.value to it.index }
        return list.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    private fun applyFilter() {
        if (!ContentRepository.hasAny()) return
        val query = b.search.text?.toString()?.trim()?.lowercase().orEmpty()
        var result = base()
        if (query.isNotEmpty()) {
            result = result.filter { it.name.lowercase().contains(query) }
        } else {
            val cat = categories.getOrNull(selectedCategory)
            if (cat != null && cat != "All") result = result.filter { it.category == cat }
        }
        result = applyPins(result)
        displayed = result
        channelAdapter.submit(result)
        if (result.isEmpty()) showEmpty(
            when (currentSection) {
                "fav" -> "No favorites yet. Long-press OK on a tile to add one."
                "continue" -> "Nothing in progress yet."
                else -> "Nothing here."
            }
        ) else b.emptyState.visibility = View.GONE
    }

    private fun openItem(ch: Channel) {
        if (ch.id.startsWith("series_")) {
            startActivity(Intent(this, SeriesActivity::class.java)
                .putExtra(SeriesActivity.EXTRA_SERIES_ID, ch.id.removePrefix("series_"))
                .putExtra(SeriesActivity.EXTRA_TITLE, ch.name))
            return
        }
        prefs.pushRecent(ch.id)
        val playable = displayed.filter { !it.id.startsWith("series_") }
        val ids = ArrayList(playable.map { it.id })
        val idx = playable.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_IDS, ids)
            .putExtra(PlayerActivity.EXTRA_INDEX, idx))
    }

    private fun showContextMenu(ch: Channel) {
        val items = arrayOf(
            if (prefs.isFavorite(ch.id)) "Remove from Favorites" else "Add to Favorites",
            if (prefs.isPinned(ch.id)) "Unpin from top" else "Pin to top",
            if (prefs.isHidden(ch.id)) "Unhide" else "Hide"
        )
        AlertDialog.Builder(this).setTitle(ch.name).setItems(items) { _, which ->
            when (which) {
                0 -> prefs.toggleFavorite(ch.id)
                1 -> prefs.togglePinned(ch.id)
                2 -> prefs.toggleHidden(ch.id)
            }
            applyFilter()
        }.show()
    }

    private fun showSettingsMenu() {
        val items = arrayOf("Edit source on TV", "Edit from laptop (pairing)", "Check for updates")
        AlertDialog.Builder(this).setTitle(R.string.settings).setItems(items) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, SetupActivity::class.java))
                1 -> startActivity(Intent(this, EditOnLaptopActivity::class.java))
                2 -> checkForUpdate(silent = false)
            }
        }.show()
    }

    private fun checkForUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val info = Updater.check()
            if (info != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage("A newer version (${info.label}) is ready. Install now?")
                    .setPositiveButton("Update") { _, _ -> doUpdate(info) }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (!silent) {
                Toast.makeText(this@MainActivity, "You're on the latest version.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doUpdate(info: Updater.Info) {
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                Updater.downloadAndInstall(this@MainActivity, info.apkUrl)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSourcePicker() {
        val profiles = prefs.profiles()
        val names = profiles.map { (if (it.id == prefs.activeProfileId) "● " else "○ ") + it.name }.toMutableList()
        names.add("＋ " + getString(R.string.add_source))
        AlertDialog.Builder(this).setTitle(R.string.sources).setItems(names.toTypedArray()) { _, which ->
            if (which == profiles.size) {
                startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_NEW, true))
            } else if (profiles[which].id != prefs.activeProfileId) {
                prefs.activeProfileId = profiles[which].id
                loadContent()
            }
        }.show()
    }

    private fun showEmpty(msg: String) {
        b.emptyState.text = msg
        b.emptyState.visibility = View.VISIBLE
    }
}
