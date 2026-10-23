package com.winlator.cmod.ui.shortcut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.gameprofile.GameProfile
import com.winlator.cmod.gameprofile.GameProfileCatalog
import com.winlator.cmod.gameprofile.GameProfiles
import com.winlator.cmod.linux.LinuxDriverManager
import com.winlator.cmod.linux.LinuxEmulator
import com.winlator.cmod.linux.LinuxEmulatorSetup
import com.winlator.cmod.linux.LinuxFex
import com.winlator.cmod.linux.LinuxGpuMemory
import com.winlator.cmod.linux.LinuxLaunchOptions
import com.winlator.cmod.linux.LinuxRuntime
import com.winlator.cmod.linux.LinuxRootfs
import com.winlator.cmod.linux.LinuxSession
import com.winlator.cmod.linux.LinuxShortcutDefaults
import com.winlator.cmod.lsfg.LsfgManager
import com.winlator.cmod.ui.settings.EditorCategory
import com.winlator.cmod.ui.settings.EnvironmentVariablesEditor
import com.winlator.cmod.ui.settings.RailEditor
import com.winlator.cmod.ui.settings.CpuSelectorRow
import com.winlator.cmod.ui.settings.RendererDriverPicker
import com.winlator.cmod.ui.settings.LinuxDriverPicker
import com.winlator.cmod.ui.settings.rememberLinuxDriverPicker
import com.winlator.cmod.ui.settings.rememberRendererDriverPicker
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingText
import com.winlator.cmod.ui.container.FexDownloadPrompt
import com.winlator.cmod.ui.container.ResolutionSetting
import com.winlator.cmod.ui.container.isValidScreenSize
import com.winlator.cmod.ui.container.UPSCALER_MODES
import com.winlator.cmod.ui.settings.SettingMappedChoice
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FLOW_SCALES = listOf("0.25", "0.50", "0.75", "0.80", "1.00")
private const val FIT = "fullscreenStretched"
private const val UPSCALER = "graphicsFilterMode"
private const val SIZE = "screenSize"

/**
 * The settings of a shortcut that starts a Linux program. A Wine shortcut has a page of Wine, DXVK and drive options
 * that mean nothing here (and read fields a Linux container does not have). They are grouped as in the container's
 * editor. Every setting is the shortcut's own: it starts as a copy of the container's (see
 * [com.winlator.cmod.linux.LinuxShortcutDefaults]), and changing the container afterwards does not change it.
 */
@Composable
internal fun LinuxShortcutEditor(shortcut: Shortcut, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = shortcut.container
    // A shortcut made before the settings were its own still follows the container: it is given its own copy here, once.
    remember { LinuxShortcutDefaults.snapshot(context, container, shortcut) }
    var options by remember { mutableStateOf(shortcut.getExtra(LinuxLaunchOptions.EXTRA)) }
    var envVars by remember { mutableStateOf(shortcut.getExtra(LinuxLaunchOptions.EXTRA_ENV_VARS)) }
    val parsed = remember(options, envVars) { LinuxLaunchOptions.parse(options, envVars) }
    val runtimes = remember { LinuxRuntime.listInstalled(context) }
    var runtimeId by remember { mutableStateOf(shortcut.getExtra(LinuxShortcutDefaults.RUNTIME)) }
    var prootEnabled by remember { mutableStateOf(LinuxSession.prootEnabled(shortcut.getExtra(LinuxSession.EXTRA_NO_PROOT))) }
    var fps by remember { mutableStateOf(shortcut.getExtra("graphicsFpsPreset", "0")) }
    var hud by remember { mutableStateOf(shortcut.getExtra("hudMode", "0")) }
    val cpuSelected = remember {
        val selected = shortcut.getExtra("cpuList").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        mutableStateListOf<Boolean>().apply {
            repeat(Runtime.getRuntime().availableProcessors().coerceIn(1, 32)) { add(selected.isEmpty() || it in selected) }
        }
    }
    val performanceTuning = remember(shortcut) { com.winlator.cmod.ui.settings.PerformanceTuningEditorState(shortcut) }
    var size by remember { mutableStateOf(shortcut.getExtra(SIZE)) }
    var fit by remember { mutableStateOf(shortcut.getExtra(FIT)) }
    var upscaler by remember { mutableStateOf(shortcut.getExtra(UPSCALER)) }
    var touch by remember { mutableStateOf(com.winlator.cmod.linux.LinuxTouchMode.resolve(container, shortcut, null)) }
    var steamClient by remember { mutableStateOf(shortcut.getExtra(com.winlator.cmod.linux.LinuxSession.EXTRA_STEAM_MINIMAL)) }
    var multiplier by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_MULTIPLIER)) }
    var lsfgPerformance by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_PERFORMANCE_MODE, "1")) }
    var lsfgPresent by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_PRESENT_MODE, LsfgManager.PRESENT_MODE_MAILBOX)) }
    var flowScale by remember { mutableStateOf(shortcut.getExtra(LsfgManager.EXTRA_FLOW_SCALE)) }
    // The game profile: "" follows the registry (a Steam game's own app), "none" turns it off, anything else is a game's id.
    var profileId by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_PROFILE)) }
    var profileVersion by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_VERSION)) }
    var noSteam by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_NO_STEAM)) }
    var gameRoot by remember { mutableStateOf(shortcut.getExtra(GameProfiles.EXTRA_ROOT)) }
    // The x86 emulator: empty follows the container. The names are the container's, so the session code is shared.
    var bionic by remember { mutableStateOf(if (prootEnabled) "0" else shortcut.getExtra(com.winlator.cmod.linux.LinuxBionicSession.EXTRA)) }
    var emulatorChoice by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_EMULATOR)) }
    var emulatorId by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_ID)) }
    var emulatorPreset by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_PRESET)) }
    var rootfsId by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_ROOTFS)) }
    var fexPreset by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_FEX_PRESET)) }
    var fexThunks by remember { mutableStateOf(shortcut.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS)) }
    var fexGl32 by remember { mutableStateOf(shortcut.getExtra(LinuxFex.EXTRA_GL_32)) }
    var tuSysmem by remember { mutableStateOf(shortcut.getExtra(LinuxEmulatorSetup.EXTRA_TU_SYSMEM)) }
    var glVendor by remember { mutableStateOf(shortcut.getExtra(LinuxFex.EXTRA_GL_VENDOR)) }
    var rendererDriverId by remember { mutableStateOf(shortcut.getRendererDriverId()) }
    val rendererDriverPicker = rememberRendererDriverPicker(rendererDriverId) { rendererDriverId = it }
    var driverId by remember { mutableStateOf(shortcut.getExtra(LinuxDriverManager.EXTRA_DRIVER)) }
    var gpuMemory by remember { mutableStateOf(LinuxGpuMemory.normalize(shortcut.getExtra(LinuxGpuMemory.EXTRA))) }
    var glDriver by remember { mutableStateOf(shortcut.getExtra(LinuxSession.EXTRA_GL_DRIVER)) }
    var vulkanPresent by remember { mutableStateOf(shortcut.getExtra(LinuxSession.EXTRA_VULKAN_PRESENT)) }
    var keyboardButton by remember { mutableStateOf(shortcut.getExtra(LinuxSession.EXTRA_KEYBOARD_BUTTON)) }
    var autoKeyboard by remember { mutableStateOf(shortcut.getExtra(LinuxSession.EXTRA_AUTO_KEYBOARD)) }
    val linuxDriverPicker = rememberLinuxDriverPicker(driverId) { driverId = it }
    val boxBuilds = remember { LinuxEmulator.list(context, LinuxEmulator.BOX64) }
    // Read again when FEX has been downloaded from this screen.
    var fexInstallTick by remember { mutableStateOf(0) }
    val fexBuilds = remember(fexInstallTick) { LinuxEmulator.list(context, LinuxEmulator.FEX) }
    val rootfsList = remember(fexInstallTick) { LinuxRootfs.list(context) }
    val boxPresets = remember { Box64PresetManager.getPresets("box64", context).associateTo(LinkedHashMap<String, String>()) { it.id to it.name } }
    val fexPresets = remember { FEXCorePresetManager.getPresets(context).associateTo(LinkedHashMap<String, String>()) { it.id to it.name } }
    // The registry's games: what is on hand at once, then what the network has.
    var games by remember { mutableStateOf<List<GameProfileCatalog.IndexEntry>>(emptyList()) }
    var profile by remember { mutableStateOf<GameProfile?>(null) }
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, false) }
        games = withContext(Dispatchers.IO) { GameProfileCatalog.index(context, true) }
    }
    val automaticId = remember(games) {
        if (shortcut.isSteamGame) games.firstOrNull { it.steamAppId == LinuxSession.steamAppId(shortcut.path) }?.id ?: "" else ""
    }
    val profileIdNow = if (profileId == GameProfiles.NONE) "" else profileId.ifEmpty { automaticId }
    LaunchedEffect(profileIdNow) {
        profile = if (profileIdNow.isEmpty()) null else withContext(Dispatchers.IO) {
            GameProfileCatalog.game(context, profileIdNow, false) ?: GameProfileCatalog.game(context, profileIdNow, true)
        }
    }
    // What the profile makes of this shortcut as it is being edited, what it would make with the version recognised and with
    // the "without Steam" choice left to the profile.
    val effective = remember(profile, profileVersion, noSteam, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, profileVersion, noSteam, gameRoot) }
    }
    val detected = remember(profile, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, "", "", gameRoot) }
    }
    val profileDefault = remember(profile, profileVersion, gameRoot) {
        profile?.let { GameProfiles.resolve(context, container, shortcut, it, profileVersion, "", gameRoot) }
    }
    // A Steam game that is not started without Steam goes through the client, and so runs at the device's own size.
    val throughSteam = shortcut.isSteamGame && effective?.runsWithoutSteam() != true
    // The profile's settings are the shortcut's as soon as one is chosen: they are copied into the fields, not followed.
    var takeFromProfile by remember { mutableStateOf(false) }
    LaunchedEffect(effective) {
        val shown = effective
        if (takeFromProfile && shown != null) {
            takeFromProfile = false
            if (shown.resolution.isNotEmpty()) size = shown.resolution
            shown.extras.forEach { (key, value) ->
                if (value.isNullOrEmpty()) return@forEach
                when (key) {
                    FIT -> fit = value
                    UPSCALER -> upscaler = value
                    "simTouchScreen" -> if (!shown.extras.containsKey("linuxTouchMode") && touch != "mouse") touch = if (value == "0") "touchpad" else "native"
                    "linuxTouchMode" -> touch = value
                    LsfgManager.EXTRA_MULTIPLIER -> multiplier = value
                    LsfgManager.EXTRA_FLOW_SCALE -> flowScale = value
                }
            }
        }
    }

    // What the program runs under.
    val effectiveChoice = emulatorChoice.ifEmpty { LinuxEmulator.choice(container) }
    val emulator = if (effectiveChoice == LinuxEmulator.NONE) "none (ARM64 programs only)" else {
        val builds = if (effectiveChoice == LinuxEmulator.FEX) fexBuilds else boxBuilds
        (builds.firstOrNull { it.id == emulatorId } ?: builds.firstOrNull())?.name ?: "$effectiveChoice (not installed)"
    }
    val emulatorProblem = when {
        effectiveChoice == LinuxEmulator.NONE -> false
        effectiveChoice == LinuxEmulator.FEX -> fexBuilds.isEmpty() || rootfsList.isEmpty()
        else -> boxBuilds.isEmpty()
    }
    val hasDll = remember { LsfgManager.hasDll(context) }
    val sizeValid = size.isEmpty() || isValidScreenSize(size.trim())

    fun save() {
        // One line: the shortcut file keeps each extra on a line of its own.
        val text = options.replace('\n', ' ').trim()
        shortcut.putExtra(LinuxLaunchOptions.EXTRA, text.ifEmpty { null })
        shortcut.putExtra(LinuxLaunchOptions.EXTRA_ENV_VARS, envVars.trim().ifEmpty { null })
        shortcut.putExtra(LinuxShortcutDefaults.RUNTIME, runtimeId)
        shortcut.putExtra(LinuxSession.EXTRA_NO_PROOT, if (prootEnabled) "0" else "1")
        shortcut.putExtra("cpuList", cpuSelected.indices.filter { cpuSelected[it] }.joinToString(","))
        shortcut.putExtra("graphicsFpsPreset", fps)
        shortcut.putExtra("hudMode", hud)
        shortcut.putExtra(SIZE, size.trim().ifEmpty { null })
        shortcut.putExtra(FIT, fit.ifEmpty { null })
        shortcut.putExtra("linuxTouchMode", touch)
        shortcut.putExtra("simTouchScreen", if (touch == "native") "1" else "0")
        shortcut.putExtra(com.winlator.cmod.linux.LinuxSession.EXTRA_STEAM_MINIMAL, steamClient.ifEmpty { null })
        shortcut.putExtra(UPSCALER, upscaler.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_MULTIPLIER, multiplier.ifEmpty { null })
        shortcut.putExtra(LsfgManager.EXTRA_PERFORMANCE_MODE, lsfgPerformance)
        shortcut.putExtra(LsfgManager.EXTRA_PRESENT_MODE, lsfgPresent)
        shortcut.putExtra(LsfgManager.EXTRA_FLOW_SCALE, flowScale.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_PROFILE, profileId.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_VERSION, profileVersion.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_NO_STEAM, noSteam.ifEmpty { null })
        shortcut.putExtra(GameProfiles.EXTRA_ROOT, gameRoot.trim().ifEmpty { null })
        shortcut.putExtra(com.winlator.cmod.linux.LinuxBionicSession.EXTRA, bionic.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_EMULATOR, emulatorChoice.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_ID, emulatorId.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_PRESET, emulatorPreset.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_ROOTFS, rootfsId.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_FEX_PRESET, fexPreset.ifEmpty { null })
        shortcut.putExtra(LinuxEmulator.EXTRA_FEX_THUNKS, fexThunks.ifEmpty { null })
        shortcut.putExtra(LinuxFex.EXTRA_GL_32, fexGl32.ifEmpty { null })
        shortcut.putExtra(LinuxFex.EXTRA_GL_VENDOR, glVendor.ifEmpty { null })
        shortcut.putExtra(LinuxEmulatorSetup.EXTRA_TU_SYSMEM, tuSysmem.ifEmpty { null })
        shortcut.setRendererDriverId(rendererDriverId)
        shortcut.putExtra(LinuxDriverManager.EXTRA_DRIVER, driverId.ifEmpty { null })
        shortcut.putExtra(LinuxGpuMemory.EXTRA, gpuMemory)
        shortcut.putExtra(LinuxSession.EXTRA_GL_DRIVER, glDriver.ifEmpty { null })
        shortcut.putExtra(LinuxSession.EXTRA_VULKAN_PRESENT, vulkanPresent.ifEmpty { null })
        shortcut.putExtra(LinuxSession.EXTRA_KEYBOARD_BUTTON, keyboardButton.ifEmpty { null })
        shortcut.putExtra(LinuxSession.EXTRA_AUTO_KEYBOARD, autoKeyboard.ifEmpty { null })
        shortcut.putExtra(LinuxShortcutDefaults.EXTRA_OWN, "1")
        performanceTuning.saveTo(shortcut)
        shortcut.saveData()
        onClose()
    }

    val graphicsSummary = listOf(
        if (size.trim() == "native") "native size" else size.trim(),
        when (fit) { "1" -> "fit"; "2" -> "game window"; else -> "1:1" },
        UPSCALER_MODES.firstOrNull { it.first == upscaler && it.first != "0" }?.second?.substringBefore(" (") ?: "no upscaler"
    ).joinToString(" · ")
    val frameGenerationSummary =
        if (multiplier == "0" || multiplier.isEmpty()) "Off" else "${multiplier}x · flow $flowScale"

    val gameSummary = profile?.let { it.name + " · " + (effective?.version?.label ?: "version not recognised") }
        ?: if (profileId == GameProfiles.NONE) "None (turned off)" else "None"

    val categories = listOfNotNull(
        EditorCategory("performance_tuning", "Performance tuning", "Override global defaults", false) {
            com.winlator.cmod.ui.settings.PerformanceTuningGameCard(performanceTuning)
        },
        EditorCategory("game", "Game profile", gameSummary, false) {
            SettingsCard {
                SettingMappedChoice(
                    "Game",
                    profileId,
                    LinkedHashMap<String, String>().apply {
                        put(
                            "",
                            if (!shortcut.isSteamGame) "None"
                            else if (automaticId.isEmpty()) "Automatic (the registry has none for this game)"
                            else "Automatic (${games.firstOrNull { it.id == automaticId }?.name ?: automaticId})"
                        )
                        if (shortcut.isSteamGame) put(GameProfiles.NONE, "None: don't use a profile")
                        games.forEach { put(it.id, it.name) }
                        if (profileId.isNotEmpty() && profileId != GameProfiles.NONE && profileId !in this) put(profileId, "$profileId (not in the registry)")
                    }
                ) {
                    profileId = it
                    profileVersion = ""
                    noSteam = ""
                    takeFromProfile = true
                }
                val current = profile
                if (current != null) {
                    SettingsDivider()
                    SettingMappedChoice(
                        "Version",
                        profileVersion,
                        LinkedHashMap<String, String>().apply {
                            put("", "Recognise it (now: ${detected?.version?.label ?: "not recognised"})")
                            current.versions.forEach { put(it.id, it.label + if (it.status == GameProfile.STATUS_BROKEN) " (does not work)" else "") }
                        }
                    ) {
                        profileVersion = it
                        takeFromProfile = true
                    }
                    if (!shortcut.isSteamGame) {
                        SettingsDivider()
                        SettingText("Game folder", gameRoot) { gameRoot = it }
                        Text(
                            "e.g. /root/programs/${current.name.replace(' ', '_')}",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val shown = effective
                    if (shown != null && shown.hasNoSteam) {
                        SettingsDivider()
                        SettingMappedChoice(
                            "Launch without Steam",
                            noSteam,
                            linkedMapOf(
                                "" to "Game profile default (${if (profileDefault?.noSteam == true) "On" else "Off"})",
                                "1" to "On",
                                "0" to "Off"
                            )
                        ) { noSteam = it }
                        Text(
                            if (shortcut.isSteamGame) "On: the game's own program starts straight away, with ${shown.noSteamArg}, and the Steam client does not. Off: the game is started through Steam."
                            else "On: ${shown.noSteamArg} is added to the program's arguments.",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SettingsDivider()
                    PreviewRow("Program", shown?.executableGuest ?: "—")
                    val version = shown?.version
                    if (shown != null && version != null) {
                        SettingsDivider()
                        PreviewRow(
                            "Status",
                            when (version.status) {
                                GameProfile.STATUS_WORKS -> "Works"
                                GameProfile.STATUS_BROKEN -> "Does not work"
                                else -> "Not tried"
                            } + (if (version.testedOn.isNotEmpty()) " · ${version.testedOn}" else "") +
                                (if (shown.how.isNotEmpty()) " · found by ${shown.how}" else "") +
                                (if (version.notes.isNotEmpty()) "\n${version.notes}" else "")
                        )
                    }
                }
            }
            if (profile == null) {
                Text(
                    "A game profile from the registry gives the shortcut the program to start, the arguments and the resolution that are " +
                        "known to work for a version of the game. Choosing one writes its settings into the groups below, where you can change them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // A Steam game has launch options in Steam too (the game's properties); these are applied by the launcher that starts the game, after Steam's.
        EditorCategory("launch", "Launch options", listOfNotNull(
            options.takeIf { it.isNotBlank() },
            if (parsed.env.isNotEmpty()) "${parsed.env.size} variables" else null
        ).joinToString(" · ").ifBlank { "None" }, false) {
            SettingsCard { SettingText("Launch options (NAME=value %command% -windowed)", options, minLines = 2) { options = it } }
            Text(
                (if (throughSteam) "Applied when Steam starts the game, on top of the options set in Steam. They reach a game that needs an x86 emulator; " +
                    "a game that runs natively is started by Steam itself. " else "") +
                "Like Steam's: variables before %command%, the program's arguments after it " +
                    "(MANGOHUD=1 BOX64_LOG=1 %command% -windowed -width 1280). A wrapper may stand between them " +
                    "(taskset -c 4-7 %command%). Without %command%, leading NAME=value words are variables and the " +
                    "rest are arguments. Quote a word that holds a space.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Environment variables", style = MaterialTheme.typography.titleSmall)
            Text(
                "Variables set here override the container, emulator preset, game profile and assignments in Launch options. " +
                    "Remove a variable to use the default again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            EnvironmentVariablesEditor(
                value = envVars,
                onChanged = { envVars = it },
                showManagedWarning = false
            )
            if (options.isNotBlank() || envVars.isNotBlank()) {
                SettingsCard {
                    PreviewRow("Variables", parsed.env.entries.joinToString("\n") { "${it.key}=${it.value}" })
                    SettingsDivider()
                    PreviewRow("Wrapper", parsed.wrapper.joinToString(" "))
                    SettingsDivider()
                    PreviewRow("Arguments", parsed.args.joinToString("\n"))
                }
            }
        },
        EditorCategory("graphics", "Graphics", graphicsSummary, !sizeValid) {
            SettingsCard {
                ResolutionSetting(size) { size = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Scaling",
                    fit,
                    linkedMapOf(
                        "1" to "Fit to screen (keep proportions)",
                        "2" to "Scale the game window to the screen (a game smaller than the container)",
                        "0" to "Original size (1:1)"
                    )
                ) { fit = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Upscaler (needs native output and Fit to screen)",
                    upscaler,
                    LinkedHashMap<String, String>().apply { UPSCALER_MODES.forEach { put(it.first, it.second) } }
                ) { upscaler = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Turnip without tiled rendering (TU_DEBUG=sysmem)",
                    tuSysmem,
                    linkedMapOf("1" to "On", "0" to "Off")
                ) { tuSysmem = it }
                SettingsDivider()
                LinuxDriverPicker(driverId, linuxDriverPicker) { driverId = it }
                SettingsDivider()
                SettingMappedChoice("Max GPU memory", gpuMemory, linkedMapOf(
                    "0" to "Default (driver)", "512" to "512 MB", "1024" to "1 GB", "2048" to "2 GB",
                    "4096" to "4 GB", "8192" to "8 GB", "12288" to "12 GB", "16384" to "16 GB"
                )) { gpuMemory = it }
                Text("Graphics memory reported to Vulkan games and Zink. Uses shared phone RAM; does not limit total game RAM. Restart the game to apply.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SettingsDivider()
                SettingMappedChoice(
                    "OpenGL driver",
                    glDriver,
                    linkedMapOf(
                        "zink" to "Zink on Turnip (OpenGL 4.6 over Vulkan, accelerated)",
                        "software" to "Software (llvmpipe, CPU)"
                    )
                ) { glDriver = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Vulkan output",
                    vulkanPresent,
                    linkedMapOf(
                        "native" to "Native (dma-buf, needed for upscalers and frame generation)",
                        "sw" to "Software (CPU copy, experimental)"
                    )
                ) { vulkanPresent = it }
                if (vulkanPresent == "native") {
                    SettingsDivider()
                    RendererDriverPicker(rendererDriverId, rendererDriverPicker) { rendererDriverId = it }
                }
            }
        },
        EditorCategory("framegen", "Frame generation", frameGenerationSummary, false) {
            SettingsCard {
                SettingMappedChoice(
                    "Multiplier",
                    multiplier,
                    linkedMapOf("0" to "Off", "2" to "2x", "3" to "3x", "4" to "4x")
                ) { multiplier = it }
                if (multiplier.isNotEmpty() && multiplier != "0" && !hasDll) {
                    Text(
                        "Frame generation stays off until you import Lossless.dll in Settings > Components > Frame Generation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
                SettingsDivider()
                SettingMappedChoice(
                    "Flow scale",
                    flowScale,
                    LinkedHashMap<String, String>().apply { FLOW_SCALES.forEach { put(it, it) } }
                ) { flowScale = it }
                SettingsDivider()
                SettingMappedChoice("Performance mode", lsfgPerformance, linkedMapOf("1" to "On", "0" to "Off")) { lsfgPerformance = it }
                SettingsDivider()
                SettingMappedChoice("Present mode", lsfgPresent, linkedMapOf(LsfgManager.PRESENT_MODE_MAILBOX to "Mailbox", LsfgManager.PRESENT_MODE_FIFO to "FIFO")) { lsfgPresent = it }
            }
            Text(
                "Works with native output only (the container's \"Software Vulkan output\" off). Changes made in the " +
                    "game's sidebar are kept on the shortcut once it has its own multiplier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        EditorCategory("performance", "Performance", "${cpuSelected.count { it }} CPU cores", false) {
            SettingsCard {
                CpuSelectorRow("CPU cores", cpuSelected) { index, checked ->
                    if (checked || cpuSelected.count { it } > 1) cpuSelected[index] = checked
                }
                SettingsDivider()
                SettingMappedChoice("FPS limit", fps, linkedMapOf("0" to "Off", "1" to "30 FPS", "2" to "60 FPS", "3" to "90 FPS", "4" to "120 FPS")) { fps = it }
                SettingsDivider()
                SettingMappedChoice("HUD", hud, linkedMapOf("0" to "Off", "1" to "Classic", "2" to "Modern")) { hud = it }
            }
        },
        EditorCategory(
            "input", "Input",
            touch,
            false
        ) {
            SettingsCard {
                SettingMappedChoice(
                    context.getString(com.winlator.cmod.R.string.input_mode),
                    touch,
                    linkedMapOf(
                        "native" to context.getString(com.winlator.cmod.R.string.input_mode_native),
                        "touchpad" to context.getString(com.winlator.cmod.R.string.input_mode_touchpad),
                        "mouse" to context.getString(com.winlator.cmod.R.string.input_mode_mouse)
                    )
                ) { touch = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Keyboard button on the screen",
                    keyboardButton,
                    linkedMapOf("1" to "On", "0" to "Off")
                ) { keyboardButton = it }
                SettingsDivider()
                SettingMappedChoice(
                    "Open the keyboard when a text field is tapped",
                    autoKeyboard,
                    linkedMapOf("1" to "On", "0" to "Off")
                ) { autoKeyboard = it }
            }
        },
        if (!throughSteam) null else EditorCategory(
            "steam", "Steam client",
            if (steamClient == "0") "the whole client" else "minimal (no window)",
            false
        ) {
            SettingsCard {
                SettingMappedChoice(
                    "Steam client",
                    steamClient,
                    linkedMapOf(
                        "" to "Minimal: no Steam window, saves memory (once you are signed in)",
                        "0" to "Whole client: its window stays open"
                    )
                ) { steamClient = it }
            }
            Text(
                "To start a game the Steam client needs neither its window nor the web helper that draws it, which takes " +
                    "most of its memory. The minimal start works once you have signed in with \"Remember me\"; until then the " +
                    "whole client opens so that you can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        // A Steam game's emulator is applied by the launcher that starts the game, so it can differ from the client's session.
        EditorCategory(
            "emulation", "Runtime and emulation",
            emulator,
            emulatorProblem
        ) {
            SettingsCard {
                SettingMappedChoice("Linux runtime", runtimeId, LinkedHashMap<String, String>().apply {
                    runtimes.forEach { put(it.id, it.name) }
                    if (runtimeId.isNotEmpty() && runtimeId !in this) put(runtimeId, "$runtimeId (not installed)")
                }) { runtimeId = it }
                SettingsDivider()
                SettingToggle("Enable proot", prootEnabled) { prootEnabled = it; if (it) bionic = "0" }
                SettingsDivider()
                SettingMappedChoice(
                    "x86 emulator",
                    effectiveChoice,
                    linkedMapOf(
                        LinuxEmulator.NONE to "None (ARM64 programs only)",
                        LinuxEmulator.BOX64 to "Box64",
                        LinuxEmulator.FEX to "FEX"
                    )
                ) {
                    // The builds and presets of one emulator mean nothing to the other.
                    emulatorChoice = it
                    emulatorId = ""
                    emulatorPreset = ""
                }
                SettingsDivider()
                SettingMappedChoice(
                    "Runtime",
                    bionic,
                    linkedMapOf(
                        "0" to "Linux runtime (glibc)",
                        "1" to "Android's C library (experimental, Box64)"
                    )
                ) { bionic = it; if (it == "1") prootEnabled = false }
                if (effectiveChoice == LinuxEmulator.BOX64 || effectiveChoice == LinuxEmulator.FEX) {
                    val fex = effectiveChoice == LinuxEmulator.FEX
                    val builds = if (fex) fexBuilds else boxBuilds
                    SettingsDivider()
                    if (builds.isEmpty()) {
                        // FEX comes with the root filesystem it runs in: one button downloads whatever is missing.
                        if (fex) FexDownloadPrompt(onInstalled = { fexInstallTick++ })
                        else Text(
                            "No Box64 build is installed.",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        SettingMappedChoice(
                            "${if (fex) "FEX" else "Box64"} build",
                            emulatorId,
                            LinkedHashMap<String, String>().apply {
                                put("", "Whichever is installed")
                                builds.forEach { put(it.id, it.name) }
                            }
                        ) { emulatorId = it }
                    }
                    SettingsDivider()
                    if (fex) {
                        if (rootfsList.isEmpty()) {
                            // With no FEX build either, the prompt above already offers both.
                            if (fexBuilds.isNotEmpty()) FexDownloadPrompt(onInstalled = { fexInstallTick++ })
                        } else {
                            SettingMappedChoice(
                                "x86-64 root filesystem",
                                rootfsId,
                                LinkedHashMap<String, String>().apply {
                                    put("", "Whichever is installed")
                                    rootfsList.forEach { put(it.id, it.name) }
                                }
                            ) { rootfsId = it }
                        }
                        SettingsDivider()
                        SettingMappedChoice(
                            "FEX preset",
                            fexPreset,
                            LinkedHashMap<String, String>().apply { putAll(fexPresets) }
                        ) { fexPreset = it }
                        SettingsDivider()
                        SettingMappedChoice(
                            "Use the runtime's Vulkan (FEX thunk)",
                            fexThunks,
                            linkedMapOf("1" to "On", "0" to "Off")
                        ) { fexThunks = it }
                        SettingsDivider()
                        SettingMappedChoice(
                            "32-bit games on the GPU (OpenGL thunk)",
                            fexGl32,
                            linkedMapOf("1" to "On", "0" to "Off (software OpenGL)")
                        ) { fexGl32 = it }
                        SettingsDivider()
                        SettingMappedChoice(
                            "GPU a 32-bit game is told it has",
                            glVendor,
                            linkedMapOf("nvidia" to "NVIDIA", "amd" to "AMD", "mesa" to "As it is (Mesa / zink)")
                        ) { glVendor = it }
                    } else {
                        SettingMappedChoice(
                            "Box64 preset",
                            emulatorPreset,
                            LinkedHashMap<String, String>().apply { putAll(boxPresets) }
                        ) { emulatorPreset = it }
                    }
                }
            }
            Text(
                if (effectiveChoice == LinuxEmulator.FEX)
                    "FEX runs a program and its libraries as executable code, which shared storage (/storage/...) does not allow: the " +
                        "program has to be inside the container (add it with \"Program only\" or \"Whole game\"). 32-bit programs run on FEX only."
                else "Box64 reads the program where it is, including on shared storage. A 32-bit program is started by FEX when it is installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        EditorCategory("program", "Program", shortcut.path, false) {
            SettingsCard {
                PreviewRow("Path", shortcut.path)
                SettingsDivider()
                PreviewRow("Container", container.name)
                SettingsDivider()
                PreviewRow("Runs under", "Linux · $emulator")
            }
        }
    )

    // As in the Windows shortcut editor: the groups in a rail (chips in portrait), saved when the screen is left.
    fun leave() {
        if (rendererDriverPicker.installing.isNotEmpty() || linuxDriverPicker.installing.isNotEmpty()) {
            android.widget.Toast.makeText(context, "Wait for the driver download to finish.", android.widget.Toast.LENGTH_SHORT).show()
        } else if (sizeValid) save()
        else android.widget.Toast.makeText(context, "Fix the screen size first.", android.widget.Toast.LENGTH_SHORT).show()
    }
    androidx.activity.compose.BackHandler(onBack = ::leave)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(shortcut.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            RailEditor("Shortcut settings", categories)
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
