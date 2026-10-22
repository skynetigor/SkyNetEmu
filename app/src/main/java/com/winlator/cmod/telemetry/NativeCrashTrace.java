package com.winlator.cmod.telemetry;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Selected fields from Android debuggerd's tombstone.proto; skips memory, logs, names and command lines.
 * Schema: https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto
 */
public final class NativeCrashTrace {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern SYSCALL = Pattern.compile("(?:system call|syscall)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final class Field { int n; long value; byte[] data; }
    private static List<Field> fields(byte[] data) throws IOException { return fields(data, false); }
    private static List<Field> fields(byte[] data, boolean prefix) throws IOException {
        List<Field> out = new ArrayList<>(); int[] pos = {0};
        while (pos[0] < data.length && out.size() < 4096) {
            long tag;
            try { tag = varint(data, pos); }
            catch (IOException e) { if (prefix) break; throw e; }
            Field f = new Field(); f.n = (int)(tag >>> 3);
            if (f.n == 0) throw new IOException("invalid tag");
            switch ((int)(tag & 7)) {
                case 0: f.value = varint(data, pos); break;
                case 1: pos[0] += 8; break;
                case 2:
                    long count;
                    try { count = varint(data, pos); }
                    catch (IOException e) { if (prefix) return out; throw e; }
                    if (count < 0 || count > data.length - pos[0]) {
                        if (prefix && count >= 0) return out;
                        throw new IOException("invalid length");
                    }
                    f.data = Arrays.copyOfRange(data, pos[0], pos[0] + (int)count); pos[0] += (int)count; break;
                case 5: pos[0] += 4; break;
                default: throw new IOException("invalid wire type");
            }
            if (pos[0] > data.length) { if (prefix) break; throw new IOException("truncated"); }
            out.add(f);
        }
        return out;
    }
    private static long varint(byte[] data, int[] pos) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= data.length) throw new IOException("truncated varint");
            int b = data[pos[0]++] & 255; value |= (long)(b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("varint overflow");
    }
    private static long number(List<Field> fs, int n) { for (Field f : fs) if (f.n == n) return f.value; return 0; }
    private static String text(List<Field> fs, int n) {
        for (Field f : fs) if (f.n == n && f.data != null) return new String(f.data, StandardCharsets.UTF_8);
        return "";
    }
    public static String read(InputStream input) {
        if (input == null) return "tombstone=unavailable";
        boolean truncated = false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (bytes.size() < MAX_BYTES + 1) {
                int n = input.read(chunk, 0, Math.min(chunk.length, MAX_BYTES + 1 - bytes.size()));
                if (n <= 0) break;
                bytes.write(chunk, 0, n);
            }
            byte[] data = bytes.toByteArray();
            truncated = data.length > MAX_BYTES;
            if (truncated) data = Arrays.copyOf(data, MAX_BYTES);
            List<Field> root = fields(data, truncated); long tid = number(root, 6);
            if (tid <= 0 || number(root, 5) <= 0) return truncated ? "tombstone=too_large" : "tombstone=unreadable";
            StringBuilder out = new StringBuilder("tombstone=" + (truncated ? "partial" : "available") + " pid=" + number(root, 5) + " tid=" + tid + " arch=" + number(root, 1) + "\n");
            for (Field f : root) {
                if (f.n == 10 && f.data != null) {
                    List<Field> signal = fields(f.data);
                    out.append("signal=").append(number(signal, 1)).append(" code=").append(number(signal, 3)).append('\n');
                } else if (f.n == 15 && f.data != null) {
                    Matcher m = SYSCALL.matcher(text(fields(f.data), 1));
                    if (m.find()) out.append("seccomp_syscall=").append(m.group(1)).append('\n');
                }
            }
            for (Field f : root) {
                if (f.n != 16 || f.data == null) continue;
                List<Field> entry = fields(f.data);
                if (number(entry, 1) != tid) continue;
                for (Field value : entry) if (value.n == 2 && value.data != null) {
                    int frames = 0;
                    for (Field frame : fields(value.data)) if (frame.n == 4 && frame.data != null && frames++ < 12) {
                        List<Field> fs = fields(frame.data);
                        String module = new File(text(fs, 6)).getName();
                        if (!module.matches("[A-Za-z0-9_.+\\-]{1,100}")) module = "unknown";
                        out.append("frame module=").append(module).append(" rel_pc=0x").append(Long.toHexString(number(fs, 1)))
                                .append(" function=").append(Scrub.text(text(fs, 4), 160)).append('\n');
                    }
                }
            }
            return Scrub.text(out.toString(), 1800);
        } catch (IOException | RuntimeException e) { return truncated ? "tombstone=too_large" : "tombstone=unreadable"; }
    }
    private NativeCrashTrace() {}
}
