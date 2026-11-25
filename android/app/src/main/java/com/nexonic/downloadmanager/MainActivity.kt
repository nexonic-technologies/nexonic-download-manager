package com.nexonic.downloadmanager

import com.nexonic.downloadmanager.video.VideoEngine
import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexonic.downloadmanager.browser.Browser
import com.nexonic.downloadmanager.browser.BrowserRules
import com.nexonic.downloadmanager.browser.Offer
import com.nexonic.downloadmanager.core.Links
import com.nexonic.downloadmanager.data.Downloads
import com.nexonic.downloadmanager.core.AppLanguage
import com.nexonic.downloadmanager.service.Notifier
import com.nexonic.downloadmanager.service.Scheduler
import com.nexonic.downloadmanager.service.canNotify
import com.nexonic.downloadmanager.torrent.TorrentPreview
import com.nexonic.downloadmanager.ui.App
import com.nexonic.downloadmanager.ui.NexonicTheme

class NexonicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.load(this)
        // Errors that stop the app are written to a report on the phone (Settings → Crash reports); never sent anywhere.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            com.nexonic.downloadmanager.core.CrashReports.write(java.io.File(filesDir, "crash-reports"),
                com.nexonic.downloadmanager.core.CrashReports.text(error, BuildConfig.VERSION_NAME, "Android ${Build.VERSION.RELEASE}, ${Build.MODEL}"))
            previous?.uncaughtException(thread, error)
        }
        Downloads.init(this)
        com.nexonic.downloadmanager.ui.Appearance.choice = Downloads.settings.appearance
        com.nexonic.downloadmanager.ui.Appearance.scheme = Downloads.settings.colorScheme
        com.nexonic.downloadmanager.ui.Appearance.font = Downloads.settings.appFont
        com.nexonic.downloadmanager.ui.Appearance.textScale = Downloads.settings.textScale
        Notifier.channels(this)
        Scheduler.apply(this)
        VideoEngine.init(this)
    }
}

enum class Tab { DOWNLOADS, BROWSER, SETTINGS }

/** Shared UI state: which tab is open and which add window or menu is showing. */
class UiState {
    var tab by mutableStateOf(Tab.DOWNLOADS)
    /** The add window: a browser offer, or pasted/shared text. */
    var addOffer by mutableStateOf<Offer?>(null)
    var addText by mutableStateOf<String?>(null)
    /** The widget's Add button: the copied link goes into the Add window once the app is in front. */
    var pasteCopied by mutableStateOf(false)
    /** A magnet link or .torrent file to show in the Add Torrent window. */
    var addTorrent by mutableStateOf<String?>(null)
    /** A playlist or channel link to list in the Add Playlist window ("" opens it empty). */
    var addPlaylist by mutableStateOf<String?>(null)
    var showLibrary by mutableStateOf(false)
    var showFeeds by mutableStateOf(false)
    var longPress by mutableStateOf<Pair<Offer, Boolean>?>(null)
    var detailId by mutableStateOf<Long?>(null)
    var message by mutableStateOf<String?>(null)
    var showFound by mutableStateOf(false)
}

/** Retains UI state and WebView navigation state across Activity recreations (e.g. language or font changes). */
class MainViewModel : ViewModel() {
    val ui = UiState()
    var webState: Bundle? = null
}

class MainActivity : ComponentActivity() {
    private lateinit var vm: MainViewModel
    private val ui: UiState get() = vm.ui
    lateinit var browser: Browser
        private set

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLanguage.load(this)
        Downloads.init(this)
        vm = ViewModelProvider(this)[MainViewModel::class.java]
        browser = Browser(
            this,
            onOffer = { offer ->
                if (Downloads.settings.askBeforeDownload) ui.addOffer = offer
                else com.nexonic.downloadmanager.ui.quickAdd(offer) { ui.message = it }
            },
            onLongPress = { offer, image -> ui.longPress = offer to image },
            onTorrent = { source -> ui.tab = Tab.DOWNLOADS; ui.addTorrent = source },
            onNexonic = { links -> ui.tab = Tab.DOWNLOADS; ui.addText = links.joinToString("\n") },
        )
        val savedWeb = vm.webState ?: savedInstanceState?.getBundle("browser_state")
        if (savedWeb != null) {
            browser.restoreState(savedWeb)
            vm.webState = null
        }
        val demo = Demo.requested(intent)
        if (!demo && Build.VERSION.SDK_INT >= 33 && !canNotify(this)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) {
            handle(intent)
            if (demo) Demo.run(intent, ui, browser)
        }
        setContent { NexonicTheme { App(ui, browser) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        Downloads.pump()
        if (ui.tab == Tab.BROWSER) browser.resume()
    }

    override fun onPause() {
        if (::browser.isInitialized) browser.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::browser.isInitialized) {
            val web = Bundle()
            browser.saveState(web)
            vm.webState = web
            outState.putBundle("browser_state", web)
        }
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            val web = Bundle()
            browser.saveState(web)
            vm.webState = web
            browser.destroy()
        }
        super.onDestroy()
    }

    /** Links shared from other apps, "Open with" web links, and taps on notifications. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            com.nexonic.downloadmanager.service.NexonicWidget.ACTION_ADD_COPIED -> { ui.tab = Tab.DOWNLOADS; ui.pasteCopied = true }
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
                // A shared .torrent file, magnet link, or link ending in .torrent opens the Add Torrent window.
                val stream = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value
                val links = Links.split(text)
                val singleTorrent = links.singleOrNull()?.takeIf { TorrentPreview.isTorrent(it) }
                if (stream != null || magnet != null || singleTorrent != null || intent.type.equals("application/x-bittorrent", true)) {
                    ui.tab = Tab.DOWNLOADS
                    ui.addTorrent = stream?.toString() ?: magnet ?: singleTorrent
                    intent.action = null
                    return
                }
                if (links.isEmpty()) ui.message = "No web link was shared."
                else { ui.tab = Tab.DOWNLOADS; ui.addText = links.joinToString("\n") }
            }
            Intent.ACTION_VIEW -> {
                val u = intent.dataString
                val isTorrentUri = u != null && (
                    u.startsWith("magnet:", true) ||
                    intent.type.equals("application/x-bittorrent", true) ||
                    ((Links.isWebLink(u) || u.startsWith("content://") || u.startsWith("file://")) &&
                        BrowserRules.isTorrentDownload(u, intent.type)) ||
                    ((u.startsWith("content://") || u.startsWith("file://")) &&
                        u.substringBefore('?').substringBefore('#').endsWith(".torrent", true))
                )
                if (isTorrentUri) {
                    ui.tab = Tab.DOWNLOADS; ui.addTorrent = u; intent.action = null; return
                }
                val shared = u?.let { Links.fromNexonicLink(it) }.orEmpty()
                if (shared.isNotEmpty()) { ui.tab = Tab.DOWNLOADS; ui.addText = shared.joinToString("\n") }  // a QR code from the desktop app
                else if (u != null && Links.isWebLink(u)) { ui.tab = Tab.BROWSER; browser.go(u) }
            }
        }
        if (intent.getBooleanExtra("startLater", false)) Downloads.startLater()
        intent.getLongExtra("open", 0L).takeIf { it != 0L }?.let { ui.tab = Tab.DOWNLOADS; ui.detailId = it }
        intent.action = null
    }
}
