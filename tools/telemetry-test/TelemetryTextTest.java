package com.winlator.cmod.telemetry;

public final class TelemetryTextTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String secrets = " /storage/emulated/0/Games/private.exe /root/private/file /tmp/user/file "
                + "C:\\Users\\person\\file person@example.org \"personal title\"";
        String diagnostic = "stage=allocate_image_memory vk_result=-1000072003 stride=5120 " + "x".repeat(700) + secrets;
        String safe = Scrub.field("skynet.renderer.diagnostics", diagnostic);
        check(safe.length() > 700, "renderer details survive the old 300-character limit");
        check(safe.contains("vk_result=-1000072003") && safe.contains("stride=5120"), "API result and geometry survive scrubbing");
        check(!safe.contains("private") && !safe.contains("person") && !safe.contains("@"), "paths, names and email are scrubbed");
        check(Scrub.field("skynet.renderer.diagnostics", "x".repeat(9000)).length() == 2048, "renderer excerpt is bounded");
        check(Scrub.field("skynet.linux.logs", "x".repeat(9000)).length() == 6000, "log excerpt retains 6000 characters");
        check(Scrub.field("skynet.linux.setup", "x".repeat(9000)).length() == 1000, "setup is bounded");
        check(Scrub.field("error.stack_trace", "x".repeat(9000)).length() == 8192, "stack trace is bounded");
        check(Scrub.field("error.message", "x".repeat(9000)).length() == 300, "ordinary messages keep their limit");

        RendererLog log = new RendererLog();
        log.add("OtherTag: unrelated personal data");
        check(log.snapshot().isEmpty(), "unrelated logcat is excluded");
        log.add("12:00 pid Winlator_Renderer: result=-1" + secrets);
        log.add("12:01 pid Winlator_Renderer: result=-1" + secrets);
        check(log.snapshot().split("\n").length == 1, "identical renderer messages are deduplicated");
        check(!log.snapshot().contains("person") && !log.snapshot().contains("private"), "renderer ring is scrubbed before retaining text");
        for (int i = 0; i < 100; i++) log.add("Winlator_Renderer: frame=" + i + " " + "x".repeat(400));
        check(log.snapshot().length() <= 3000, "ring has a total character bound");
        check(log.snapshot().split("\n").length <= 24, "ring has a line-count bound");
        check(log.snapshot().contains("frame=99") && !log.snapshot().contains("frame=0 "), "newest renderer evidence is retained");
        log.clear();
        check(log.snapshot().isEmpty(), "session cleanup removes retained messages");
        System.out.println("PASS telemetry bounds, privacy, renderer filtering and retention");
    }
}
