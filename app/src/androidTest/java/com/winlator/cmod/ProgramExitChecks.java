package com.winlator.cmod;

import android.app.Instrumentation;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.ProgramOutput;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real child-process failures; does not start a container or change user files. */
public final class ProgramExitChecks {
    private final Instrumentation instrumentation;
    private final StringBuilder report = new StringBuilder();
    public ProgramExitChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        report.append("PASS ").append(description).append('\n');
    }
    private int run(String script, ProgramOutput output) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(-999);
        int pid = ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", script}, new String[0],
                instrumentation.getTargetContext().getCacheDir(), result -> {
                    status.set(result);
                    done.countDown();
                }, output::append);
        check(pid > 0, "test process starts");
        check(done.await(10, TimeUnit.SECONDS), "test process exit is delivered");
        return status.get();
    }
    public String run() throws Exception {
        ProgramOutput error = new ProgramOutput();
        int code = run("echo startup; echo 'missing test runtime library' >&2; exit 7", error);
        check(code == 7 && error.hasFailure(code), "nonzero exit is a failure");
        check(error.lastOutput(12).contains("missing test runtime library"), "immediate stderr arrives before exit callback");
        check(error.exitMessage(code, 1).contains("code 7"), "dialog explains the exit code");

        ProgramOutput normal = new ProgramOutput();
        code = run("echo '0090:err:nsi:poll_events bind failed, errno 13.' >&2; echo 'wine-cachyos is a testing version'; exit 0", normal);
        check(code == 0 && !normal.hasFailure(code), "ordinary Wine err/warning output does not turn a clean exit into a failure");

        ProgramOutput fatal = new ProgramOutput();
        code = run("echo 'Pointer tag for 0x1234 was truncated' >&2; exit 0", fatal);
        check(code == 0 && fatal.hasFailure(code), "fatal child error survives a successful launcher exit");
        check(fatal.exitMessage(code, 1).contains("fatal error"), "successful launcher status is not described as a nonzero exit");
        for (int i = 0; i < 150; i++) fatal.append("later diagnostic " + i);
        check(fatal.lastOutput(12).contains("Pointer tag") && fatal.lastOutput(12).split("\n").length == 12,
                "fatal diagnostic survives a bounded noisy output tail");

        ProgramOutput signal = new ProgramOutput();
        code = run("kill -TERM $$", signal);
        check(code == 143 && signal.exitMessage(code, 1).contains("signal 15"), "signal exit is explained");

        ProgramOutput missing = new ProgramOutput();
        int pid = ProcessHelper.exec(new String[]{"/does-not-exist/skynet-test-program"}, new String[0],
                instrumentation.getTargetContext().getCacheDir(), null, missing::append);
        check(pid == -1 && missing.lastOutput(12).contains("Unable to start program"), "spawn failure keeps the OS diagnostic");
        missing.failedToStart("missing runtime");
        check(missing.hasFailure(-1) && missing.exitMessage(-1, 0).contains("could not be started"), "spawn failures have a readable explanation");

        ProgramOutput cleaned = new ProgramOutput();
        cleaned.append("\u001B[31mBOX64\u001B[0m diagnostic");
        check(cleaned.lastOutput(1).equals("BOX64 diagnostic"), "terminal formatting is removed from dialog output");
        cleaned.append("x".repeat(100000));
        check(cleaned.lastOutput(1).length() < 2100, "single output line is bounded");

        ProgramOutput first = new ProgramOutput(), second = new ProgramOutput();
        CountDownLatch both = new CountDownLatch(2);
        ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", "echo FIRST; sleep 0.1; echo FIRST_END >&2"}, new String[0],
                instrumentation.getTargetContext().getCacheDir(), result -> both.countDown(), first::append);
        ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", "echo SECOND; echo SECOND_END >&2"}, new String[0],
                instrumentation.getTargetContext().getCacheDir(), result -> both.countDown(), second::append);
        check(both.await(10, TimeUnit.SECONDS), "concurrent process exits are delivered");
        check(first.lastOutput(12).contains("FIRST_END") && !first.lastOutput(12).contains("SECOND")
                && second.lastOutput(12).contains("SECOND_END") && !second.lastOutput(12).contains("FIRST"),
                "diagnostics belong only to their own process");
        return report.toString();
    }
}
