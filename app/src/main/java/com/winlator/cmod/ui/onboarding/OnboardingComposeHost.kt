package com.winlator.cmod.ui.onboarding

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GPUInformation
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.settings.cleanContainerEnvironment
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject

@Immutable
data class OnboardingComponent @JvmOverloads constructor(
    val id: String,
    val type: String,
    val name: String,
    val installed: Boolean,
    val recommended: Boolean,
    val removable: Boolean,
    val runtimeIdentifier: String? = null,
    val inUse: Boolean = false,
    val bundled: Boolean = false,
    /** "stable" or "prerelease" from the registry; null shows no badge. */
    val channel: String? = null
)

interface OnboardingCallbacks {
    fun onInstall(componentId: String)
    fun onRemove(componentId: String)
    fun onInstallBundledRuntime()
    fun onRemoveBundledRuntime()
    fun onBrowseLocal()
    fun onBrowseDriver()
    fun onRequestPermissions()
    fun onRetryCore()
    fun onCloseComponents()
}

class OnboardingComposeController internal constructor(
    private val coreReady: MutableState<Boolean>,
    private val coreProgress: MutableState<Int>,
    private val bundledWineInstalled: MutableState<Boolean>,
    private val bundledWineInUse: MutableState<Boolean>,
    private val components: MutableState<List<OnboardingComponent>>,
    private val installingId: MutableState<String?>,
    private val installingLabel: MutableState<String?>,
    private val installingProgress: MutableState<Int>
) {
    fun updateCore(ready: Boolean, progress: Int) {
        coreReady.value = ready
        coreProgress.value = progress.coerceIn(0, 100)
    }

    fun updateBundledRuntime(installed: Boolean, inUse: Boolean) {
        bundledWineInstalled.value = installed
        bundledWineInUse.value = inUse
    }

    fun setComponents(value: List<OnboardingComponent>) {
        components.value = value.toList()
    }

    fun setInstallBusy(componentId: String?, busy: Boolean) {
        installingId.value = if (busy) componentId else null
        if (busy) {
            installingLabel.value = null
            installingProgress.value = -1
        } else {
            installingLabel.value = null
            installingProgress.value = -1
        }
    }

    fun updateInstallProgress(label: String?, progress: Int) {
        installingLabel.value = label
        installingProgress.value = progress.coerceIn(-1, 100)
    }
}

object OnboardingComposeHost {
    @JvmStatic
    fun attach(
        activity: ComponentActivity,
        initialCoreReady: Boolean,
        initialCoreProgress: Int,
        initialBundledWineInstalled: Boolean,
        initialBundledWineInUse: Boolean,
        componentManagerMode: Boolean,
        callbacks: OnboardingCallbacks
    ): OnboardingComposeController {
        val ready = mutableStateOf(initialCoreReady)
        val progress = mutableStateOf(initialCoreProgress)
        val bundledInstalled = mutableStateOf(initialBundledWineInstalled)
        val bundledInUse = mutableStateOf(initialBundledWineInUse)
        val components = mutableStateOf<List<OnboardingComponent>>(emptyList())
        val installing = mutableStateOf<String?>(null)
        val installLabel = mutableStateOf<String?>(null)
        val installProgress = mutableStateOf(-1)
        val controller = OnboardingComposeController(
            ready,
            progress,
            bundledInstalled,
            bundledInUse,
            components,
            installing,
            installLabel,
            installProgress
        )

        applyAppFullscreen(activity)
        activity.setContent {
            WinZTheme {
                OnboardingFlow(
                    activity,
                    ready,
                    progress,
                    bundledInstalled,
                    bundledInUse,
                    components,
                    installing,
                    installLabel,
                    installProgress,
                    componentManagerMode,
                    callbacks
                )
            }
        }
        return controller
    }
}

private enum class OnboardingPage { Welcome, Theme, Components, Privacy, Access }

@Composable
private fun OnboardingFlow(
    activity: ComponentActivity,
    ready: State<Boolean>,
    progress: State<Int>,
    bundledInstalled: State<Boolean>,
    bundledInUse: State<Boolean>,
    components: State<List<OnboardingComponent>>,
    installing: State<String?>,
    installingLabel: State<String?>,
    installingProgress: State<Int>,
    managerMode: Boolean,
    cb: OnboardingCallbacks
) {
    var page by rememberSaveable(managerMode) {
        mutableStateOf(if (managerMode) OnboardingPage.Components else OnboardingPage.Theme)
    }

    val hasInstalledRuntime = bundledInstalled.value || components.value.any {
        it.installed && (it.type == "Wine" || it.type == "Proton") && !it.runtimeIdentifier.isNullOrBlank()
    }

    when (page) {
        OnboardingPage.Welcome -> ClassicWinlatorWelcome(
            ready,
            progress,
            start = { page = OnboardingPage.Theme },
            skip = { page = OnboardingPage.Privacy },
            retry = { cb.onRetryCore() }
        )

        OnboardingPage.Theme -> OnboardingThemeScreen(
            onContinue = { page = OnboardingPage.Privacy }
        )

        OnboardingPage.Components -> OnboardingComponentsScreen(
            ready = ready,
            progress = progress,
            bundledInstalled = bundledInstalled,
            bundledInUse = bundledInUse,
            all = components.value,
            installing = installing.value,
            installingLabel = installingLabel.value,
            installingProgress = installingProgress.value,
            managerMode = managerMode,
            onBack = {
                if (managerMode) cb.onCloseComponents()
                else page = OnboardingPage.Theme
            },
            onContinue = {
                if (managerMode) cb.onCloseComponents()
                else if (ready.value && hasInstalledRuntime) page = OnboardingPage.Privacy
            },
            cb = cb,
            // No Wine/Proton installed yet: it can be done later from Settings.
            onSkip = { page = OnboardingPage.Privacy }
        )

        OnboardingPage.Privacy -> OnboardingPrivacyScreen(
            onChoose = { share ->
                com.winlator.cmod.telemetry.Telemetry.setEnabled(activity, share)
                page = OnboardingPage.Access
            },
            back = { page = OnboardingPage.Theme }
        )

        OnboardingPage.Access -> OnboardingAccessScreen(
            back = { page = OnboardingPage.Privacy },
            next = { cb.onRequestPermissions() }
        )
    }
}
