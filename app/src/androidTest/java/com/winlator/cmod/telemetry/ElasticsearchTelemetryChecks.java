package com.winlator.cmod.telemetry;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** Real uploader checks against a loopback ES stand-in, plus an explicitly requested live smoke test. */
public final class ElasticsearchTelemetryChecks {
    private final Instrumentation driver;
    private final Context context;
    private final StringBuilder report = new StringBuilder();
    private static final String TEST_KEY = "aWQ6dGVzdA==";

    private ElasticsearchTelemetryChecks(Instrumentation driver) { this.driver = driver; context = driver.getTargetContext(); }

    public static String run(Instrumentation driver, boolean live) throws Exception {
        ElasticsearchTelemetryChecks checks = new ElasticsearchTelemetryChecks(driver);
        checks.configuration();
        checks.protocol();
        checks.schema();
        checks.transport();
        checks.silentFailures();
        if (live) checks.live();
        return checks.report.toString();
    }

    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(report + "FAIL " + message);
        report.append("PASS ").append(message).append('\n');
    }

    private void configuration() throws Exception {
        JSONObject registry = new JSONObject(FileUtils.readString(context, "registry/telemetry.json"));
        TelemetryConfig config = TelemetryConfig.fromJson(registry);
        check(registry.getInt("schema") == 2 && config.usable() && config.host.startsWith("https://") && !config.host.endsWith("/"),
                "bundled registry supplies the Elasticsearch host and public ingest key");
        check(!TelemetryConfig.defaults().usable(), "no destination or credential is hardcoded in the uploader");
        check(!TelemetryConfig.fromJson(new JSONObject().put("host", config.host)).usable(), "an incomplete credential pair disables sending");
        check(!TelemetryConfig.fromJson(new JSONObject(registry.toString()).put("enabled", false)).usable(), "registry kill switch disables sending");
        for (String bad : new String[]{"http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com?key=x", "https://example.com#fragment"}) {
            check(TelemetryConfig.normalizeHost(bad, false).isEmpty(), "unsafe or non-base host is rejected");
        }
        check(TelemetryConfig.normalizeHost("http://127.0.0.1:8787", true).equals("http://127.0.0.1:8787"), "debug loopback can host a mock Elasticsearch receiver");
    }

    private void protocol() throws Exception {
        String[] datasets = {"skynet.session", "skynet.funnel", "skynet.game_session", "skynet.error"};
        List<String> docs = new ArrayList<>();
        for (String dataset : datasets) docs.add(new JSONObject().put("event.dataset", dataset).put("event.id", UUID.randomUUID().toString()).toString());
        String body = ElasticsearchBulk.body(docs);
        String[] lines = body.split("\n");
        check(lines.length == 8 && body.endsWith("\n"), "bulk wire format alternates create metadata and documents with a trailing newline");
        for (int i = 0; i < datasets.length; i++) {
            JSONObject create = new JSONObject(lines[i * 2]).getJSONObject("create");
            check(create.getString("_index").equals("logs-" + datasets[i] + "-default")
                    && create.getString("_id").equals(new JSONObject(docs.get(i)).getString("event.id")), "dataset routes to its existing stream with a stable document ID");
        }
        check(Arrays.equals(ElasticsearchBulk.outcomes(200, reply(201, 409, 429, 400), 4), new int[]{0, 0, 1, 2}),
                "HTTP 200 bulk responses distinguish accepted, duplicate, transient and permanent results");
        check(ElasticsearchBulk.outcomes(200, "not json", 1)[0] == ElasticsearchBulk.RETRY
                && ElasticsearchBulk.outcomes(200, reply(201), 2)[0] == ElasticsearchBulk.RETRY,
                "malformed or incomplete responses cannot silently lose events");
        check(ElasticsearchBulk.outcomes(500, "", 1)[0] == ElasticsearchBulk.RETRY
                && ElasticsearchBulk.outcomes(403, "", 1)[0] == ElasticsearchBulk.DROP,
                "temporary HTTP failures retry while authorization failures are dropped");
    }

    private void schema() throws Exception {
        TelemetrySchema schema = TelemetrySchema.load(context);
        JSONObject raw = new JSONObject().put("event.dataset", "skynet.error").put("event.action", "linux_session_start")
                .put("skynet.error.stage", "linux_session_start").put("unknown", "dropped")
                .put("skynet.linux.seconds_running", 900000).put("skynet.linux.logs", "open /tmp/private/file by test@example.org\n" + "l".repeat(7000));
        JSONObject clean = schema.clean(raw);
        check(!clean.has("unknown") && clean.getLong("skynet.linux.seconds_running") == 604800, "schema drops unknown fields and clamps numeric values before ES ingestion");
        check(clean.getString("skynet.linux.logs").length() == 6000 && clean.getString("skynet.linux.logs").startsWith("open <path> by <email>\n"),
                "schema scrubs and bounds diagnostic text on the phone");
        check(clean.getString("skynet.error.stage").equals("linux_session_start"), "Linux error stages remain valid");
        check(!schema.hasRequired(clean), "malformed events missing required identity fields are discarded");
    }

    @SuppressWarnings("unchecked")
    private List<Telemetry.Event> send(TelemetryConfig config, List<Telemetry.Event> events) throws Exception {
        Method method = Uploader.class.getDeclaredMethod("send", Context.class, TelemetryConfig.class, List.class);
        method.setAccessible(true);
        return (List<Telemetry.Event>) method.invoke(null, context, config, events);
    }

    private static Telemetry.Event event(String dataset, String action) {
        return new Telemetry.Event(dataset, action, null, new Object[]{
                "user.id", UUID.randomUUID().toString(), "device.manufacturer", "TelemetryTest",
                "device.model.identifier", "android-direct-es-check", "service.version", "direct-es-test",
                "skynet.renderer.diagnostics", "stage=ingest_probe vk_result=0 " + "d".repeat(700),
                "skynet.linux.logs", "Synthetic Android direct Elasticsearch test\n".repeat(70),
                "skynet.linux.setup", "synthetic direct ingestion check", "skynet.linux.seconds_running", 40,
        });
    }

    private static TelemetryConfig mockConfig(int port) throws Exception {
        return TelemetryConfig.fromJson(new JSONObject().put("host", "https://example.invalid").put("apiKey", TEST_KEY))
                .withDebugHost("http://127.0.0.1:" + port);
    }

    private void transport() throws Exception {
        try (Receiver receiver = new Receiver(new Reply(200, reply(201, 409, 429, 400)), new Reply(200, reply(201)))) {
            List<Telemetry.Event> events = Arrays.asList(event("skynet.session", "linux_renderer"), event("skynet.error", "launch"),
                    event("skynet.session", "linux_renderer"), event("skynet.error", "launch"));
            List<Telemetry.Event> retry = send(mockConfig(receiver.port()), events);
            check(retry.size() == 1 && retry.get(0) == events.get(2), "real HTTP uploader retries only the transient bulk item");
            String id = events.get(2).id;
            check(send(mockConfig(receiver.port()), retry).isEmpty() && id.equals(events.get(2).id), "retry preserves the event ID for Elasticsearch deduplication");
            check(receiver.paths.equals(Arrays.asList("/_bulk", "/_bulk")) && receiver.validHeaders,
                    "real requests use gzip bulk NDJSON and Authorization ApiKey");
            check(receiver.documents.get(0).size() == 4 && receiver.documents.get(1).get(0).getString("event.id").equals(id),
                    "only the failed document is sent again");
            check(receiver.failure == null, "loopback receiver completed without protocol errors");
        }
        try (Receiver receiver = new Receiver(new Reply(413, "{}"), new Reply(200, reply(201)), new Reply(200, reply(201)))) {
            check(send(mockConfig(receiver.port()), Arrays.asList(event("skynet.session", "linux_renderer"), event("skynet.error", "launch"))).isEmpty()
                    && receiver.documents.size() == 3, "oversized batches split without losing individual events");
        }
        try (Receiver receiver = new Receiver(new Reply(403, "{}"), new Reply(200, "broken"))) {
            List<Telemetry.Event> events = Collections.singletonList(event("skynet.session", "linux_renderer"));
            check(send(mockConfig(receiver.port()), events).isEmpty(), "authorization failures are silently discarded");
            check(send(mockConfig(receiver.port()), events).size() == 1, "malformed response exceptions stay inside the uploader");
        }
        try (Receiver receiver = new Receiver(new Reply(200, reply(201)))) {
            Telemetry.Event bad = new Telemetry.Event("skynet.error", "launch", null, new Object[]{new Object() {
                @Override public String toString() { throw new IllegalStateException("injected serialization failure"); }
            }, "bad"});
            check(send(mockConfig(receiver.port()), Arrays.asList(bad, event("skynet.session", "linux_renderer"))).isEmpty()
                    && receiver.documents.get(0).size() == 1, "serialization exceptions drop only the malformed event and do not block later events");
        }
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = closed.getLocalPort();
            closed.close();
            check(send(mockConfig(port), Collections.singletonList(event("skynet.session", "linux_renderer"))).size() == 1,
                    "connection failures return a bounded retry instead of throwing into the app");
        }
    }

    private void silentFailures() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        Uploader.background(() -> { throw new IllegalStateException("injected background failure"); });
        Uploader.background(ran::countDown);
        check(ran.await(10, TimeUnit.SECONDS), "background exceptions are caught silently and the worker continues");
        Field enabled = Telemetry.class.getDeclaredField("enabled"); enabled.setAccessible(true);
        boolean previous = enabled.getBoolean(null);
        try {
            driver.runOnMainSync(() -> {
                Telemetry.init(null);
                Telemetry.setEnabled(null, true);
                Telemetry.asked(null);
                Telemetry.event(null, null, (String) null);
                Telemetry.flushNow(1);
            });
            check(true, "telemetry startup, settings and caller-facing methods contain configuration exceptions on the UI thread");
        } finally { enabled.setBoolean(null, previous); }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Uploader.background(() -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        check(entered.await(10, TimeUnit.SECONDS), "background worker can be stalled for the nonblocking check");
        long[] elapsed = new long[1];
        try {
            driver.runOnMainSync(() -> {
                long start = SystemClock.elapsedRealtime();
                Telemetry.event("invalid", "synthetic_nonblocking_check");
                elapsed[0] = SystemClock.elapsedRealtime() - start;
            });
        } finally { release.countDown(); }
        check(elapsed[0] < 250, "event submission does not wait for a stalled telemetry worker");
    }

    private void live() throws Exception {
        TelemetryConfig config = TelemetryConfig.load(context, "");
        check(config.usable(), "live registry configuration is usable");
        List<Telemetry.Event> events = Arrays.asList(event("skynet.session", "linux_renderer"), event("skynet.funnel", "game_launched"),
                event("skynet.game_session", "session_ended"), event("skynet.error", "launch"));
        check(send(config, events).isEmpty(), "live app uploader has no retryable failures");
        // Independently checking these IDs in ES distinguishes accepted from permanent failures.
        for (Telemetry.Event event : events) report.append("LIVE_EVENT ").append(event.dataset).append(' ').append(event.id).append('\n');
    }

    private static String reply(int... statuses) throws Exception {
        JSONArray items = new JSONArray();
        for (int status : statuses) items.put(new JSONObject().put("create", new JSONObject().put("status", status)));
        return new JSONObject().put("items", items).toString();
    }

    private static final class Reply {
        final int status; final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }

    private static final class Receiver implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        final List<List<JSONObject>> documents = Collections.synchronizedList(new ArrayList<>());
        volatile Exception failure;
        volatile boolean validHeaders = true, closing;
        Receiver(Reply... replies) throws Exception {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(15000);
            thread = new Thread(() -> {
                try {
                    for (Reply reply : replies) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(10000);
                            InputStream input = new BufferedInputStream(socket.getInputStream());
                            String start = line(input);
                            paths.add(start.split(" ")[1]);
                            Map<String, String> headers = new HashMap<>();
                            String line;
                            while (!(line = line(input)).isEmpty()) {
                                int colon = line.indexOf(':');
                                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                            }
                            validHeaders &= ("ApiKey " + TEST_KEY).equals(headers.get("authorization"))
                                    && "gzip".equals(headers.get("content-encoding"))
                                    && headers.getOrDefault("content-type", "").startsWith("application/x-ndjson");
                            byte[] zipped = new byte[Integer.parseInt(headers.get("content-length"))];
                            new DataInputStream(input).readFully(zipped);
                            ByteArrayOutputStream plain = new ByteArrayOutputStream();
                            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
                                byte[] buffer = new byte[4096]; int n;
                                while ((n = gzip.read(buffer)) != -1) plain.write(buffer, 0, n);
                            }
                            String body = plain.toString("UTF-8");
                            if (!body.endsWith("\n")) throw new IllegalStateException("Missing trailing newline");
                            String[] lines = body.split("\n");
                            List<JSONObject> batch = new ArrayList<>();
                            for (int i = 0; i < lines.length; i += 2) {
                                JSONObject doc = new JSONObject(lines[i + 1]);
                                JSONObject create = new JSONObject(lines[i]).getJSONObject("create");
                                if (!create.getString("_id").equals(doc.getString("event.id"))) throw new IllegalStateException("ID mismatch");
                                batch.add(doc);
                            }
                            documents.add(batch);
                            byte[] response = reply.body.getBytes(StandardCharsets.UTF_8);
                            socket.getOutputStream().write(("HTTP/1.1 " + reply.status + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                                    + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(response);
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (Exception error) { if (!closing) failure = error; }
            }, "MockElasticsearch");
            thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { closing = true; server.close(); thread.join(2000); }
        private static String line(InputStream input) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); int b;
            while ((b = input.read()) != -1) {
                if (b == '\n') return out.toString("US-ASCII").replace("\r", "");
                out.write(b);
                if (out.size() > 16384) throw new IllegalStateException("Header too large");
            }
            throw new IllegalStateException("Unexpected end of HTTP headers");
        }
    }
}
