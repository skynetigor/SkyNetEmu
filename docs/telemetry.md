# Telemetry

Telemetry is opt-in (onboarding or Settings > Privacy). The Android app sends events directly to Elasticsearch over HTTPS.

## Registry configuration

The components repository's `registry/telemetry.json` supplies the Elasticsearch base `host` and encoded, public append-only `apiKey`.
A matching copy is bundled at `app/src/main/assets/registry/telemetry.json` for offline startup. Host and key are replaced together;
a partial or malformed remote configuration falls back to the bundled copy. `enabled: false` always disables uploads.

```json
{"schema":2,"enabled":true,"host":"https://your-project.es.region.elastic.cloud","apiKey":"<encoded public ingest key>","batchMax":200,"flushMs":2000,"maxAttempts":3,"sampleSec":5,"gameSessionSampleRate":1.0}
```

Configuration reloads at startup and every six hours while uploading. The host must be an HTTPS base URL without credentials, a path,
query or fragment. Debug builds allow the `telemetry_es_host_override` preference to select an HTTP loopback receiver.
There are no build-time endpoint or credential overrides. Local `es.env` credentials are tooling inputs and are never bundled.

The registry key is intentionally public. Restrict it to `create_doc` on `logs-skynet.*-*`, with no read, update, delete, management,
index creation or automatic mapping permissions. Create the four streams and their mappings separately. Public submission keys can
be used to generate false events and ingestion costs.

## Delivery and failure isolation

- Caller-facing telemetry methods catch exceptions silently. `Telemetry.event(...)` only puts an object in a bounded memory queue
  (1000 events); a full queue drops the newest event. It does no network access, serialization or waiting on the caller's thread.
- A daemon thread at background priority loads configuration, validates/serializes documents and uploads batches of up to 200 events.
  An exception in a background task is caught without stopping later telemetry work. Failed telemetry never blocks game launch.
- Requests use gzip NDJSON, `POST <host>/_bulk`, and `Authorization: ApiKey <apiKey>`. Redirects are disabled. Network timeouts are
  five seconds for connecting/reading/writing and ten seconds overall per call.
- Every document has a `create` action with `_index=logs-<event.dataset>-default` and `_id=event.id`. Retries preserve the ID. Only the
  four telemetry datasets are routed; callers cannot select arbitrary indices.
- Bulk results are handled per document. Successful creates and duplicate IDs (409) are complete. Rate limits (429), timeouts and
  server/network failures retry, up to `maxAttempts` (three). Other item failures are dropped. Malformed replies retry safely.
- HTTP 413 splits a batch into smaller requests; a single oversized event is dropped. Permanent authorization failures are dropped.
- Malformed individual events are skipped so later valid events can still be delivered. At game exit, the caller waits at most 1.5 seconds
  for one background flush attempt. A crash record is sent at the next startup if telemetry is enabled.

```text
POST <host>/_bulk
Content-Type: application/x-ndjson
Content-Encoding: gzip
Authorization: ApiKey <encoded public ingest key>

{"create":{"_index":"logs-skynet.session-default","_id":"<event UUID>"}}
{"@timestamp":"2026-10-18T12:00:00.000Z","event.id":"<same event UUID>","event.dataset":"skynet.session",...}
```

## Event schema

`telemetry/schema/v1.json` is the canonical allow-list, type definitions and limits. Run `python3 docs/telemetry/gen_template.py`
to update both `app/src/main/assets/telemetry-schema.json` and `telemetry/elasticsearch/index-template.json`; `--check` detects drift.
The app drops unknown fields, clamps numbers, bounds text, scrubs paths/quoted names/email and validates required fields before uploading.
Elasticsearch mappings remain non-dynamic. A trusted final ingest pipeline can additionally enforce validation for arbitrary public clients.

Data streams remain `logs-skynet.session-default`, `logs-skynet.funnel-default`, `logs-skynet.game_session-default` and
`logs-skynet.error-default`. Event schema version remains 1; registry transport configuration is version 2.
`event.ingested` is supplied by the Elasticsearch ingest pipeline.

Common fields include `@timestamp`, `event.id`, `event.dataset`, `event.action`, optional `event.outcome`, random install `user.id`,
process `session.id`, service/build version, Android/device/SoC/GPU identity and `skynet.telemetry.schema`.
The install UUID resets when telemetry is disabled and enabled again. No IP address is included in event documents.

| Dataset | Events |
|---|---|
| `skynet.session` | App start, the neutral Linux renderer snapshot and periodic Linux performance samples |
| `skynet.funnel` | Game import and launch steps |
| `skynet.game_session` | Session duration, performance, device conditions and exit summary |
| `skynet.error` | Bounded error messages/stacks, exit reasons and Linux diagnostics |

## Linux renderer diagnostics

With telemetry enabled, Linux sessions send bounded diagnostic excerpts rather than full local log files:

| Field | Limit | Content |
|---|---|---|
| `skynet.renderer.diagnostics` | 2048 characters | GPU/driver identity, external-memory capabilities, last DMA import stage and Vulkan result, buffer geometry, memory requirements/type masks, accepted/rejected update counts, GPU submit/present results and counts |
| `skynet.linux.setup` | 1000 characters | Linux runtime, emulator and launch configuration |
| `skynet.linux.seconds_running` | 0–604800 | Elapsed seconds |
| `skynet.linux.logs` | 6000 characters total | Renderer messages when the debug logcat collector is running, the last 30 lines of game output, and up to 25 lines from each of Steam's five newest text logs |

These fields are allowed on `skynet.session` and `skynet.error`. All text is scrubbed on the phone before it is sent. The renderer
excerpt keeps at most 24 distinct consecutive messages and 3000 characters; it excludes unrelated logcat tags. Release builds still
collect the native numeric snapshot directly, but do not start a logcat collector for telemetry. Full `*_winlator_*.txt` files are kept locally.

An import rejection sends one immediate `skynet.error` event per Linux session (`event.action=launch`,
`error.type=linux_frame_import_failed`). It reads log tails on a background thread. At 40 seconds, a neutral
`skynet.session` / `linux_renderer` event captures diagnostics even if the game is sending frames; the existing zero-frame watchdog also
checks at 40 and 120 seconds. Rejected DMA imports do not increase the game/HUD frame count. Accepted imports or successful Android
presentation calls alone do not establish that the game's pixels are visible.

### Linux performance diagnosis

Opted-in Linux sessions also send `skynet.session` / `linux_performance` after 10 seconds, then every 30 seconds
with fixed delay. Sampling runs on a background-priority worker, independently of the HUD. It does not wait for a clean exit
and does not upload game log tails on every sample. Turning telemetry off suppresses collection and events.

`skynet.performance.diagnostics` is a numeric-only, versioned text snapshot, bounded to 4096 characters. Missing or denied
readings are `-1`, including the first CPU utilization reading. It contains:

- Per-core utilization from `/proc/stat`, current frequency, scaling frequency cap and hardware maximum in kHz (up to 16 cores).
- Adreno GPU current/capped frequency in Hz where accessible, and GPU utilization. A missing GPU reading is not zero utilization.
- Whole-device available RAM and total/free swap in kB; cumulative swap-in, swap-out and major-fault counters from `/proc/vmstat`.
- The Android app process's RSS/swap in kB, separately from device memory. This excludes guest processes and is not the game's RSS.
- CPU, memory and I/O PSI `some`/`full` cumulative stall totals in microseconds where accessible. These are system-wide;
  consecutive deltas show pressure during the interval, not proof that this game caused it.
- Current CPU/battery temperatures, Android thermal status, charging and power-saving state, plus aggregate CPU utilization.
- Cumulative counted game frames and the age of the cached native renderer snapshot in milliseconds. Renderer counters are refreshed
  by frame updates at most every five seconds, so check freshness before calculating rates. Counted frames mean accepted updates,
  not confirmed scanout or unique game frames. Direct Linux frame counting works with the HUD hidden.

Each performance event also carries `skynet.linux.setup`, elapsed seconds and the cached `skynet.renderer.diagnostics`.
Setup includes requested Linux/Android renderer driver IDs, emulator build/preset, CPU affinity selection, Vulkan output mode,
GL driver, sysmem setting, requested renderer present mode and game resolution. Requested IDs are configuration evidence;
they do not prove which game-side driver or emulator defaults actually loaded.

Native snapshots include the actual renderer Vulkan driver ID/name/info, custom-driver-handle status, output dimensions and
chosen present mode. Host timing counters (`*_calls`, cumulative `*_us`, lifetime `*_max_us`) cover render-frame fence waits,
swapchain acquire, queue submit, queue present and DMA update/import including its mutex wait. They start at the first diagnostic
snapshot and use a monotonic clock. These measure CPU wall time inside those operations, **not GPU execution time**; small
submit times do not rule out a GPU bottleneck. Fence counters exclude one-shot uploads, swapchain recreation and texture retirement.
Timings keep fixed-size counters with no per-frame log messages or allocations.

For comparisons, align scene, game resolution, emulator configuration and both driver selections. Compare counter deltas over
the elapsed interval with clocks, thermal state and memory-pressure deltas. A slower frame arrival rate without long renderer
call times directs further profiling toward the game/emulator or GPU producer; it does not by itself distinguish CPU from GPU work.

The generated Elasticsearch template adds `skynet.performance.diagnostics`. Apply that mapping to existing data streams as well
as the template before relying on indexed searches of the new field. Existing `dynamic: false` streams can retain unknown fields
in `_source` without indexing them. This source change does not deploy backend mappings or install a phone build.

## Verification

`JAVA_HOME=<JDK17> python3 tools/telemetry-test/run.py` checks text privacy/bounds, registry configuration and generated schema consistency.
Build the debug app/test APKs, install both and run:

```bash
adb shell am instrument -w -e elasticsearchTelemetry 1 com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

The checks exercise real gzip bulk HTTP requests, all four routing targets, partial failures, duplicate IDs, malformed responses,
413 splitting, network/serialization exceptions, background-worker survival and nonblocking UI submission. Add `-e liveEs 1` to create
four marked test events in the configured Elasticsearch project; retrieve the printed IDs to verify acceptance independently.
Synthetic events use `device.manufacturer=TelemetryTest` and can be excluded from device analysis.

`python3 tools/telemetry-mock/receive.py --item-statuses 201,409,429,400` starts a standalone loopback bulk receiver. It never logs credentials.
