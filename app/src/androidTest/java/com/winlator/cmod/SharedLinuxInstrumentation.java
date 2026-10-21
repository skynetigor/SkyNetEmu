package com.winlator.cmod;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.winlator.cmod.container.*;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.gameprofile.GameProfiles;
import com.winlator.cmod.linux.*;
import com.winlator.cmod.steam.SteamContainer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device integration checks against the installed app; creates only temporary shortcuts, never deletes user data. */
public final class SharedLinuxInstrumentation extends Instrumentation {
    private final List<File> temporary = new ArrayList<>();
    private final List<File> temporaryDirectories = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private boolean nativeTouch;
    private Bundle options;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); options = arguments; nativeTouch = arguments != null && "1".equals(arguments.getString("nativeTouch")); start(); }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n');
    }
    private long count(ContainerManager manager) { return manager.getContainers().stream().filter(Container::isLinux).count(); }
    private void async(java.util.function.Consumer<Runnable> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        runOnMainSync(() -> action.accept(latch::countDown));
        check(latch.await(15, TimeUnit.SECONDS), "container operation completes");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_OK;
        try {
            if (options != null && "1".equals(options.getString("linuxLaunchDiagnostics"))) {
                result.putString("stream", com.winlator.cmod.telemetry.LinuxLaunchDiagnosticsChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("prootSettings"))) {
                result.putString("stream", ProotSettingsChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("shortcutEditor"))) {
                result.putString("stream", ShortcutEditorChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("rendererRegistry"))) {
                result.putString("stream", RendererDriverRegistryChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("nativeMissingRuntime"))) {
                result.putString("stream", com.winlator.cmod.ui.addgame.NativeWindowsChecks.missingRuntime(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("nativeCatalog"))) {
                result.putString("stream", NativeCatalogChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("windowsLive"))) {
                result.putString("stream", WindowsLiveChecks.run(this, options));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("nativeWindows"))) {
                result.putString("stream", com.winlator.cmod.ui.addgame.NativeWindowsChecks.run(this, options));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("fexVulkan"))) {
                result.putString("stream", FexVulkanCompatChecks.run(this));
                finish(status,result);return;
            }
            if (options != null && "1".equals(options.getString("gpuMemory"))) {
                result.putString("stream", LinuxGpuMemoryChecks.run(this));
                finish(status,result);return;
            }
            if (options != null && "1".equals(options.getString("programExit"))) {
                result.putString("stream", new ProgramExitChecks(this).run());
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("programImport"))) {
                result.putString("stream", LinuxProgramImportChecks.run(this, options.getString("program")));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("fileManager"))) {
                result.putString("stream", FileManagerChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("framePipeline"))) {
                result.putString("stream", com.winlator.cmod.linux.FramePipelineChecks.run(this) + new RendererDiagnosticsChecks(this).run());
                finish(status,result);return;
            }
            if (options != null && "1".equals(options.getString("performanceTuning"))) {
                result.putString("stream", com.winlator.cmod.perf.PerformanceTuningChecks.run(this));
                finish(status, result); return;
            }
            if (options != null && "1".equals(options.getString("elasticsearchTelemetry"))) {
                result.putString("stream", com.winlator.cmod.telemetry.ElasticsearchTelemetryChecks.run(this,
                        "1".equals(options.getString("liveEs"))));
                finish(status, result);
                return;
            }
            if (options != null && "1".equals(options.getString("rendererDiagnostics"))) {
                result.putString("stream", new RendererDiagnosticsChecks(this).run());
                finish(status, result);
                return;
            }
            if (options != null && "1".equals(options.getString("liveTouch"))) {
                result.putString("stream", new NativeTouchChecks(this).live(options));
                finish(status,result);
                return;
            }
            if (nativeTouch) {
                result.putString("stream", new NativeTouchChecks(this).run());
                finish(status, result);
                return;
            }
            android.content.Context context = getTargetContext();
            ContainerManager manager = new ContainerManager(context);
            Container shared = SteamContainer.ensure(context, manager);
            check(shared != null && count(manager) == 1, "exactly one shared Linux container");
            List<String> windows = new ArrayList<>();
            for (Container c : manager.getWineContainers()) windows.add(FileUtils.readString(c.getConfigFile()));
            String original = FileUtils.readString(shared.getConfigFile());
            JSONObject imported = new JSONObject().put("type", "linux").put("linuxRuntime", shared.getLinuxRuntime())
                    .put("name", "Test import must not rename shared container").put("screenSize", "960x540")
                    .put("envVars", "SHARED_LINUX_TEST=profile")
                    .put("extraData", new JSONObject().put("linuxEmulator", "box64").put("hudMode", "0"));
            ContainerImporter.Result importedResult = ContainerImporter.run(context, manager, new ContentsManager(context),
                    new ContainerProfile.Envelope(imported, new JSONArray()), null, new ContainerImporter.LinuxPlan());
            check(importedResult.container != null && importedResult.container.id == shared.id && count(manager) == 1,
                    "Linux profile import reuses shared container");
            check("960x540".equals(importedResult.linuxSettings.get("screenSize")), "import returns per-shortcut settings");
            check(original.equals(FileUtils.readString(shared.getConfigFile())), "import preserves shared defaults");
            Container duplicate = manager.createLinuxContainerFromData(new JSONObject(imported.toString()));
            check(duplicate.id == shared.id && count(new ContainerManager(context)) == 1, "creation cannot create a second Linux container");
            check(manager.createContainerFromData(new JSONObject(imported.toString()), new ContentsManager(context)).id == shared.id,
                    "generic creation API also reuses Linux container");
            manager.removeManagedContainer(shared);
            check(shared.getConfigFile().isFile(), "direct deletion API protects Linux container");
            async(done -> manager.removeContainerAsync(shared, done));
            async(done -> manager.duplicateContainerAsync(shared, done));
            check(shared.getConfigFile().isFile() && count(new ContainerManager(context)) == 1, "delete and duplicate APIs preserve singleton");
            check(SteamContainer.reset(context, manager).id == shared.id, "legacy reset API cannot wipe Linux data");
            String prefix = "__shared_linux_test_" + System.nanoTime();
            File a = ContainerManager.addLinuxShortcut(context, shared, prefix + "_a", LinuxSession.STEAM_GAME_PREFIX + "203160", importedResult.linuxSettings);
            File b = ContainerManager.addLinuxShortcut(context, shared, prefix + "_b", "/usr/bin/true");
            temporary.add(a); temporary.add(b);
            Shortcut first = new Shortcut(shared, a), second = new Shortcut(shared, b);
            check("native".equals(first.getExtra(LinuxTouchMode.EXTRA)), "new Linux shortcut defaults to native touch");
            first.putExtra(LinuxTouchMode.EXTRA, null);
            first.putExtra("simTouchScreen", "0");
            LinuxShortcutDefaults.snapshot(context, shared, first);
            check("touchpad".equals(first.getExtra(LinuxTouchMode.EXTRA)), "version-3 legacy touchpad shortcut migrates once");
            first.putExtra(LinuxTouchMode.EXTRA, "native");
            LinuxShortcutDefaults.snapshot(context, shared, first);
            check("native".equals(first.getExtra(LinuxTouchMode.EXTRA)), "explicit native mode overrides old simulated-touch setting");
            check("native".equals(second.getExtra(LinuxTouchMode.EXTRA)), "touch mode remains independent per shortcut");
            first.putExtra("screenSize", "960x540");
            first.putExtra("hudMode", "0");
            first.putExtra("cpuList", "0");
            first.putExtra("linuxNoProot", "0");
            first.putExtra("lsfgPerformanceMode", "0");
            first.putExtra("lsfgPresentMode", "fifo");
            first.putExtra("envVars", "FEX_TSOENABLED=1 SHARED_LINUX_TEST=shortcut");
            first.putExtra("launchOptions", "FEX_TSOENABLED=0 %command% -nolauncher");
            first.saveData();
            check(original.equals(FileUtils.readString(shared.getConfigFile())), "shortcut edits do not modify shared defaults");
            check("960x540".equals(GameProfiles.screenSize(context, shared, first, null)), "Steam game uses shortcut resolution");
            Container session = LinuxEmulatorSetup.of(shared, first);
            check("0".equals(session.getCPUList()) && "0".equals(session.getExtra("linuxNoProot")), "session uses shortcut CPU and proot settings");
            check(session.getLinuxRuntime().equals(shared.getLinuxRuntime()), "session resolves shortcut Linux runtime");
            check("native".equals(session.getExtra(LinuxTouchMode.EXTRA)), "session receives shortcut touch mode");
            java.lang.reflect.Method guestEnv = LinuxSession.class.getDeclaredMethod("guestEnv", android.content.Context.class,
                    File.class, File.class, Container.class, File.class, String.class);
            guestEnv.setAccessible(true);
            List<String> nativeEnv = (List<String>)guestEnv.invoke(null, context, LinuxRuntime.rootDir(context, LinuxRuntime.resolve(context, session).id),
                    com.winlator.cmod.xenvironment.ImageFs.find(context).getRootDir(), session, null, null);
            check(nativeEnv.contains("SDL_TOUCH_MOUSE_EVENTS=0") && nativeEnv.contains("SDL_MOUSE_TOUCH_EVENTS=0"), "native mode disables both SDL mouse synthesis directions");
            session.setEnvVars(session.getEnvVars() + " SDL_TOUCH_MOUSE_EVENTS=1");
            List<String> explicitEnv = (List<String>)guestEnv.invoke(null, context, LinuxRuntime.rootDir(context, LinuxRuntime.resolve(context, session).id),
                    com.winlator.cmod.xenvironment.ImageFs.find(context).getRootDir(), session, null, null);
            check(explicitEnv.contains("SDL_TOUCH_MOUSE_EVENTS=1") && !explicitEnv.contains("SDL_TOUCH_MOUSE_EVENTS=0"), "explicit shortcut environment overrides SDL touch defaults");
            check("0".equals(session.getExtra("lsfgPerformanceMode")) && "fifo".equals(session.getExtra("lsfgPresentMode")),
                    "session uses shortcut frame-generation performance and present mode");
            check("1".equals(LinuxLaunchOptions.parse(first.getExtra("launchOptions"), first.getExtra("envVars")).env.get("FEX_TSOENABLED")),
                    "shortcut environment overrides launch option assignments");
            check(!"shortcut".equals(LinuxLaunchOptions.parse(second.getExtra("launchOptions"), second.getExtra("envVars")).env.get("SHARED_LINUX_TEST")),
                    "other shortcut environment remains independent");
            Shortcut saved = new Shortcut(shared, a);
            check("0".equals(saved.getExtra("hudMode")) && "0".equals(saved.getExtra("cpuList")) && "960x540".equals(saved.getExtra("screenSize")),
                    "shortcut settings persist after reload");
            ArrayList<Shortcut> shortcuts = manager.loadShortcuts();
            check(shortcuts.stream().filter(s -> s.container.isLinux()).allMatch(s -> s.container.id == shared.id), "all Linux shortcuts point to shared container");
            check(shortcuts.stream().anyMatch(s -> LinuxSession.isSteam(s.path) && s.container.id == shared.id), "Steam uses same container");
            check(shortcuts.stream().filter(s -> LinuxSession.isSteam(s.path)).noneMatch(Shortcut::hasFixedSettings),
                    "Steam shortcut settings are editable");
            List<String> afterWindows = new ArrayList<>();
            for (Container c : new ContainerManager(context).getWineContainers()) afterWindows.add(FileUtils.readString(c.getConfigFile()));
            check(windows.equals(afterWindows), "Windows container configurations unchanged");
            int windowsId = manager.getNextContainerId();
            File fixture = new File(shared.getRootDir().getParentFile(), "xuser-" + windowsId);
            check(fixture.mkdirs(), "isolated Windows test fixture created");
            temporaryDirectories.add(fixture);
            FileUtils.writeString(new File(fixture, ".container"), new JSONObject().put("name", prefix + "_windows")
                    .put("type", Container.TYPE_WINE).toString());
            ContainerManager windowsManager = new ContainerManager(context);
            Container windowsContainer = windowsManager.getContainerById(windowsId);
            int windowsCount = windowsManager.getWineContainers().size();
            async(done -> windowsManager.duplicateContainerAsync(windowsContainer, done));
            check(windowsManager.getWineContainers().size() == windowsCount + 1, "Windows duplication remains available");
            for (Container c : new ArrayList<>(windowsManager.getWineContainers())) {
                if (c.id == windowsId || c.getName().startsWith(prefix + "_windows")) {
                    temporaryDirectories.add(c.getRootDir());
                    async(done -> windowsManager.removeContainerAsync(c, done));
                    check(!c.getRootDir().exists(), "Windows deletion remains available");
                }
            }
            File oldRedirect = new File(shared.getRootDir().getParentFile(), "xuser-2/" + SharedLinuxContainers.REDIRECT);
            if (oldRedirect.isFile()) check(manager.getContainerById(2).id == shared.id, "old pinned container ID resolves to shared Linux");
            result.putString("stream", report.toString());
        } catch (Throwable error) {
            status = Activity.RESULT_CANCELED;
            result.putString("stream", report + "FAIL " + android.util.Log.getStackTraceString(error));
        } finally {
            for (File file : temporary) file.delete();
            for (File directory : temporaryDirectories) if (directory.exists()) FileUtils.delete(directory);
        }
        finish(status, result);
    }
}
