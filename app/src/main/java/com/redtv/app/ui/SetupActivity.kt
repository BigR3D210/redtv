package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivitySetupBinding
import com.redtv.app.model.RemoteConfig
import com.redtv.app.model.Source
import com.redtv.app.model.SourceProfile
import kotlinx.coroutines.launch

/**
 * Guided setup wizard:
 *   step 0 Welcome -> 1 Choose type -> 2 Details -> 3 Test connection / Finish
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var b: ActivitySetupBinding
    private lateinit var prefs: Prefs

    private var choice = "url"          // "url" | "m3u" | "xtream"
    private var editingId: String? = null
    private var candidate: SourceProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.switchDedup.isChecked = prefs.hideDuplicates

        wireButtons()

        val isNew = intent.getBooleanExtra(EXTRA_NEW, false)
        val active = if (!isNew) prefs.activeProfile() else null
        if (active != null) {
            editingId = active.id
            prefillFrom(active)
            configureDetails()
            showStep(2)            // editing: jump straight to details, prefilled
        } else {
            showStep(0)
            b.btnStart.requestFocus()
        }
    }

    private fun wireButtons() {
        b.btnStart.setOnClickListener { showStep(1); b.btnChoiceUrl.requestFocus() }
        b.btnPhoneSetup.setOnClickListener { startActivity(android.content.Intent(this, EditOnLaptopActivity::class.java)) }
        b.btnBack1.setOnClickListener { showStep(0) }

        b.btnChoiceUrl.setOnClickListener { pickChoice("url") }
        b.btnChoiceM3u.setOnClickListener { pickChoice("m3u") }
        b.btnChoiceXtream.setOnClickListener { pickChoice("xtream") }

        b.btnBack2.setOnClickListener { showStep(1) }
        b.btnTest.setOnClickListener { startTest() }

        b.btnBack3.setOnClickListener { showStep(2) }
        b.btnFinish.setOnClickListener { commit() }
        b.btnSaveAnyway.setOnClickListener { commit() }
    }

    private fun pickChoice(c: String) {
        choice = c
        configureDetails()
        showStep(2)
        b.inputName.requestFocus()
    }

    private fun configureDetails() {
        b.inputConfigUrl.visibility = if (choice == "url") View.VISIBLE else View.GONE
        b.inputM3u.visibility = if (choice == "m3u") View.VISIBLE else View.GONE
        b.groupXtream.visibility = if (choice == "xtream") View.VISIBLE else View.GONE
        b.inputEpg.visibility = if (choice == "url") View.GONE else View.VISIBLE
        b.detailsHelp.text = when (choice) {
            "url" -> "Paste the link to your hosted config.json. Edit that file online anytime to update channels or login."
            "m3u" -> "Paste your M3U / M3U8 playlist link. EPG is optional."
            else -> "Enter the host, username and password from your provider. EPG is optional."
        }
    }

    private fun prefillFrom(p: SourceProfile) {
        b.inputName.setText(p.name)
        if (!p.configUrl.isNullOrBlank()) {
            choice = "url"
            b.inputConfigUrl.setText(p.configUrl)
        } else p.manual?.let { cfg ->
            choice = if (cfg.source.isXtream()) "xtream" else "m3u"
            b.inputM3u.setText(cfg.source.m3uUrl ?: "")
            b.inputHost.setText(cfg.source.host ?: "")
            b.inputUser.setText(cfg.source.username ?: "")
            b.inputPass.setText(cfg.source.password ?: "")
            b.inputEpg.setText(cfg.source.epgUrl ?: "")
        }
    }

    /** Build a candidate profile from the form, or null + toast if incomplete. */
    private fun buildCandidate(): SourceProfile? {
        val name = b.inputName.text.toString().trim()
            .ifBlank { "Source ${prefs.profiles().size + 1}" }
        val epg = b.inputEpg.text.toString().trim().ifBlank { null }
        val id = editingId ?: "p_${System.currentTimeMillis()}"

        return when (choice) {
            "url" -> {
                val url = b.inputConfigUrl.text.toString().trim()
                if (url.isBlank()) { toast("Paste your config URL."); null }
                else SourceProfile(id = id, name = name, configUrl = url)
            }
            "m3u" -> {
                val m3u = b.inputM3u.text.toString().trim()
                if (m3u.isBlank()) { toast("Enter your M3U URL."); null }
                else SourceProfile(id = id, name = name,
                    manual = RemoteConfig(appName = "Red TV",
                        source = Source(type = "m3u", m3uUrl = m3u, epgUrl = epg)))
            }
            else -> {
                val host = b.inputHost.text.toString().trim()
                val user = b.inputUser.text.toString().trim()
                val pass = b.inputPass.text.toString().trim()
                if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                    toast("Enter host, username and password."); null
                } else SourceProfile(id = id, name = name,
                    manual = RemoteConfig(appName = "Red TV",
                        source = Source(type = "xtream", host = host, username = user,
                            password = pass, epgUrl = epg)))
            }
        }
    }

    private fun startTest() {
        val cand = buildCandidate() ?: return
        candidate = cand
        showStep(3)
        b.testProgress.visibility = View.VISIBLE
        b.testStatus.text = "Testing connection…"
        b.btnFinish.visibility = View.GONE
        b.btnSaveAnyway.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val list = ContentRepository.previewChannels(cand)
                b.testProgress.visibility = View.GONE
                if (list.isEmpty()) {
                    b.testStatus.text = "Connected, but found 0 channels.\nDouble-check the source, or save anyway."
                    b.btnSaveAnyway.visibility = View.VISIBLE
                } else {
                    b.testStatus.text = "✓ Success! Found ${list.size} channels & movies."
                    b.btnFinish.visibility = View.VISIBLE
                    b.btnFinish.requestFocus()
                }
            } catch (e: Exception) {
                b.testProgress.visibility = View.GONE
                b.testStatus.text = "✗ Couldn't connect:\n${e.message ?: "unknown error"}"
                b.btnSaveAnyway.visibility = View.VISIBLE
                b.btnBack3.requestFocus()
            }
        }
    }

    private fun commit() {
        val cand = candidate ?: buildCandidate() ?: return
        prefs.hideDuplicates = b.switchDedup.isChecked
        prefs.upsertProfile(cand)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun showStep(i: Int) { b.flipper.displayedChild = i }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_NEW = "new"
    }
}
