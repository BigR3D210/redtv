package com.redtv.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Channel
import com.redtv.app.model.Overrides
import com.redtv.app.model.SourceProfile

/** Local persistence: sources/profiles, favorites, hidden, pinned, recents, resume points. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("redtv", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---------- Source profiles (multi-source) ----------

    fun profiles(): MutableList<SourceProfile> =
        sp.getString(KEY_PROFILES, null)?.let {
            runCatching { gson.fromJson<List<SourceProfile>>(it, profilesType) }.getOrNull()
        }?.toMutableList() ?: mutableListOf()

    private fun saveProfiles(list: List<SourceProfile>) {
        sp.edit().putString(KEY_PROFILES, gson.toJson(list)).apply()
    }

    var activeProfileId: String?
        get() = sp.getString(KEY_ACTIVE, null)
        set(v) = sp.edit().putString(KEY_ACTIVE, v).apply()

    fun activeProfile(): SourceProfile? {
        val list = profiles()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.id == activeProfileId } ?: list.first()
    }

    /** Add or update a profile (matched by id) and make it active. */
    fun upsertProfile(profile: SourceProfile) {
        val list = profiles()
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list[i] = profile else list.add(profile)
        saveProfiles(list)
        activeProfileId = profile.id
    }

    fun deleteProfile(id: String) {
        val list = profiles().filterNot { it.id == id }
        saveProfiles(list)
        if (activeProfileId == id) activeProfileId = list.firstOrNull()?.id
    }

    fun hasSource(): Boolean = profiles().isNotEmpty()

    // ---------- Display options ----------

    var hideDuplicates: Boolean
        get() = sp.getBoolean(KEY_DEDUP, false)
        set(v) = sp.edit().putBoolean(KEY_DEDUP, v).apply()

    // ---------- Hidden channels ----------

    fun hidden(): MutableSet<String> =
        HashSet(sp.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())

    fun isHidden(id: String) = hidden().contains(id)

    fun toggleHidden(id: String) {
        val set = hidden()
        if (set.contains(id)) set.remove(id) else set.add(id)
        sp.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun clearHidden() = sp.edit().remove(KEY_HIDDEN).apply()

    /** Used by the online editor: set hidden state for a known set of channel ids. */
    fun setHiddenForScope(scopeIds: Collection<String>, hiddenIds: Set<String>) {
        val set = hidden()
        for (id in scopeIds) { if (hiddenIds.contains(id)) set.add(id) else set.remove(id) }
        sp.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    // ---------- Pinned (reorder: pin to top) ----------

    fun pinned(): List<String> =
        sp.getString(KEY_PINNED, null)?.let {
            runCatching { gson.fromJson<List<String>>(it, listType) }.getOrNull()
        } ?: emptyList()

    fun isPinned(id: String) = pinned().contains(id)

    fun togglePinned(id: String) {
        val list = pinned().toMutableList()
        if (list.contains(id)) list.remove(id) else list.add(0, id)
        sp.edit().putString(KEY_PINNED, gson.toJson(list)).apply()
    }

    // ---------- Favorites ----------

    fun favorites(): MutableSet<String> =
        HashSet(sp.getStringSet(KEY_FAVS, emptySet()) ?: emptySet())

    fun isFavorite(id: String) = favorites().contains(id)

    fun toggleFavorite(id: String): Boolean {
        val set = favorites()
        val nowFav = if (set.contains(id)) { set.remove(id); false } else { set.add(id); true }
        sp.edit().putStringSet(KEY_FAVS, set).apply()
        return nowFav
    }

    /** Used by the online editor: set favorite state for a known set of channel ids. */
    fun setFavoritesForScope(scopeIds: Collection<String>, favIds: Set<String>) {
        val set = favorites()
        for (id in scopeIds) { if (favIds.contains(id)) set.add(id) else set.remove(id) }
        sp.edit().putStringSet(KEY_FAVS, set).apply()
    }

    // ---------- Recents ----------

    fun recents(): List<String> =
        sp.getString(KEY_RECENTS, null)?.let {
            runCatching { gson.fromJson<List<String>>(it, listType) }.getOrNull()
        } ?: emptyList()

    fun pushRecent(id: String) {
        val list = recents().toMutableList()
        list.remove(id)
        list.add(0, id)
        while (list.size > 30) list.removeAt(list.size - 1)
        sp.edit().putString(KEY_RECENTS, gson.toJson(list)).apply()
    }

    var lastChannelId: String?
        get() = sp.getString(KEY_LAST, null)
        set(v) = sp.edit().putString(KEY_LAST, v).apply()

    // ---------- Resume positions (movies / VOD) ----------

    fun resumePosition(id: String): Long = sp.getLong("$KEY_RESUME$id", 0L)

    fun setResumePosition(id: String, posMs: Long) {
        if (posMs > 10_000) sp.edit().putLong("$KEY_RESUME$id", posMs).apply()
        else sp.edit().remove("$KEY_RESUME$id").apply()
    }

    /** Ids of movies with a saved resume point (for the Continue Watching row). */
    fun continueIds(): List<String> {
        return sp.all.keys
            .filter { it.startsWith(KEY_RESUME) }
            .map { it.removePrefix(KEY_RESUME) }
    }

    // ---------- Offline cache of a fetched remote config ----------

    var cachedConfig: RemoteConfig?
        get() = sp.getString(KEY_CACHE, null)?.let {
            runCatching { gson.fromJson(it, RemoteConfig::class.java) }.getOrNull()
        }
        set(v) = sp.edit().putString(KEY_CACHE, v?.let { gson.toJson(it) }).apply()

    // ---------- Overrides (online editor: category/channel order, hide, rename) ----------

    fun overrides(): Overrides =
        sp.getString(KEY_OVR, null)?.let { runCatching { gson.fromJson(it, Overrides::class.java) }.getOrNull() }
            ?: Overrides()

    fun saveOverrides(o: Overrides) = sp.edit().putString(KEY_OVR, gson.toJson(o)).apply()

    private fun ovrKey(section: String, name: String) = "$section|$name"

    fun isCategoryHidden(section: String, name: String) =
        overrides().categoryHidden.contains(ovrKey(section, name))

    fun categoryDisplayName(section: String, name: String): String =
        overrides().categoryRename[ovrKey(section, name)] ?: name

    fun orderedCategories(section: String, raw: List<String>): List<String> {
        val order = overrides().categoryOrder[section] ?: return raw
        val rank = order.withIndex().associate { it.value to it.index }
        return raw.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it.lowercase() }))
    }

    fun orderedChannels(section: String, category: String, items: List<Channel>): List<Channel> {
        val order = overrides().channelOrder[ovrKey(section, category)] ?: return items
        val rank = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_DEDUP = "hide_duplicates"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_PINNED = "pinned"
        private const val KEY_CACHE = "cached_config"
        private const val KEY_FAVS = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val KEY_LAST = "last_channel"
        private const val KEY_RESUME = "resume_"
        private const val KEY_OVR = "overrides"
        private val listType = object : TypeToken<List<String>>() {}.type
        private val profilesType = object : TypeToken<List<SourceProfile>>() {}.type
    }
}
