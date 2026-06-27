package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityMainBinding
import com.redtv.app.model.Channel
import com.redtv.app.model.Section
import com.redtv.app.net.Http
import com.redtv.app.net.Updater
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var listAdapter: GuideAdapter

    private val sectionKeys = listOf(
        Section.LIVE, Section.MOVIES, Section.SERIES, Section.SPORTS, "fav", "recent", "continue"
    )
    private val sectionLabels = listOf(
        "Live TV", "Movies", "Series", "Sports/PPV", "Favorites", "Recent", "Continue Watching"
    )
    private var currentSection = Section.LIVE
    // index 0 = "All" (null); others are raw category names
    private var categoriesRaw: List<String?> = emptyList()
    private var selectedCategory = 0
    private var displayed: List<Channel> = emptyList()

    // ---- Live preview (right pane) ----
    private var previewPlayer: ExoPlayer? = null
    private var pending: Channel? = null
    private var lastFocused: Channel? = null
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val previewRunnable = Runnable { pending?.let { startPreview(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.hasSource()) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        listAdapter = GuideAdapter(
            prefs,
            onFocused = { _, ch -> onFocusItem(ch) },
            onSelected = { ch -> openItem(ch) },
            onLongPress = { ch -> showContextMenu(ch) }
        )
        b.channelList.layoutManager = LinearLayoutManager(this)
        b.channelList.adapter = listAdapter
        b.channelList.setHasFixedSize(true)
        b.channelList.setItemViewCacheSize(20)
        b.channelList.itemAnimator = null
        b.categoryList.layoutManager = LinearLayoutManager(this)
        b.sectionList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.sectionList.adapter = CategoryAdapter(sectionLabels, R.layout.item_section) { idx -> onSection(idx) }

        b.btnSettings.setOnClickListener { showSettingsMenu() }
        b.btnSources.setOnClickListener { showSourcePicker() }
        b.btnSort.setOnClickListener { showSortMenu() }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        loadContent()
        checkForUpdate(silent = true)
    }

    override fun onStart() {
        super.onStart()
        initPreview()
    }

    override fun onResume() {
        super.onResume()
        if (ContentRepository.dirty) {
            ContentRepository.dirty = false
            loadContent()
        } else if (ContentRepository.hasAny()) onSection(currentSectionIndex())
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(previewRunnable)
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun currentSectionIndex() = sectionKeys.indexOf(currentSection).coerceAtLeast(0)

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
        if (isVirtual) {
            categoriesRaw = listOf(null)
            b.categoryList.adapter = CategoryAdapter(listOf("All")) { i -> selectedCategory = i; applyFilter() }
        } else {
            val raw = ContentRepository.categoriesFor(currentSection)
                .filterNot { prefs.isCategoryHidden(currentSection, it) }
            val ordered = prefs.orderedCategories(currentSection, raw)
            categoriesRaw = listOf<String?>(null) + ordered
            val display = listOf("All") + ordered.map { prefs.categoryDisplayName(currentSection, it) }
            b.categoryList.adapter = CategoryAdapter(display) { i -> selectedCategory = i; applyFilter() }
        }
        selectedCategory = 0
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
        val cat = categoriesRaw.getOrNull(selectedCategory)
        if (query.isNotEmpty()) {
            result = result.filter { it.name.lowercase().contains(query) }
        } else if (cat != null) {
            result = prefs.orderedChannels(currentSection, cat, result.filter { it.category == cat })
        } else {
            result = applyPins(result)
        }
        result = applySort(result)
        displayed = result
        listAdapter.submit(result)
        resetPreview()
        if (result.isEmpty()) showEmpty(emptyMessage()) else b.emptyState.visibility = View.GONE
    }

    // ---- Preview pane ----

    private fun initPreview() {
        if (previewPlayer != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                b.previewBuffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
        b.preview.player = p
        previewPlayer = p
    }

    private fun onFocusItem(ch: Channel) {
        lastFocused = ch
        updatePreviewInfo(ch)
        if (ch.id.startsWith("live_")) lifecycleScope.launch {
            ContentRepository.ensureShortEpg(ch)
            if (lastFocused?.id == ch.id) updatePreviewInfo(ch)
        }
        handler.removeCallbacks(previewRunnable)
        if (ch.id.startsWith("series_") || ch.streamUrl.isBlank()) {
            // A series folder has no single stream — show info, no video.
            previewPlayer?.stop()
            b.previewIdle.text = "Press OK to see episodes"
            b.previewIdle.visibility = View.VISIBLE
            b.previewBuffering.visibility = View.GONE
        } else {
            pending = ch
            handler.postDelayed(previewRunnable, 900)
        }
    }

    private fun updatePreviewInfo(ch: Channel) {
        b.previewName.text = ch.name
        val (now, next) = ContentRepository.nowNextForChannel(ch)
        b.previewNow.text = when {
            now != null -> "Now: ${now.title}"
            ch.number != null -> "Channel ${ch.number}"
            else -> ch.category
        }
        if (next != null) {
            b.previewNext.visibility = View.VISIBLE
            b.previewNext.text = "Next: ${next.title}  (${timeFmt.format(Date(next.startMillis))})"
        } else b.previewNext.visibility = View.GONE
    }

    private fun startPreview(ch: Channel) {
        val p = previewPlayer ?: return
        b.previewIdle.visibility = View.GONE
        p.setMediaItem(MediaItem.fromUri(ch.streamUrl))
        p.prepare()
        p.playWhenReady = true
    }

    private fun resetPreview() {
        handler.removeCallbacks(previewRunnable)
        previewPlayer?.stop()
        b.previewBuffering.visibility = View.GONE
        b.previewIdle.text = "Pause on a channel to preview"
        b.previewIdle.visibility = View.VISIBLE
        b.previewName.text = ""
        b.previewNow.text = ""
        b.previewNext.visibility = View.GONE
    }

    /** Explain WHY a section is empty: provider error, all-hidden, or no search matches. */
    private fun emptyMessage(): String {
        val searching = !b.search.text?.toString().isNullOrBlank()
        if (searching) return "No matches for your search."
        when (currentSection) {
            "fav" -> return "No favorites yet. Long-press OK on a tile to add one."
            "recent" -> return "Nothing watched yet."
            "continue" -> return "Nothing in progress yet."
        }
        val err = ContentRepository.sectionError(currentSection)
        val repoCount = ContentRepository.sectionItems(currentSection).size
        return when {
            err != null && repoCount == 0 -> "Couldn't load this section from your provider:\n$err"
            repoCount == 0 -> "Your provider returned nothing for this section."
            else -> "All $repoCount items here are hidden.\nOpen Settings, Edit from laptop and use 'Show all' to bring them back."
        }
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

    private fun showSortMenu() {
        val labels = arrayOf("Default", "Name A-Z", "Name Z-A", "Newest added", "Oldest added", "Top rated")
        val modes = arrayOf("default", "az", "za", "newest", "oldest", "rating")
        val checked = modes.indexOf(prefs.sortMode(currentSection)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort " + sectionLabels[currentSectionIndex()])
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.setSortMode(currentSection, modes[which])
                applyFilter()
                d.dismiss()
            }
            .show()
    }

    private fun applySort(list: List<Channel>): List<Channel> = when (prefs.sortMode(currentSection)) {
        "az" -> list.sortedBy { it.name.lowercase() }
        "za" -> list.sortedByDescending { it.name.lowercase() }
        "newest" -> list.sortedByDescending { it.added }
        "oldest" -> list.sortedBy { it.added }
        "rating" -> list.sortedByDescending { it.rating }
        else -> list
    }

    private fun showSettingsMenu() {
        val items = arrayOf("Edit source on TV", "Edit from laptop (pairing)", "Cast to TV", "Check for updates")
        AlertDialog.Builder(this).setTitle(R.string.settings).setItems(items) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, SetupActivity::class.java))
                1 -> startActivity(Intent(this, EditOnLaptopActivity::class.java))
                2 -> startActivity(Intent(this, CastActivity::class.java))
                3 -> checkForUpdate(silent = false)
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
