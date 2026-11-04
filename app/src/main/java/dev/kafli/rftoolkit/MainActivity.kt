package dev.kafli.rftoolkit

import android.Manifest
import android.app.RemoteInput
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import dev.kafli.rftoolkit.core.ExportStore
import dev.kafli.rftoolkit.modules.probe.CapabilityProbe
import dev.kafli.rftoolkit.modules.ble.TrackerScan
import dev.kafli.rftoolkit.modules.hunt.Candidate
import dev.kafli.rftoolkit.modules.hunt.HuntEngine
import dev.kafli.rftoolkit.modules.hunt.HuntTarget
import dev.kafli.rftoolkit.modules.hunt.TargetScan
import dev.kafli.rftoolkit.modules.ble.TrackerWatch
import dev.kafli.rftoolkit.modules.lan.LanListener
import dev.kafli.rftoolkit.modules.radar.ChannelScan
import dev.kafli.rftoolkit.modules.radar.ChannelStat
import dev.kafli.rftoolkit.modules.radar.JamMonitor
import dev.kafli.rftoolkit.modules.radar.MagMonitor
import dev.kafli.rftoolkit.modules.radar.UltraMonitor
import dev.kafli.rftoolkit.modules.net.LinkInfo
import dev.kafli.rftoolkit.modules.net.NetTools
import dev.kafli.rftoolkit.modules.nfc.TagContentType
import dev.kafli.rftoolkit.modules.nfc.TagEmulationService
import dev.kafli.rftoolkit.modules.nfc.TagStore
import dev.kafli.rftoolkit.modules.survey.SurveyPreset
import dev.kafli.rftoolkit.modules.survey.SurveyService
import dev.kafli.rftoolkit.modules.survey.SurveyState
import dev.kafli.rftoolkit.modules.wifi.WifiFindings
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import dev.kafli.rftoolkit.ui.FindingsScreen
import dev.kafli.rftoolkit.ui.HomeEntry
import dev.kafli.rftoolkit.ui.HomeScreen
import dev.kafli.rftoolkit.ui.HuntPickerScreen
import dev.kafli.rftoolkit.ui.HuntScreen
import dev.kafli.rftoolkit.ui.ChannelsScreen
import dev.kafli.rftoolkit.ui.JammingScreen
import dev.kafli.rftoolkit.ui.LanScreen
import dev.kafli.rftoolkit.ui.MagScreen
import dev.kafli.rftoolkit.ui.RadarMenuScreen
import dev.kafli.rftoolkit.ui.UltrasonicScreen
import dev.kafli.rftoolkit.ui.NetTool
import dev.kafli.rftoolkit.ui.NetToolsScreen
import dev.kafli.rftoolkit.ui.NfcTagScreen
import dev.kafli.rftoolkit.ui.ProbeScreen
import dev.kafli.rftoolkit.ui.SurveyScreen
import dev.kafli.rftoolkit.ui.TrackersScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEXT_INPUT_KEY = "text_value"

class MainActivity : ComponentActivity() {

    // Probe state
    private var probeRunning by mutableStateOf(false)
    private var probeStatus by mutableStateOf("Tap Run to probe this watch")
    private var probeHighlights by mutableStateOf<Map<String, String>>(emptyMap())

    // Findings quick-scan state
    private var findingsScanning by mutableStateOf(false)
    private var findingsStatus by mutableStateOf<String?>(null)

    // Text entry via the watch keyboard / voice (RemoteInput), shared by NFC and network tools.
    private var onTextResult: ((String) -> Unit)? = null

    private val textInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(TEXT_INPUT_KEY) }
                ?.toString()?.trim()
            if (!text.isNullOrEmpty()) onTextResult?.invoke(text)
            onTextResult = null
        }

    private fun askText(label: String, onResult: (String) -> Unit) {
        onTextResult = onResult
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(TEXT_INPUT_KEY).setLabel(label).build()))
        textInput.launch(intent)
    }

    private fun editTag(type: TagContentType) {
        askText(if (type == TagContentType.URL) "URL" else "Text") { text ->
            val value = if (type == TagContentType.URL && !text.contains("://")) "https://$text" else text
            TagStore.update(this) { it.copy(type = type, value = value) }
        }
    }

    // Threat radar
    private var channelStats by mutableStateOf<List<ChannelStat>>(emptyList())
    private var channelScanning by mutableStateOf(false)
    private val radarPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun scanChannels() {
        if (channelScanning) return
        channelScanning = true
        lifecycleScope.launch {
            channelStats = runCatching { ChannelScan.scan(this@MainActivity) }.getOrDefault(channelStats)
            channelScanning = false
        }
    }

    // Network tools state
    private var netLink by mutableStateOf<LinkInfo?>(null)
    private var netTarget by mutableStateOf("example.com")
    private var netRunning by mutableStateOf<NetTool?>(null)
    private var netProgress by mutableStateOf<String?>(null)
    private var netOutput by mutableStateOf<List<String>>(emptyList())

    private fun runNetTool(tool: NetTool) {
        if (netRunning != null) return
        netRunning = tool
        netProgress = null
        netOutput = emptyList()
        lifecycleScope.launch {
            netOutput = try {
                val t = netTarget
                when (tool) {
                    NetTool.PING -> NetTools.ping(t)
                    NetTool.TRACE -> NetTools.traceroute(t)
                    NetTool.DNS -> NetTools.dnsCheck(t)
                    NetTool.TLS -> NetTools.tlsInspect(t)
                    NetTool.HEADERS -> NetTools.httpHeaders(t)
                    NetTool.PORTS -> {
                        val ip = withContext(Dispatchers.IO) { java.net.InetAddress.getByName(t).hostAddress ?: t }
                        NetTools.scanHost(ip)
                    }
                    NetTool.SWEEP -> NetTools.sweepSubnet(this@MainActivity) { netProgress = it }
                }
            } catch (e: Throwable) {
                listOf("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                netRunning = null
            }
        }
    }

    /**
     * Samsung Health also registers the NDEF tag AID. While our NFC screen is in the foreground we
     * ask Android to route that AID to our service; it reverts as soon as we release it.
     */
    private fun setTagServicePreferred(preferred: Boolean) {
        runCatching {
            val ce = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(this) ?: return)
            if (preferred) ce.setPreferredService(this, ComponentName(this, TagEmulationService::class.java))
            else ce.unsetPreferredService(this)
        }
    }

    // Fox hunt target scan state
    private var huntCandidates by mutableStateOf<List<Candidate>>(emptyList())
    private var huntScanning by mutableStateOf(false)
    private var huntStatus by mutableStateOf<String?>(null)

    private val huntScanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { huntScan() }

    // Trackers quick-scan state
    private var trackerScanning by mutableStateOf(false)
    private var trackerStatus by mutableStateOf<String?>(null)

    private val trackerScanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { trackerScan() }

    private val scanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { quickScan() }

    private val probePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runProbe() }

    private val surveyPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                SurveyService.start(this)
            } else {
                Toast.makeText(this, "Survey needs precise location", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entries = listOf(
            HomeEntry("City survey", "Wi-Fi + BLE + GPS log", "survey"),
            HomeEntry("Wi-Fi findings", "Weak security · IoT · evil twin", "findings"),
            HomeEntry("Trackers", "AirTag · SmartTag · Tile · FMDN", "trackers"),
            HomeEntry("Fox hunt", "Find a device by signal", "hunt"),
            HomeEntry("NFC tag", "Watch as a programmable tag", "nfc"),
            HomeEntry("Network tools", "Ping · DNS · TLS · ports", "net"),
            HomeEntry("LAN listener", "mDNS · SSDP · isolation", "lan"),
            HomeEntry("Threat radar", "Channels · jamming · ultrasonic", "radar"),
            HomeEntry("Capability probe", "What this watch supports", "probe"),
        )
        setContent {
            MaterialTheme {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    val survey by SurveyState.status.collectAsStateWithLifecycle()
                    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(entries, survey.running) { nav.navigate(it) }
                        }
                        composable("survey") {
                            SurveyScreen(
                                status = survey,
                                onToggle = {
                                    if (survey.running) {
                                        SurveyService.stop(this@MainActivity)
                                    } else {
                                        // Guard preset is only started from the Trackers screen.
                                        if (!survey.preset.selectable) SurveyState.selectPreset(SurveyPreset.BALANCED)
                                        surveyPermissions.launch(surveyPermissionList())
                                    }
                                },
                                onCyclePreset = { SurveyState.selectPreset(survey.preset.next()) },
                                onToggleKeepWifi = { SurveyState.toggleKeepWifi() },
                            )
                        }
                        composable("findings") {
                            val findings by WifiFindings.list.collectAsStateWithLifecycle()
                            val baseline by WifiFindings.baselineSize.collectAsStateWithLifecycle()
                            FindingsScreen(
                                findings = findings,
                                baselineSsids = baseline,
                                scanning = findingsScanning,
                                scanStatus = findingsStatus,
                                onScan = { scanPermissions.launch(surveyPermissionList()) },
                                onClear = { WifiFindings.clearFindings() },
                                onResetBaseline = { WifiFindings.clearBaseline(this@MainActivity) },
                                onHunt = { bssid, ssid ->
                                    HuntEngine.select(HuntTarget.Wifi(bssid, ssid))
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("trackers") {
                            val tracks by TrackerWatch.tracks.collectAsStateWithLifecycle()
                            val guard = survey.running && survey.preset == SurveyPreset.TRACKER_GUARD
                            TrackersScreen(
                                tracks = tracks,
                                scanning = trackerScanning,
                                scanStatus = trackerStatus,
                                guardRunning = guard,
                                otherSurveyRunning = survey.running && !guard,
                                onScan = { trackerScanPermissions.launch(surveyPermissionList()) },
                                onToggleGuard = {
                                    if (guard) {
                                        SurveyService.stop(this@MainActivity)
                                    } else {
                                        SurveyState.selectPreset(SurveyPreset.TRACKER_GUARD)
                                        surveyPermissions.launch(surveyPermissionList())
                                    }
                                },
                                onClear = { TrackerWatch.clear() },
                                onHunt = { t ->
                                    HuntEngine.select(HuntTarget.Ble(t.id, t.tracker.kind.label))
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("hunt") {
                            val tracks by TrackerWatch.tracks.collectAsStateWithLifecycle()
                            HuntPickerScreen(
                                trackers = tracks,
                                candidates = huntCandidates,
                                scanning = huntScanning,
                                scanStatus = huntStatus,
                                onScan = { huntScanPermissions.launch(surveyPermissionList()) },
                                onPick = { target ->
                                    HuntEngine.select(target)
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("hunt/active") {
                            val hunt by HuntEngine.state.collectAsStateWithLifecycle()
                            HuntScreen(
                                state = hunt,
                                onStart = { HuntEngine.start(this@MainActivity) },
                                onStop = { HuntEngine.stop() },
                                onSweep = { HuntEngine.startSweep() },
                                onToggleHaptics = { HuntEngine.toggleHaptics() },
                            )
                        }
                        composable("nfc") {
                            TagStore.load(this@MainActivity)
                            val cfg by TagStore.config.collectAsStateWithLifecycle()
                            val log by TagStore.log.collectAsStateWithLifecycle()
                            NfcTagScreen(
                                config = cfg,
                                log = log,
                                onToggle = { TagStore.update(this@MainActivity) { it.copy(enabled = !it.enabled) } },
                                onPreset = { type, value -> TagStore.update(this@MainActivity) { it.copy(type = type, value = value) } },
                                onEditUrl = { editTag(TagContentType.URL) },
                                onEditText = { editTag(TagContentType.TEXT) },
                                onClearLog = { TagStore.clearLog() },
                                onEnter = { setTagServicePreferred(true) },
                                onExit = { setTagServicePreferred(false) },
                            )
                        }
                        composable("net") {
                            NetToolsScreen(
                                link = netLink,
                                target = netTarget,
                                running = netRunning,
                                progress = netProgress,
                                output = netOutput,
                                onEnter = {
                                    lifecycleScope.launch {
                                        NetTools.acquireWifi(this@MainActivity)
                                        netLink = NetTools.linkInfo(this@MainActivity)
                                    }
                                },
                                onExit = { NetTools.releaseWifi(this@MainActivity) },
                                onEditTarget = { askText("Host or IP") { netTarget = it } },
                                onSetTarget = { netTarget = it },
                                onRun = { runNetTool(it) },
                            )
                        }
                        composable("lan") {
                            val lan by LanListener.state.collectAsStateWithLifecycle()
                            LanScreen(
                                state = lan,
                                onEnter = { lifecycleScope.launch { LanListener.start(this@MainActivity) } },
                                onExit = { LanListener.stop(this@MainActivity) },
                                onAskOnce = { LanListener.askOnce() },
                            )
                        }
                        composable("radar") {
                            RadarMenuScreen(
                                onEnter = { radarPermissions.launch(surveyPermissionList() + Manifest.permission.RECORD_AUDIO) },
                                onOpen = { nav.navigate(it) },
                            )
                        }
                        composable("radar/channels") {
                            ChannelsScreen(channelStats, channelScanning) { scanChannels() }
                        }
                        composable("radar/jam") {
                            val st by JamMonitor.state.collectAsStateWithLifecycle()
                            JammingScreen(st, onEnter = { JamMonitor.start(this@MainActivity) }, onExit = { JamMonitor.stop() })
                        }
                        composable("radar/ultra") {
                            val st by UltraMonitor.state.collectAsStateWithLifecycle()
                            UltrasonicScreen(st, onEnter = { UltraMonitor.start(this@MainActivity) }, onExit = { UltraMonitor.stop() })
                        }
                        composable("radar/mag") {
                            val st by MagMonitor.state.collectAsStateWithLifecycle()
                            MagScreen(
                                st,
                                onEnter = { MagMonitor.start(this@MainActivity) },
                                onExit = { MagMonitor.stop() },
                                onCalibrate = { MagMonitor.calibrate() },
                                onToggleHaptics = { MagMonitor.toggleHaptics() },
                            )
                        }
                        composable("probe") {
                            ProbeScreen(
                                running = probeRunning,
                                status = probeStatus,
                                highlights = probeHighlights,
                                onRun = { probePermissions.launch(CapabilityProbe.RUNTIME_PERMISSIONS.toTypedArray()) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun surveyPermissionList(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun huntScan() {
        if (huntScanning) return
        huntScanning = true
        lifecycleScope.launch {
            huntStatus = try {
                huntCandidates = TargetScan.scan(this@MainActivity)
                "${huntCandidates.count { it.kind == "BLE" }} BLE · ${huntCandidates.count { it.kind == "Wi-Fi" }} Wi-Fi"
            } catch (t: Throwable) {
                "Scan failed: ${t.message}"
            } finally {
                huntScanning = false
            }
        }
    }

    private fun trackerScan() {
        if (trackerScanning) return
        trackerScanning = true
        lifecycleScope.launch {
            trackerStatus = try {
                val n = TrackerScan.scanOnce(this@MainActivity)
                val tracks = TrackerWatch.tracks.value
                "$n adverts · ${tracks.size} trackers"
            } catch (t: Throwable) {
                "Scan failed: ${t.message}"
            } finally {
                trackerScanning = false
            }
        }
    }

    private fun quickScan() {
        if (findingsScanning) return
        findingsScanning = true
        lifecycleScope.launch {
            try {
                WifiFindings.ensureLoaded(this@MainActivity)
                val results = WifiScan.scanOnce(this@MainActivity)
                val profiles = withContext(Dispatchers.Default) { results.map { WifiScan.profile(it) } }
                val found = WifiFindings.process(this@MainActivity, profiles)
                withContext(Dispatchers.IO) { WifiFindings.saveBaseline(this@MainActivity) }
                findingsStatus = "${profiles.size} APs · ${found.size} findings"
            } catch (t: Throwable) {
                findingsStatus = "Scan failed: ${t.message}"
            } finally {
                findingsScanning = false
            }
        }
    }

    private fun runProbe() {
        if (probeRunning) return
        probeRunning = true
        // Keep the screen on: reader mode and scans need a foreground, resumed activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                val probe = CapabilityProbe(this@MainActivity)
                val json = probe.run { probeStatus = it }
                val file = withContext(Dispatchers.IO) {
                    ExportStore.writeWithLatest(this@MainActivity, "probe", "json", json.toString(2))
                }
                probeHighlights = probe.highlights.toMap()
                probeStatus = "Saved ${file.name}"
            } catch (t: Throwable) {
                probeStatus = "Probe failed: ${t.message}"
            } finally {
                probeRunning = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
