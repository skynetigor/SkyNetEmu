package com.winlator.cmod.telemetry;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LinuxLaunchDiagnosticsTest {
    private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] p : parts) out.write(p); return out.toByteArray();
    }
    private static byte[] vint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); do { int v = (int)(n & 127); n >>>= 7; out.write(v | (n > 0 ? 128 : 0)); } while (n > 0); return out.toByteArray();
    }
    private static byte[] num(int field, int n) throws Exception { return concat(vint(field << 3), vint(n)); }
    private static byte[] msg(int field, byte[] bytes) throws Exception { return concat(vint(field << 3 | 2), vint(bytes.length), bytes); }
    private static byte[] text(int field, String text) throws Exception { return msg(field, text.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(byte[] data) { return NativeCrashTrace.read(new ByteArrayInputStream(data)); }
    private static void proc(Path root, int pid, int ppid, long birth, String state, String command, int uid) throws Exception {
        Path dir = root.resolve("" + pid); Files.createDirectories(dir);
        String[] fields = new String[22]; Arrays.fill(fields, "0"); fields[0] = state; fields[1] = "" + ppid;
        fields[11] = "10"; fields[12] = "5"; fields[19] = "" + birth;
        Files.writeString(dir.resolve("stat"), pid + " (name with ) parentheses) " + String.join(" ", fields));
        Files.writeString(dir.resolve("status"), "Uid:\t" + uid + "\nThreads:\t3\nVmRSS:\t123 kB\nVmSwap:\t4 kB\n");
        Files.writeString(dir.resolve("cmdline"), command);
        Files.writeString(dir.resolve("wchan"), "futex_wait_queue");
        Files.writeString(dir.resolve("syscall"), "98 0xsecret-address");
    }
    public static void main(String[] args) throws Exception {
        LinuxLaunchOutput output = new LinuxLaunchOutput();
        output.append("startup /storage/emulated/0/private/game person@example.org");
        output.append("fatal SIGSYS seccomp syscall=434");
        for (int i = 0; i < 1000; i++) output.append("error loading " + i + " " + "x".repeat(100));
        String capture = output.snapshot();
        check(capture.contains("startup") && capture.contains("syscall=434") && capture.contains("loading 999"), "startup, first fatal and recent output survive noise");
        check(capture.length() < 2600 && !capture.contains("private") && !capture.contains("person@"), "capture is bounded and scrubbed before retention");
        byte[] frame = concat(num(1, 123), text(4, "vkCreateDevice"), text(6, "/data/private/libvulkan.so"));
        byte[] thread = concat(num(1, 102), text(2, "private thread name"), msg(4, frame));
        byte[] tombstone = concat(num(1, 1), num(5, 101), num(6, 102), text(9, "private command"),
                msg(10, concat(num(1, 31), num(3, 1))), msg(15, text(1, "seccomp prevented call to disallowed arm64 system call 434")),
                msg(16, concat(num(1, 102), msg(2, thread))), msg(18, text(1, "private logs")));
        String trace = decode(tombstone);
        check(trace.contains("signal=31 code=1") && trace.contains("seccomp_syscall=434"), "structured SIGSYS and blocked syscall decoded");
        check(trace.contains("libvulkan.so") && trace.contains("vkCreateDevice") && trace.contains("rel_pc=0x7b"), "crashed thread backtrace decoded");
        check(!trace.contains("private") && !trace.contains("command") && !trace.contains("logs"), "memory, logs, command line, full paths and thread names excluded");
        String partial = decode(concat(tombstone, msg(1000, new byte[300000])));
        check(partial.contains("tombstone=partial") && partial.contains("signal=31") && partial.contains("seccomp_syscall=434"),
                "large memory/log payloads do not erase complete signal fields before the size limit");
        check(decode(new byte[]{10, 127}).equals("tombstone=unreadable"), "malformed protobuf handled");
        check(decode(new byte[300000]).equals("tombstone=too_large"), "oversized protobuf bounded");
        check(NativeCrashTrace.read(null).equals("tombstone=unavailable"), "missing tombstone explicit");
        Path root = Files.createTempDirectory("process-fixture-");
        try {
            proc(root, 101, 1, 10, "S", "/usr/bin/bash\0/path/game\0", 42);
            proc(root, 102, 101, 20, "D", "/usr/bin/box64\0/path/game\0", 42);
            proc(root, 103, 1, 30, "R", "/private/unrelated\0", 42);
            proc(root, 104, 101, 40, "S", "/private/otheruid\0", 99);
            proc(root, 105, 101, 50, "S", "/path/game\0--type=renderer\0", 42);
            LinuxProcessTracker tracker = new LinuxProcessTracker(root.toFile(), 42, "/path/game");
            String snapshot = tracker.snapshot(101);
            check(snapshot.contains("tracked_live=3") && snapshot.contains("guest_alive=1"), "only own session descendants sampled; shell and CEF helpers are not main guest");
            check(snapshot.contains("state=D") && snapshot.contains("syscall=98") && snapshot.contains("wchan=futex_wait_queue"), "blocked process state and safe syscall number retained");
            check(!snapshot.contains("secret") && !snapshot.contains("private") && !snapshot.contains("unrelated") && !snapshot.contains("pid=103"), "process output has no argv, addresses or unrelated processes");
            proc(root, 102, 1, 20, "Z", "", 42);
            snapshot = tracker.snapshot(101);
            check(snapshot.contains("guest_seen=true guest_alive=0 zombies=1"), "reparented zombie guest distinguished from living helper");
            proc(root, 102, 1, 999, "S", "/private/reused-pid\0", 42);
            snapshot = tracker.snapshot(101);
            check(!snapshot.contains("pid=102 "), "PID reuse excluded by process start time");
            check(new LinuxProcessTracker(root.resolve("missing").toFile(), 42, "game").snapshot(101).equals("processes=unavailable"), "denied/missing proc access explicit");
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        System.out.println("PASS launch output retention/privacy, structured tombstones, guest/helper process state and PID reuse");
    }
}
