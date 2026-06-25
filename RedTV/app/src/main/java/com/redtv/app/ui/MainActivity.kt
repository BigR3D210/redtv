package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private lateinit var channelAdapter: ChannelAdapter
    private var displayCategories: List<String> = emptyList()
    private var selectedCategory = 0

    private lateinit var lblContinue: String
    private lateinit var lblFavorites: String
    private lateinit var lblRecent: String
    private lateinit var lblAll: String
    private lateinit var lblHidden: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.hasSource()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        lblContinue = getString(R.string.continue_watching)
        lblFavorites = getString(R.string.favorites)
        lblRecent = getString(R.string.recent)
        lblAll = getString(R.string.all)
        lblHidden = getString(R.string.hidden)

        channelAdapter = ChannelAdapter(
            prefs = prefs,
            onClick = { ch, pos -> openPlayer(ch, pos) },
            onLongPress = { ch -> showContextMenu(ch) }
        )
        b.channelGrid.layoutManager = GridLayoutManager(this, 5)
        b.channelGrid.adapter = channelAdapter
        b.categoryList.layoutManager = LinearLayoutManager(this)

        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        b.btnSources.setOnClickListener { showSourcePicker() }

        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadContent()
    }

    override fun onResume() {
        super.onResume()
        if (ContentRepository.channels.isNotEmpty()) applyFilter()
    }

    private fun loadContent() {
        b.progress.visibility = View.VISIBLE
        b.emptyState.visibility = View.GONE
        lifecycleScope.launch {
            try {
                ContentRepository.reload(prefs)
                buildCategories()
                applyFilter()
                if (ContentRepository.channels.isEmpty()) showEmpty(getString(R.string.no_channels))
            } catch (e: Exception) {
                showEmpty("Couldn't load: ${e.message ?: "unknown error"}")
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun buildCategories() {
        val virtual = listOf(lblContinue, lblFavorites, lblRecent, lblAll)
        val tail = if (prefs.hidden().isNotEmpty()) listOf(lblHidden) else emptyList()
        displayCategories = virtual + ContentRepository.categories + tail
        selectedCategory = displayCategories.indexOf(lblAll).coerceAtLeast(0)
        val adapter = CategoryAdapter(displayCategories) { idx ->
            selectedCategory = idx
            applyFilter()
        }
        b.categoryList.adapter = adapter
    }

    /** All channels minus hidden, with optional de-duplication by name. */
    private fun visibleChannels(): List<Channel> {
        val hidden = prefs.hidden()
        var list = ContentRepository.channels.filterNot { hidden.contains(it.id) }
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
        val all = ContentRepository.channels
        if (all.isEmpty()) return
        val base = visibleChannels()
        val query = b.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val label = displayCategories.getOrNull(selectedCategory)

        val result: List<Channel> = when {
            query.isNotEmpty() -> base.filter { it.name.lowercase().contains(query) }
            label == lblContinue -> {
                val cont = prefs.continueIds()
                cont.mapNotNull { id -> all.firstOrNull { it.id == id } }
            }
            label == lblFavorites -> {
                val favs = prefs.favorites()
                base.filter { favs.contains(it.id) }
            }
            label == lblRecent -> {
                val order = prefs.recents()
                order.mapNotNull { id -> all.firstOrNull { it.id == id } }
            }
            label == lblHidden -> {
                val hidden = prefs.hidden()
                all.filter { hidden.contains(it.id) }
            }
            label == lblAll -> applyPins(base)
            else -> applyPins(base.filter { it.category == label })
        }

        channelAdapter.submit(result)
        if (result.isEmpty()) {
            showEmpty(
                when (label) {
                    lblFavorites -> "No favorites yet. Long-press OK on a channel to add one."
                    lblContinue -> "Nothing in progress yet."
                    else -> "Nothing here."
                }
            )
        } else {
            b.emptyState.visibility = View.GONE
        }
    }

    private fun showContextMenu(ch: Channel) {
        val fav = prefs.isFavorite(ch.id)
        val pinned = prefs.isPinned(ch.id)
        val hidden = prefs.isHidden(ch.id)
        val items = arrayOf(
            if (fav) "Remove from Favorites" else "Add to Favorites",
            if (pinned) "Unpin from top" else "Pin to top",
            if (hidden) "Unhide channel" else "Hide channel"
        )
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.toggleFavorite(ch.id)
                    1 -> prefs.togglePinned(ch.id)
                    2 -> { prefs.toggleHidden(ch.id); buildCategories() }
                }
                applyFilter()
            }
            .show()
    }

    private fun showSourcePicker() {
        val profiles = prefs.profiles()
        val names = profiles.map {
            (if (it.id == prefs.activeProfileId) "● " else "○ ") + it.name
        }.toMutableList()
        names.add("＋ " + getString(R.string.add_source))

        AlertDialog.Builder(this)
            .setTitle(R.string.sources)
            .setItems(names.toTypedArray()) { _, which ->
                if (which == profiles.size) {
                    startActivity(Intent(this, SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_NEW, true))
                } else {
                    val p = profiles[which]
                    if (p.id != prefs.activeProfileId) {
                        prefs.activeProfileId = p.id
                        loadContent()
                    }
                }
            }
            .show()
    }

    private fun showEmpty(msg: String) {
        b.emptyState.text = msg
        b.emptyState.visibility = View.VISIBLE
    }

    private fun openPlayer(ch: Channel, position: Int) {
        prefs.pushRecent(ch.id)
        prefs.lastChannelId = ch.id
        val ids = channelAdapter.currentIds()
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_IDS, ids)
                .putExtra(PlayerActivity.EXTRA_INDEX, position)
        )
    }
}
