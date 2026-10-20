package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Context;

import com.winlator.cmod.container.ContainerProfile;
import com.winlator.cmod.contents.ContainerConfigCatalog;
import com.winlator.cmod.contents.RegistryCache;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.gameprofile.ConfigChooser;
import com.winlator.cmod.gameprofile.GameProfile;
import com.winlator.cmod.gameprofile.GameProfileCatalog;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only checks of the shipped/native registry selection using Android's real JSON parser. */
public final class NativeCatalogChecks {
    private static final String NATIVE_CONFIG = "beamng-040-windows-arm64-proton9-dd-turnip";
    private static final String LINUX_CONFIG = "beamng-040-linux-box64-r3";
    private static final String SOC = "snapdragon-8-elite-gen-5";

    private static void check(boolean condition, String message, StringBuilder report) {
        if (!condition) throw new AssertionError(report + "FAIL " + message);
        report.append("PASS ").append(message).append('\n');
    }

    public static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        StringBuilder report = new StringBuilder();
        String bundledIndex = RegistryCache.bundledCopy(context, ContainerConfigCatalog.INDEX);
        String cachedIndex = RegistryCache.get(context, ContainerConfigCatalog.INDEX, RegistryCache.TTL_INDEX, false);
        int bundledRevision = new JSONObject(bundledIndex).optInt("revision");
        int cachedRevision = cachedIndex == null ? -1 : new JSONObject(cachedIndex).optInt("revision");
        Map<String, ContainerConfigCatalog.Config> all = ContainerConfigCatalog.all(context, false);
        check(all.containsKey(NATIVE_CONFIG), "native BeamNG preset is visible with the phone's cached registry", report);
        check("arm64".equals(all.get(NATIVE_CONFIG).windowsArchitecture),
                "native preset can be filtered using index metadata without downloading its profile", report);
        if (bundledRevision > cachedRevision)
            check(all.get(NATIVE_CONFIG).file.equals("containers/" + NATIVE_CONFIG + ".wcfg"),
                    "newer bundled container index supplies the native preset over an older cached index", report);

        GameProfile profile = GameProfileCatalog.game(context, "beamng-drive", false);
        GameProfile.Version version = profile == null ? null : profile.version("0.40");
        check(version != null && version.containers.contains(NATIVE_CONFIG), "effective BeamNG 0.40 profile includes the native preset", report);
        check(LINUX_CONFIG.equals(version.preferredContainer), "BeamNG 0.40 keeps its Linux preferred configuration", report);
        check(profile.windowsExecutables.contains("BinArm64/BeamNG.drive.arm64.exe"), "effective BeamNG profile recognizes the ARM64 executable", report);

        Map<String, ContainerConfigCatalog.Config> nativeWindows = new LinkedHashMap<>();
        Map<String, ContainerConfigCatalog.Config> translatedWindows = new LinkedHashMap<>();
        Map<String, ContainerConfigCatalog.Config> linux = new LinkedHashMap<>();
        for (ContainerConfigCatalog.Config config : all.values()) {
            if (config.platform == GameProfile.Platform.LINUX) {
                linux.put(config.id, config);
                continue;
            }
            boolean nativeArm64;
            if (!config.windowsArchitecture.isEmpty()) nativeArm64 = "arm64".equals(config.windowsArchitecture);
            else {
                String text = ContainerConfigCatalog.profileText(context, config, false);
                JSONObject container = text == null ? new JSONObject() : ContainerProfile.parse(text).container;
                JSONObject extra = container.optJSONObject("extraData");
                nativeArm64 = extra != null && WineInfo.NATIVE_ARM64_CONTAINER_VALUE.equals(
                        extra.optString(WineInfo.NATIVE_ARM64_CONTAINER_KEY));
            }
            (nativeArm64 ? nativeWindows : translatedWindows).put(config.id, config);
        }
        ConfigChooser.Choice nativeChoice = ConfigChooser.choose(version, SOC, nativeWindows, GameProfile.Platform.WINDOWS);
        check(nativeChoice != null && NATIVE_CONFIG.equals(nativeChoice.configId) && nativeChoice.suitsDevice,
                "ARM64 executable receives the native BeamNG recommendation after architecture filtering", report);
        check("prerelease".equals(nativeWindows.get(nativeChoice.configId).channel),
                "the native recommendation retains its experimental channel", report);
        check(!translatedWindows.containsKey(NATIVE_CONFIG)
                        && ConfigChooser.choose(version, SOC, translatedWindows, GameProfile.Platform.WINDOWS) == null,
                "x64 BeamNG 0.40 receives no native ARM64 recommendation", report);
        ConfigChooser.Choice linuxChoice = ConfigChooser.choose(version, SOC, linux, GameProfile.Platform.LINUX);
        check(linuxChoice != null && LINUX_CONFIG.equals(linuxChoice.configId),
                "Linux BeamNG 0.40 still receives its Box64 r3 recommendation", report);
        GameProfile.Version old = profile.version("0.34");
        check(old != null && !old.containers.contains(NATIVE_CONFIG)
                        && old.containers.contains("cachyos-x64-box64-dd-turnip"),
                "BeamNG 0.34 retains its existing CachyOS option", report);

        JSONObject nativeProfile = ContainerProfile.parse(ContainerConfigCatalog.profileText(context, all.get(NATIVE_CONFIG), false)).container;
        check("proton-9.0-arm64ec".equals(nativeProfile.optString("wineVersion"))
                        && "wined3d".equals(nativeProfile.optString("dxwrapper"))
                        && !nativeProfile.optString("wincomponents").contains("=1"),
                "native profile uses Proton 9 and ARM64 Wine builtins", report);
        check("DD-Turnip v0.1.1".equals(nativeProfile.optString("rendererDriverId"))
                        && nativeProfile.optString("graphicsDriverConfig").contains("version=DD-Turnip v0.1.1;"),
                "native profile selects the verified DD-Turnip graphics path", report);
        String serialized = nativeProfile.toString();
        check(!serialized.contains("GUEST_PROGRAM_LAUNCHER_COMMAND") && !serialized.contains("/data/user/")
                        && !serialized.contains("-userpath"),
                "native registry profile contains no experimental shell command or device paths", report);
        return report.toString();
    }
}
