package com.winlator.cmod.linux;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Environment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/** Checks the actual Android storage paths and FEX launch plan without copying or launching a game. */
public final class LinuxProgramImportChecks {
    private static void check(boolean ok, String name, StringBuilder report) {
        if (!ok) throw new AssertionError(name);
        report.append("PASS ").append(name).append('\n');
    }

    public static String run(Instrumentation test, String program) throws Exception {
        Context context = test.getTargetContext();
        Container base = new ContainerManager(context).getContainers().stream()
                .filter(Container::isLinux).findFirst().orElseThrow();
        java.util.Map<String, String> fexSettings = new java.util.HashMap<>();
        fexSettings.put(LinuxEmulator.EXTRA_EMULATOR, LinuxEmulator.FEX);
        fexSettings.put(LinuxEmulator.EXTRA_ID, LinuxEmulator.list(context, LinuxEmulator.FEX).get(0).id);
        fexSettings.put(LinuxEmulator.EXTRA_ROOTFS, LinuxRootfs.list(context).get(0).id);
        Container fex = base.copyWithExtras(fexSettings);
        Container box64 = base.copyWithExtras(Collections.singletonMap(LinuxEmulator.EXTRA_EMULATOR, LinuxEmulator.BOX64));
        String name = "import-check-" + UUID.randomUUID();
        File internal = new File(base.getLinuxHomeDir(), ".skynet/" + name);
        File shared = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        StringBuilder report = new StringBuilder();
        try {
            check(internal.mkdirs() && shared.mkdirs(), "isolated storage fixtures", report);
            for (int machine : new int[]{62, 3, 183, -1}) {
                byte[] bytes = new byte[20];
                if (machine != -1) {
                    bytes[0]=0x7f; bytes[1]='E'; bytes[2]='L'; bytes[3]='F'; bytes[18]=(byte)machine;
                }
                File inside = new File(internal, "program-" + machine);
                File outside = new File(shared, "program-" + machine);
                Files.write(inside.toPath(), bytes); Files.write(outside.toPath(), bytes);
                check(!LinuxProgramInstaller.needsCopy(fex, inside), "FEX reuses internal program " + machine, report);
                check(!LinuxProgramInstaller.needsCopy(box64, inside), "Box64 reuses internal program " + machine, report);
                check(LinuxProgramInstaller.needsCopy(fex, outside), "FEX still imports shared program " + machine, report);
                check(LinuxProgramInstaller.needsCopy(box64, outside) == (machine != 62),
                        "shared Box64 import rules preserved " + machine, report);
            }
            File copiedGame = new File(program);
            check(copiedGame.isFile() && LinuxSession.elfMachine(copiedGame) == 62, "real copied BeamNG executable", report);
            check(!LinuxProgramInstaller.needsCopy(fex, copiedGame), "copied BeamNG does not need importing", report);
            String guest = LinuxProgramInstaller.guestPath(fex, copiedGame);
            check(guest.startsWith("/root/") && LinuxSession.hostFile(context, fex, guest).getCanonicalFile().equals(copiedGame.getCanonicalFile()),
                    "guest shortcut path resolves to the same file", report);
            String[] command = LinuxSession.programCommand(context, fex, guest);
            check("/bin/bash".equals(command[0]) && Arrays.asList(command).contains(guest),
                    "FEX accepts the internal game launch without shared-storage refusal: " + Arrays.toString(command), report);
            check(Arrays.stream(command).anyMatch(s -> s.endsWith("/bin/FEX")), "launch plan uses installed FEX", report);
        } finally {
            FileUtils.delete(internal); FileUtils.delete(shared);
        }
        return report.toString();
    }
}
