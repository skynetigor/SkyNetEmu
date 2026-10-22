package com.winlator.cmod.telemetry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only this session's same-UID descendants. Never emits command lines, filenames, or environment variables. */
public final class LinuxProcessTracker {
    private final File proc;
    private final int uid;
    private final String guest;
    private final Map<Integer, String> seen = new LinkedHashMap<>();
    private final Map<Integer, String> roles = new HashMap<>();
    private boolean guestSeen;
    public LinuxProcessTracker(File proc, int uid, String guestExecutable) {
        this.proc = proc; this.uid = uid; this.guest = guestExecutable == null ? "" : new File(guestExecutable).getName();
    }
    private static String read(File file, int limit) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[Math.min(limit, 1024)];
            while (out.size() < limit) {
                int n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
                if (n <= 0) break;
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
        catch (IOException | SecurityException e) { return ""; }
    }
    private static String status(String text, String key) {
        for (String line : text.split("\n")) if (line.startsWith(key + ":")) return line.substring(line.indexOf(':') + 1).trim().split("\\s+")[0];
        return "-1";
    }
    private static final class Entry {
        int pid, parent; String state, birth, ticks, threads, rss, swap, role, wait, syscall;
    }
    private Entry entry(File dir) {
        try {
            String status = read(new File(dir, "status"), 8192);
            if (!Integer.toString(uid).equals(status(status, "Uid"))) return null;
            String stat = read(new File(dir, "stat"), 4096);
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            Entry e = new Entry(); e.pid = Integer.parseInt(dir.getName()); e.parent = Integer.parseInt(f[1]);
            e.state = f[0].matches("[RSDTZtXIPWK]") ? f[0] : "?"; e.birth = Long.toString(Long.parseLong(f[19]));
            e.ticks = Long.toString(Long.parseLong(f[11]) + Long.parseLong(f[12]));
            e.threads = status(status, "Threads"); e.rss = status(status, "VmRSS"); e.swap = status(status, "VmSwap");
            return e;
        } catch (RuntimeException ignored) { return null; }
    }
    private void details(Entry e) {
        File dir = new File(proc, Integer.toString(e.pid));
        String args = read(new File(dir, "cmdline"), 4096);
        if (args.isEmpty()) {
            e.role = e.birth.equals(seen.get(e.pid)) ? roles.getOrDefault(e.pid, "unknown") : "unknown";
        } else {
            String[] words = args.split("\u0000");
            String first = new File(words.length == 0 ? "" : words[0]).getName();
            boolean wrapper = first.equals("proot") || first.equals("bash") || first.equals("sh") || first.equals("env");
            e.role = wrapper ? "helper" : "other";
            if (!wrapper) for (String arg : words) {
                String name = new File(arg).getName();
                if (!guest.isEmpty() && name.equals(guest)) e.role = "guest";
                else if (e.role.equals("other") && (name.equals("box64") || name.startsWith("FEXInterpreter"))) e.role = "emulator";
            }
            // Chromium renderer/GPU helpers can reuse the game's executable after its main process died.
            for (String arg : words) if (arg.startsWith("--type=")) e.role = "helper";
        }
        String wait = read(new File(dir, "wchan"), 128).trim();
        e.wait = wait.matches("[A-Za-z0-9_]{1,80}") ? wait : "unavailable";
        String syscall = read(new File(dir, "syscall"), 128).trim().split("\\s+")[0];
        e.syscall = syscall.matches("-?[0-9]{1,8}|running") ? syscall : "unavailable";
    }
    public synchronized String snapshot(int root) {
        File[] dirs = proc.listFiles(f -> f.getName().matches("[0-9]+"));
        if (dirs == null) return "processes=unavailable";
        Map<Integer, Entry> all = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(dirs.length, 4096); i++) {
            Entry e = entry(dirs[i]); if (e != null) all.put(e.pid, e);
        }
        Set<Integer> live = new LinkedHashSet<>();
        for (Entry e : all.values()) if ((e.pid == root && (!seen.containsKey(root) || seen.get(root).equals(e.birth)))
                || e.birth.equals(seen.get(e.pid))) live.add(e.pid);
        for (int i = 0; i < 16 && live.size() < 64; i++) {
            int before = live.size();
            for (Entry e : all.values()) if (live.contains(e.parent) && live.size() < 64) live.add(e.pid);
            if (before == live.size()) break;
        }
        int aliveGuests = 0, zombies = 0;
        StringBuilder detail = new StringBuilder();
        for (int pid : live) {
            Entry e = all.get(pid);
            details(e);
            if (seen.size() < 128 || seen.containsKey(pid)) { seen.put(pid, e.birth); roles.put(pid, e.role); }
            if (e.role.equals("guest")) { guestSeen = true; if (!e.state.equals("Z") && !e.state.equals("X")) aliveGuests++; }
            if (e.state.equals("Z")) zombies++;
            if (detail.length() < 1100) detail.append("pid=").append(pid).append(" ppid=").append(e.parent).append(" role=").append(e.role)
                    .append(" state=").append(e.state).append(" threads=").append(e.threads).append(" cpu_ticks=").append(e.ticks)
                    .append(" rss_kb=").append(e.rss).append(" swap_kb=").append(e.swap).append(" wchan=").append(e.wait)
                    .append(" syscall=").append(e.syscall).append('\n');
        }
        return Scrub.text("root_pid=" + root + " readable=" + all.size() + " root_readable=" + all.containsKey(root) + " tracked_live=" + live.size()
                + " guest_seen=" + guestSeen + " guest_alive=" + aliveGuests + " zombies=" + zombies
                + "\n" + detail, 1600);
    }
    public synchronized Set<Integer> knownPids() { return new HashSet<>(seen.keySet()); }
}
