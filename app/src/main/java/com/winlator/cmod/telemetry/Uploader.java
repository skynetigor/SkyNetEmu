package com.winlator.cmod.telemetry;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.core.DeviceInfo;
import com.winlator.cmod.core.GPUInformation;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends the events in bulk without ever slowing the caller down.
 *
 * Events wait in a bounded memory buffer (a full buffer drops the newest). One daemon thread at background priority sends them: it
 * is asleep while there is nothing to send, and the first event wakes it for a flush {@code flushMs} later (2 s). A flush sends
 * everything that is waiting, in requests of at most {@code batchMax} events. An event whose request failed is tried again
 * silently at the next flush; after {@code maxAttempts} (3) failed attempts it is dropped. Nothing is shown to the user and nothing
 * is thrown to the caller.
 */
final class Uploader {
    private static final int BUFFER_SIZE = 1000;
    private static final long CONFIG_TTL_MS = 6L * 60 * 60 * 1000;
    private static final MediaType NDJSON = MediaType.parse("application/x-ndjson");

    private static final ArrayBlockingQueue<Telemetry.Event> BUFFER = new ArrayBlockingQueue<>(BUFFER_SIZE);
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    /** Events whose request failed and that still have attempts left; touched only by the uploader's thread. */
    private static final List<Telemetry.Event> PENDING = new ArrayList<>();

    private static final ScheduledThreadPoolExecutor EXECUTOR = executor();
    private static volatile TelemetryConfig config = TelemetryConfig.defaults();
    private static long configLoadedAt;
    private static JSONObject environment;
    private static OkHttpClient client;
    private static SimpleDateFormat timestamps;
    private static volatile TelemetrySchema schema;

    private Uploader() {}

    private static ScheduledThreadPoolExecutor executor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "SkyNet-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        // The thread goes away while there is nothing to do and comes back with the next event.
        executor.setKeepAliveTime(5, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Called when telemetry is turned on: reads the configuration on the uploader's thread, never on the caller's. */
    static void start(Context context) {
        background(() -> loadConfig(context, true));
    }

    /** How often the performance sampler reads the device, in seconds (0: not at all). */
    static int sampleSeconds() {
        return config.sampleSec;
    }

    /** Runs a task on the uploader's thread (it wakes up for it and sleeps again). */
    static void background(Runnable task) {
        try {
            EXECUTOR.execute(() -> {
                try { task.run(); }
                catch (Exception ignored) {}
            });
        }
        catch (RuntimeException ignored) {}
    }

    /** Called from any thread; only queues. */
    static void offer(Telemetry.Event event) {
        if (!BUFFER.offer(event)) return;
        if (SCHEDULED.compareAndSet(false, true)) schedule(config.flushMs);
    }

    /** Telemetry was turned off: forget what is waiting. */
    static void clear(Context context) {
        BUFFER.clear();
        background(() -> {
            PENDING.clear();
            environment = null;
        });
    }

    /**
     * One bounded attempt to send what is waiting, for the moment before the process ends (it waits at most {@code timeoutMs}).
     * The sending itself happens on the uploader's thread because the main thread may not use the network.
     */
    static void flushNow(long timeoutMs) {
        if (!Telemetry.isEnabled()) return;
        try {
            Future<?> done = EXECUTOR.submit(() -> flush(true));
            done.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (Exception ignored) {
            // Timed out or failed: the events are skipped like any others.
        }
    }

    private static void schedule(long delayMs) {
        try {
            EXECUTOR.schedule(() -> flush(false), delayMs, TimeUnit.MILLISECONDS);
        }
        catch (RuntimeException e) {
            SCHEDULED.set(false);
        }
    }

    private static void flush(boolean oneAttemptOnly) {
        if (!oneAttemptOnly) SCHEDULED.set(false);
        try {
            Context context = Telemetry.context();
            if (context == null) return;
            loadConfig(context, false);
            TelemetryConfig c = config;
            if (!Telemetry.isEnabled() || !c.usable()) {
                BUFFER.clear();
                PENDING.clear();
                return;
            }

            List<Telemetry.Event> all = new ArrayList<>(PENDING);
            PENDING.clear();
            BUFFER.drainTo(all);
            List<Telemetry.Event> keep = new ArrayList<>();
            boolean failed = false;
            for (int from = 0; from < all.size(); from += c.batchMax) {
                List<Telemetry.Event> chunk = all.subList(from, Math.min(all.size(), from + c.batchMax));
                if (failed) {
                    // The request before failed: these were not tried, so they keep their attempts.
                    keep.addAll(chunk);
                    continue;
                }
                List<Telemetry.Event> retry = send(context, c, chunk);
                failed = !retry.isEmpty();
                for (Telemetry.Event event : retry) {
                    if (!oneAttemptOnly && ++event.attempts < c.maxAttempts) keep.add(event);
                }
            }
            // Never keep more than the buffer holds: the oldest go first.
            int excess = keep.size() - BUFFER_SIZE;
            PENDING.addAll(excess > 0 ? keep.subList(excess, keep.size()) : keep);
        }
        catch (Exception ignored) {
            // Telemetry never throws into the app.
        }
        if (!oneAttemptOnly && (!PENDING.isEmpty() || !BUFFER.isEmpty()) && SCHEDULED.compareAndSet(false, true)) {
            schedule(config.flushMs);
        }
    }

    private static void loadConfig(Context context, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && configLoadedAt != 0 && now - configLoadedAt < CONFIG_TTL_MS) return;
        try {
            config = TelemetryConfig.load(context, Telemetry.debugEsHost());
            configLoadedAt = now;
        } catch (Exception ignored) {}
    }

    /** Returns only transient failures. Every exception stays on this background path. */
    private static List<Telemetry.Event> send(Context context, TelemetryConfig c, List<Telemetry.Event> events) {
        List<Telemetry.Event> valid = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try {
            if (environment == null) environment = environment(context);
            if (timestamps == null) {
                timestamps = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                timestamps.setTimeZone(TimeZone.getTimeZone("UTC"));
            }
            for (Telemetry.Event event : events) {
                try {
                    String line = json(event, environment, timestamps);
                    if (!schema.hasRequired(new JSONObject(line))) continue;
                    lines.add(line);
                    valid.add(event);
                } catch (Exception ignored) {
                    // A malformed event is discarded without blocking later events.
                }
            }
            if (valid.isEmpty()) return valid;
            return sendDocuments(c, valid, lines);
        }
        catch (Exception e) {
            return new ArrayList<>(events);
        }
    }

    private static List<Telemetry.Event> sendDocuments(TelemetryConfig config, List<Telemetry.Event> events, List<String> lines) {
        try {
            byte[] body = ElasticsearchBulk.body(lines).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream zipped = new ByteArrayOutputStream(body.length / 3 + 64);
            try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) { gzip.write(body); }
            Request request = new Request.Builder().url(config.host + "/_bulk")
                    .header("Content-Encoding", "gzip").header("Authorization", "ApiKey " + config.apiKey)
                    .post(RequestBody.create(zipped.toByteArray(), NDJSON)).build();
            int status;
            String responseBody;
            try (Response response = http().newCall(request).execute()) {
                status = response.code();
                responseBody = response.body() == null ? "" : response.body().string();
            }
            if (status == 413 && events.size() > 1) {
                int middle = events.size() / 2;
                List<Telemetry.Event> retry = sendDocuments(config, events.subList(0, middle), lines.subList(0, middle));
                retry.addAll(sendDocuments(config, events.subList(middle, events.size()), lines.subList(middle, lines.size())));
                return retry;
            }
            int[] outcomes = ElasticsearchBulk.outcomes(status, responseBody, events.size());
            List<Telemetry.Event> retry = new ArrayList<>();
            List<String> accepted = new ArrayList<>();
            for (int i = 0; i < outcomes.length; i++) {
                if (outcomes[i] == ElasticsearchBulk.RETRY) retry.add(events.get(i));
                else if (outcomes[i] == ElasticsearchBulk.ACCEPTED) accepted.add(lines.get(i));
            }
            remember(accepted);
            return retry;
        } catch (Exception ignored) {
            return new ArrayList<>(events);
        }
    }

    private static OkHttpClient http() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
        }
        return client;
    }

    /** The last few documents that were accepted, for the "What is sent" screen. */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();

    private static void remember(List<String> lines) {
        synchronized (RECENT) {
            for (String line : lines) {
                RECENT.addLast(line);
                if (RECENT.size() > 5) RECENT.removeFirst();
            }
        }
    }

    /** The last few documents that were sent (newest last). */
    static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    /** What the next session event would look like, built from the real device; for the "What is sent" screen. Call off the main thread. */
    static String sample(Context context) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Telemetry.Event event = new Telemetry.Event("skynet.session", "app_started", "success", null);
            String id = Telemetry.installId().isEmpty() ? "(made when you turn it on)" : Telemetry.installId();
            return new JSONObject(json(event, environment(context), format)).put("user.id", id).toString(2);
        }
        catch (Exception e) {
            return "";
        }
    }

    private static String json(Telemetry.Event event, JSONObject environment, SimpleDateFormat timestamps) throws JSONException {
        if (event.id == null) event.id = UUID.randomUUID().toString();
        JSONObject doc = new JSONObject();
        doc.put("@timestamp", timestamps.format(new Date(event.time)));
        doc.put("event.id", event.id);
        doc.put("event.dataset", event.dataset);
        doc.put("event.action", event.action);
        doc.put("event.outcome", event.outcome);
        doc.put("user.id", Telemetry.installId());
        doc.put("session.id", Telemetry.SESSION_ID);
        Iterator<String> keys = environment.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            doc.put(key, environment.get(key));
        }
        Object[] f = event.fields;
        for (int i = 0; f != null && i + 1 < f.length; i += 2) {
            String key = String.valueOf(f[i]);
            Object value = f[i + 1];
            if (value == null) continue;
            if (value instanceof String) value = Scrub.field(key, (String) value);
            doc.put(key, value);
        }
        if (schema == null) schema = TelemetrySchema.load(Telemetry.context());
        return schema.clean(doc).toString();
    }

    /** What every event says about the device and the app. Read once, on the uploader's thread. */
    private static JSONObject environment(Context context) throws JSONException {
        JSONObject env = new JSONObject();
        env.put("service.name", "skynetemu");
        env.put("service.version", BuildConfig.VERSION_NAME);
        env.put("skynet.build.type", BuildConfig.DEBUG ? "debug" : "release");
        env.put("host.os.name", "Android");
        env.put("host.os.version", Build.VERSION.RELEASE);
        env.put("skynet.android.sdk", Build.VERSION.SDK_INT);
        env.put("device.manufacturer", Build.MANUFACTURER);
        env.put("device.model.identifier", Build.MODEL);
        try {
            DeviceInfo.Soc soc = DeviceInfo.soc(context);
            if (soc.family != null) env.put("skynet.soc.family", soc.family);
            if (!soc.model.isEmpty()) env.put("skynet.soc.model", soc.model);
        }
        catch (RuntimeException ignored) {}
        try {
            String gpu = GPUInformation.getRenderer(null, context);
            if (gpu != null && !gpu.isEmpty()) env.put("skynet.gpu.renderer", gpu);
        }
        catch (Throwable ignored) {}
        try {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
            env.put("skynet.memory.total_bytes", memory.totalMem);
        }
        catch (RuntimeException ignored) {}
        env.put("skynet.telemetry.schema", 1);
        return env;
    }
}
