# Telemetry

Opt-in. Off until the user says yes (onboarding question, Settings > Privacy). When it is on, the app sends usage, error and performance
events to an HTTPS endpoint you run.

## Delivery rules

- `Telemetry.event(...)` only puts a small object into a memory buffer (1000 events, a full buffer drops the new event). No I/O, no JSON,
  nothing the caller waits for.
- One daemon thread at background priority sends the buffer. It sleeps while the buffer is empty; the first event schedules a flush 2 s later.
  A flush sends everything waiting, in requests of at most 200 events.
- A failed request is retried silently at the next flush (2 s later). After 3 failed attempts the events are dropped, silently. Offline
  counts as failed attempts.
- At the end of a game the app makes one bounded attempt (1.5 s) so the session summary is not lost when the process exits.
- A crash writes one small file that is sent at the next start. Nothing else is stored on the phone.

## Request

```
POST <endpoint>
Content-Type: application/x-ndjson
Content-Encoding: gzip
X-SkyNet-Key: <public key from the build, may be empty>

{"@timestamp":"2026-10-14T12:00:00.000Z","event.id":"...","event.dataset":"skynet.session",...}
{...}
```

Reply any 2xx for accepted. Anything else is a failed attempt. `event.id` is a UUID made on the phone, the same on a retry: use it as the
document `_id` so a retry after a lost reply does not duplicate. The key is in the APK, so it only keeps casual noise out: rate-limit
per `user.id` and per IP (do not store the IP) and drop fields you do not know.

## Common fields (every event)

`@timestamp`, `event.id`, `event.dataset`, `event.action`, `event.outcome` (`success` / `failure`), `user.id` (random install UUID, reset when
the user turns telemetry off and on), `session.id` (per app process), `service.name`=`skynetemu`, `service.version`,
`skynet.build.type`, `host.os.name`, `host.os.version`, `skynet.android.sdk`, `device.manufacturer`, `device.model.identifier`,
`skynet.soc.family`, `skynet.soc.model`, `skynet.gpu.renderer`, `skynet.memory.total_bytes`, `skynet.telemetry.schema` (1).

## Datasets

| `event.dataset` | one per | fields |
|---|---|---|
| `skynet.session` | app start; once after 40 s of a Linux session | the common fields; `linux_renderer` snapshots also carry the Linux diagnostic fields below and omit `event.outcome` |
| `skynet.funnel` | step of adding a game | `event.action`: `add_game_started`, `exe_picked`, `game_recognised`, `config_chosen`, `container_created`, `shortcut_created`, `game_launched`; `skynet.game.id`, `skynet.game.version`, `skynet.config.id` |
| `skynet.game_session` | play session | `skynet.game.id`, `skynet.game.version`, `skynet.config.id`, `skynet.wine`, `skynet.emulator`, `skynet.driver`, `skynet.file_cache`, `skynet.storage`, `skynet.time_to_window_ms`, `skynet.time_to_renderer_ms`, `event.duration` (ns), `skynet.fps.avg`, `skynet.fps.p1_low`, `skynet.frametime.p99_ms`, `skynet.cpu.avg_pct`, `skynet.temp.cpu_max_c`, `skynet.temp.battery_max_c`, `skynet.thermal.max_status`, `skynet.memory.peak_used_bytes`, `skynet.battery.drain_pct`, `skynet.exit.code`, `skynet.exit.kind` |
| `skynet.error` | failure | `error.type`, `error.message`, `error.stack_trace`, `skynet.error.stage` |

Never sent: file paths, game folder or exe names, shortcut or container names, text typed by the user, Wine logs, the IP address. Messages are
scrubbed of paths, quoted text and e-mail addresses before they leave the phone. Game ids are the registry's, or `unknown`.

## Linux renderer diagnostics

With telemetry enabled, Linux sessions send bounded diagnostic excerpts rather than full local log files:

| Field | Limit | Content |
|---|---|---|
| `skynet.renderer.diagnostics` | 2048 characters | GPU/driver identity, external-memory capabilities, last DMA import stage and Vulkan result, buffer geometry, memory requirements/type masks, accepted/rejected update counts, GPU submit/present results and counts |
| `skynet.linux.setup` | 1000 characters | Linux runtime, emulator and launch configuration |
| `skynet.linux.seconds_running` | 0–604800 | Elapsed seconds |
| `skynet.linux.logs` | 6000 characters total | Renderer messages when the debug logcat collector is running, the last 30 lines of game output, and up to 25 lines from each of Steam's five newest text logs |

These fields are allowed on `skynet.session` and `skynet.error`. All text is scrubbed on the phone and again by ingestion. The renderer
excerpt keeps at most 24 distinct consecutive messages and 3000 characters; it excludes unrelated logcat tags. Release builds still
collect the native numeric snapshot directly, but do not start a logcat collector for telemetry. Full `*_winlator_*.txt` files are kept locally.

An import rejection sends one immediate `skynet.error` event per Linux session (`event.action=launch`,
`error.type=linux_frame_import_failed`). It reads log tails on a background thread. At 40 seconds, a neutral
`skynet.session` / `linux_renderer` event captures diagnostics even if the game is sending frames; the existing zero-frame watchdog also
checks at 40 and 120 seconds. Rejected DMA imports do not increase the game/HUD frame count. Accepted imports or successful Android
presentation calls alone do not establish that the game's pixels are visible.

The ingest service's schema has a different structure from the app schema here. Apply [ingest-renderer-logs.patch](telemetry/ingest-renderer-logs.patch)
to the root of the `ubuntu_vm` repository. It updates the service
allow-list, per-event byte limit, privacy filtering, ingest pipeline, template, and existing session/error stream mappings on startup.
The live integration check tags synthetic events with `device.manufacturer=TelemetryTest` so they can be excluded from device analysis.

## Endpoint and switches

The endpoint comes from the registry file `registry/telemetry.json` (kept by the app for 6 hours, with a copy bundled in the app), or from the
build (`-PtelemetryEndpoint=... -PtelemetryKey=...`) when the registry names none. `"enabled": false` in the registry stops all sending. With no
endpoint anywhere, nothing is sent even if the user opted in.

```json
{"schema":1,"enabled":true,"endpoint":"https://your-host/v1/events","batchMax":200,"flushMs":2000,"maxAttempts":3,"sampleSec":5,"gameSessionSampleRate":1.0}
```

Debug builds also accept the preference `telemetry_endpoint_override` (a test receiver, plain http allowed).

## Elasticsearch

Data streams, one per dataset: `logs-skynet.session-default`, `logs-skynet.funnel-default`, `logs-skynet.game_session-default`,
`logs-skynet.error-default`. Fields are flat dotted names, which Elasticsearch maps as objects. Index template sketch:

```json
PUT _index_template/logs-skynet
{
  "index_patterns": ["logs-skynet.*-*"],
  "data_stream": {},
  "priority": 200,
  "template": {
    "settings": {"index.lifecycle.name": "skynet-90d"},
    "mappings": {
      "dynamic": "false",
      "properties": {
        "@timestamp": {"type": "date"},
        "event": {"properties": {"id": {"type": "keyword"}, "dataset": {"type": "keyword"}, "action": {"type": "keyword"},
                                 "outcome": {"type": "keyword"}, "duration": {"type": "long"}}},
        "user": {"properties": {"id": {"type": "keyword"}}},
        "session": {"properties": {"id": {"type": "keyword"}}},
        "service": {"properties": {"name": {"type": "keyword"}, "version": {"type": "keyword"}}},
        "host": {"properties": {"os": {"properties": {"name": {"type": "keyword"}, "version": {"type": "keyword"}}}}},
        "device": {"properties": {"manufacturer": {"type": "keyword"}, "model": {"properties": {"identifier": {"type": "keyword"}}}}},
        "error": {"properties": {"type": {"type": "keyword"}, "message": {"type": "text"}, "stack_trace": {"type": "wildcard"}}},
        "skynet": {"properties": {
          "build": {"properties": {"type": {"type": "keyword"}}},
          "android": {"properties": {"sdk": {"type": "integer"}}},
          "soc": {"properties": {"family": {"type": "keyword"}, "model": {"type": "keyword"}}},
          "gpu": {"properties": {"renderer": {"type": "keyword"}}},
          "memory": {"properties": {"total_bytes": {"type": "long"}, "peak_used_bytes": {"type": "long"}}},
          "game": {"properties": {"id": {"type": "keyword"}, "version": {"type": "keyword"}}},
          "config": {"properties": {"id": {"type": "keyword"}}},
          "wine": {"type": "keyword"}, "emulator": {"type": "keyword"}, "driver": {"type": "keyword"},
          "file_cache": {"type": "boolean"}, "storage": {"type": "keyword"},
          "time_to_window_ms": {"type": "long"}, "time_to_renderer_ms": {"type": "long"},
          "fps": {"properties": {"avg": {"type": "float"}, "p1_low": {"type": "float"}}},
          "frametime": {"properties": {"p99_ms": {"type": "float"}}},
          "cpu": {"properties": {"avg_pct": {"type": "float"}}},
          "temp": {"properties": {"cpu_max_c": {"type": "float"}, "battery_max_c": {"type": "float"}}},
          "thermal": {"properties": {"max_status": {"type": "integer"}}},
          "battery": {"properties": {"drain_pct": {"type": "float"}}},
          "exit": {"properties": {"code": {"type": "integer"}, "kind": {"type": "keyword"}}},
          "error": {"properties": {"stage": {"type": "keyword"}}},
          "telemetry": {"properties": {"schema": {"type": "integer"}}}
        }}
      }
    }
  }
}
```

With `dynamic: false` an unexpected field is kept in `_source` but not indexed, so a newer app does not break an older template. ILM example:
a hot phase with rollover, delete after 90 days.

Useful views: FPS and 1 % low by SoC family and config (feeds `preferredBySoc` in the registry), time to window by storage and file cache,
crash and kill rate by app version, funnel drop-off, thermal status by device model.
