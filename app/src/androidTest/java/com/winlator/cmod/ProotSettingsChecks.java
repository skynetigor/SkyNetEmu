package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Context;
import com.winlator.cmod.container.*;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.linux.*;
import com.winlator.cmod.xenvironment.ImageFs;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

/** Reproduces profile import overriding a saved proot choice, without modifying user containers. */
public final class ProotSettingsChecks {
    private final StringBuilder report = new StringBuilder();
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(report + "FAIL " + message);
        report.append("PASS ").append(message).append('\n');
    }
    public static String run(Instrumentation test) throws Exception {
        return new ProotSettingsChecks().execute(test.getTargetContext());
    }
    private String execute(Context context) throws Exception {
        ContainerManager manager = new ContainerManager(context);
        Container shared = manager.getContainers().stream().filter(Container::isLinux).findFirst().orElseThrow();
        String original = FileUtils.readString(shared.getConfigFile());
        List<File> files = new ArrayList<>();
        try {
            check(LinuxSession.prootEnabled(null) && LinuxSession.prootEnabled("")
                    && LinuxSession.prootEnabled("0") && !LinuxSession.prootEnabled("1"),
                    "proot defaults on and preserves an explicit opt-out");
            Container defaults = shared.copyWithExtras(Collections.singletonMap(LinuxSession.EXTRA_NO_PROOT, ""));
            verifyShortcut(context, defaults, null, "0", files, "shortcut with no saved proot choice defaults on");
            JSONObject data = new JSONObject().put("type", "linux")
                    .put("linuxRuntime", shared.getLinuxRuntime()).put("extraData", new JSONObject());
            for (String containerChoice : new String[]{"0", "1"}) {
                Container copy = shared.copyWithExtras(Collections.singletonMap(LinuxSession.EXTRA_NO_PROOT, containerChoice));
                Map<String, String> inherited = LinuxShortcutDefaults.fromData(context, manager, data);
                check(!inherited.containsKey(LinuxSession.EXTRA_NO_PROOT), "omitted profile proot choice remains unspecified");
                verifyShortcut(context, copy, inherited, containerChoice, files, "profile omission inherits container");
                for (String explicit : new String[]{"0", "1"}) {
                    JSONObject specified = new JSONObject(data.toString());
                    specified.getJSONObject("extraData").put(LinuxSession.EXTRA_NO_PROOT, explicit);
                    verifyShortcut(context, copy, LinuxShortcutDefaults.fromData(context, manager, specified), explicit,
                            files, "explicit profile choice overrides container " + containerChoice);
                }
                verifyShortcut(context, copy, null, containerChoice, files, "ordinary shortcut inherits container");
            }
            // Build the actual commands too: the saved choice must reach the session builder.
            for (String choice : new String[]{"", "0", "1"}) {
                Map<String, String> extras = new LinkedHashMap<>();
                extras.put(LinuxSession.EXTRA_NO_PROOT, choice);
                extras.put(LinuxEmulator.EXTRA_EMULATOR, LinuxEmulator.NONE);
                extras.put(LinuxBionicSession.EXTRA, "1");
                Container experimental = shared.copyWithExtras(extras);
                check(LinuxBionicSession.enabled(experimental) == "1".equals(choice), "proot choice takes precedence over experimental bionic (" + choice + ")");
                extras.put(LinuxBionicSession.EXTRA, "0");
                Container copy = shared.copyWithExtras(extras);
                LinuxSession.Launch launch = LinuxSession.build(context, ImageFs.find(context), copy,
                        LinuxRuntime.resolve(context, copy), new String[]{"/usr/bin/true"});
                boolean usesProot = new File(launch.argv[0]).getName().equals("proot");
                check(usesProot == "proot".equals(launch.executionMode), "actual execution mode matches command for choice " + choice);
                check("1".equals(choice) || usesProot, "default or enabled proot cannot launch directly");
                report.append("INFO linuxNoProot=").append(choice).append(" executionMode=").append(launch.executionMode).append('\n');
            }
            check(original.equals(FileUtils.readString(shared.getConfigFile())), "checks preserve saved container settings");
        } finally {
            for (File file : files) file.delete();
        }
        return report.toString();
    }
    private void verifyShortcut(Context context, Container container, Map<String, String> settings, String expected,
                                List<File> files, String message) {
        File file = ContainerManager.addLinuxShortcut(context, container, "__proot_check_" + System.nanoTime(), "/usr/bin/true", settings);
        files.add(file);
        Shortcut shortcut = new Shortcut(container, file);
        Container session = LinuxEmulatorSetup.of(container, shortcut);
        check(expected.equals(shortcut.getExtra(LinuxSession.EXTRA_NO_PROOT))
                && expected.equals(session.getExtra(LinuxSession.EXTRA_NO_PROOT)), message + " (" + expected + ") survives save/reload and session setup");
    }
}
