# ZimbriMetrics — Minecraft server monitoring for Prometheus and Grafana

[![build](https://github.com/gzimbric/ZimbriMetrics/actions/workflows/build.yml/badge.svg)](https://github.com/gzimbric/ZimbriMetrics/actions/workflows/build.yml)
[![licence GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-blue.svg)](LICENSE)
[![Paper 26.2+](https://img.shields.io/badge/Paper-26.2%2B-orange.svg)](https://papermc.io)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-red.svg)](https://adoptium.net)

A lightweight **Prometheus exporter plugin for Minecraft [Paper](https://papermc.io) servers** — a
28 KB jar with **zero runtime dependencies**.

Monitor your Minecraft server's **TPS, MSPT tick lag, entity counts, chunk loading, RAM, CPU and
player activity**, and graph it all in **Grafana** — without running a heavyweight monitoring stack
inside your game server.

```
mc_mspt_ms{quantile="p99"} 21.6336
mc_tps{window="1m"} 20.0
mc_players_online 4
mc_world_entities{world="world"} 226
mc_world_entities_by_type{world="world",type="chicken"} 35
```

## Why this exists

Every existing option for getting Minecraft server metrics into Prometheus and Grafana is either
unmaintained or far heavier than the job requires:

| | Latest release | Newest MC supported | Jar |
|---|---|---|---|
| [minecraft-prometheus-exporter](https://github.com/sladkoff/minecraft-prometheus-exporter) | Feb 2025 | pre-26.x | 1.6 MB |
| [UnifiedMetrics](https://github.com/Cubxity/UnifiedMetrics) | Apr 2023 | pre-26.x | 6 MB |
| [mineGrafana](https://github.com/seraphicness/mineGrafana) | Apr 2026 | declares 1.21 | 50 MB |
| **ZimbriMetrics** | — | **26.2** | **28 KB** |

mineGrafana does work on 26.2 despite its declared version, but it runs a full Spring Boot reactive
stack — plus Hibernate, HikariCP, three JDBC drivers and async-profiler — inside your game server
process, and keeps initialising for roughly twenty seconds after the server reports ready.

ZimbriMetrics bundles nothing. The HTTP server is the JDK's own `com.sun.net.httpserver`, and the
Prometheus text format is written directly, so there is no metrics library either. The published jar
contains its own classes and two YAML files, and nothing else.

## Requirements

- Paper **26.2** or newer
- Java **25** or newer

Spigot and Bukkit are not supported: the exporter relies on Paper's `getTickTimes()` and its O(1)
per-world counters, which is precisely what keeps it cheap.

## Installing

1. Drop `ZimbriMetrics-1.0.0.jar` into `plugins/`.
2. Start the server. It serves `http://127.0.0.1:9101/metrics` immediately.
3. Point Prometheus at it.

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["127.0.0.1:9101"]
```

Or, if the server is behind NAT and Prometheus is elsewhere, scrape locally with
[Grafana Alloy](https://grafana.com/docs/alloy/) and push outward:

```river
prometheus.scrape "minecraft" {
  targets = [
    {
      __address__ = "127.0.0.1:9101",
      instance    = "mcbox",
      job         = "minecraft",
    },
  ]
  forward_to      = [prometheus.remote_write.central.receiver]
  scrape_interval = "30s"
}

prometheus.remote_write "central" {
  endpoint {
    url = "http://prometheus.example:9090/api/v1/write"
  }
}
```

### Running in Docker

**Set `bind-address` to `0.0.0.0`, not `127.0.0.1`.** Docker's port publishing connects to the
container's IP address, not its loopback, so binding to loopback *inside* the container makes the
port unreachable even with a correct `-p` mapping. Restrict exposure on the publish side instead:

```yaml
ports:
  - "127.0.0.1:9101:9101"
```

That is what actually limits the endpoint to the host.

## Configuration

`plugins/ZimbriMetrics/config.yml`:

```yaml
server-id: "mcbox"          # value of the `server` label on every metric
bind-address: "0.0.0.0"
port: 9101
path: "/metrics"

collection-interval-ticks: 100   # 100 ticks = 5s

per-world: true

entity-types:
  enabled: true
  interval-ticks: 600
```

Set `server-id` to whatever identifies this server elsewhere in your stack. If you also ship logs to
Loki, matching it to the Loki `host` label lets you correlate metrics and logs on one dashboard.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/zmetrics status` | `zimbrimetrics.admin` | Current tick, player and collection figures |
| `/zmetrics reload` | `zimbrimetrics.admin` | Re-read config and rebind the HTTP server |

Aliased to `/zm`. Both work from the console and over RCON.

## Metrics

Every series carries a `server` label.

### Tick timing

| Metric | Labels | Description |
|---|---|---|
| `mc_mspt_ms` | `quantile` = avg, min, max, p50, p95, p99 | Milliseconds per tick |
| `mc_tps` | `window` = 1m, 5m, 15m | Ticks per second |
| `mc_tick_samples` | | Ticks in the current window |

One tick is 50 ms. TPS is capped at 20 and so cannot show headroom — `mc_mspt_ms` is the honest
signal, and the percentiles are exact rather than bucketed (see [Design](#design)).

### Server and players

| Metric | Labels | Description |
|---|---|---|
| `mc_players_online` | | Connected players |
| `mc_players_max` | | Configured slots |
| `mc_plugins` | | Loaded plugins |
| `mc_player_ping_avg_ms` | | Mean player ping |
| `mc_player_ping_max_ms` | | Worst player ping |
| `mc_events_total` | `event` = login, join, quit, chat, death | Monotonic event counters |

### Worlds

| Metric | Labels | Description |
|---|---|---|
| `mc_world_entities` | `world` | Entities per world |
| `mc_world_tile_entities` | `world` | Tile entities per world |
| `mc_world_chunks` | `world` | Loaded chunks per world |
| `mc_world_players` | `world` | Players per world |
| `mc_world_entities_by_type` | `world`, `type` | Entities broken down by type |

### JVM and process

| Metric | Labels | Description |
|---|---|---|
| `mc_jvm_memory_bytes_used` | `area` = heap, nonheap | Memory in use |
| `mc_jvm_memory_bytes_committed` | `area` | Memory committed by the OS |
| `mc_jvm_memory_bytes_max` | `area` | Ceiling, `-1` when unbounded |
| `mc_jvm_memory_bytes_init` | `area` | Requested at startup |
| `mc_jvm_threads_current` | | Live threads |
| `mc_jvm_threads_daemon` | | Live daemon threads |
| `mc_jvm_threads_peak` | | Peak since start |
| `mc_jvm_threads_started_total` | | Threads started (counter) |
| `mc_jvm_classes_loaded` | | Classes loaded |
| `mc_jvm_gc_collections_total` | `gc` | GC cycles by collector (counter) |
| `mc_jvm_gc_seconds_total` | `gc` | Time in GC by collector (counter) |
| `mc_process_cpu_load_ratio` | | Server process CPU, 0–1 |
| `mc_system_cpu_load_ratio` | | Whole-host CPU, 0–1 |
| `mc_process_cpu_seconds_total` | | CPU seconds consumed (counter) |
| `mc_process_start_time_seconds` | | Process start, unix seconds |
| `mc_uptime_seconds` | | JVM uptime |
| `mc_collection_duration_seconds` | | Cost of the exporter's own sampling |

## Design

**Nothing blocks the server thread.** Bukkit's API is not thread-safe, so all reads happen inside a
scheduler task on the main thread, which publishes an immutable snapshot to a `volatile` field. The
HTTP handler only serialises the most recent snapshot. A scrape therefore never touches, blocks or
races the tick loop, no matter how often Prometheus polls.

**Percentiles are exact, not estimated.** Paper's `Bukkit.getTickTimes()` hands back the raw per-tick
durations in nanoseconds, so p50/p95/p99 are computed from the real distribution. No histogram
library, no bucket boundaries to tune. A p50 far below the mean is the signature of an idle server
with rare stalls — worth chasing the stall rather than the average.

**Per-world counts are free.** `World.getEntityCount()`, `getTileEntityCount()`, `getChunkCount()`
and `getPlayerCount()` are counters Paper already maintains, so reading them is O(1). Sampling costs
roughly 2–3 ms, which the exporter reports as `mc_collection_duration_seconds`.

**One deliberate exception.** `mc_world_entities_by_type` has to walk the entity list, so it runs on
its own slower cadence and can be switched off entirely.

**Events are counters, not gauges.** A join and a quit between two samples would be invisible to a
gauge. The listeners run at `MONITOR` priority with `ignoreCancelled`, so counting never influences
gameplay, and they use Paper's `AsyncChatEvent` rather than the deprecated `AsyncPlayerChatEvent`,
which is not guaranteed to fire on a modern chat pipeline.

### What it deliberately does not do

There is no per-plugin CPU attribution, because doing it properly needs a sampling profiler and that
is most of why the alternatives are so large. Paper already bundles
[spark](https://spark.lucko.me/), which profiles better than a background sampler could, on demand
rather than continuously.

## FAQ

**How do I monitor a Minecraft server with Grafana?**
Install this plugin, point Prometheus at `http://<server>:9101/metrics`, add Prometheus as a Grafana
data source, and build panels on the metrics listed above. The [Installing](#installing) section has
a working scrape config. If your server is at home behind NAT and Grafana lives on a VPS, use the
Grafana Alloy example — it scrapes locally and pushes outward, so nothing needs a port forward.

**My Minecraft server is lagging. How do I find out why?**
Watch `mc_mspt_ms` rather than TPS. A p50 well below the mean with a high p99 means the server is
idle most ticks and stalling occasionally — chase the stall, not the average. From there,
`mc_world_entities_by_type` shows whether a mob or item build-up is responsible, `mc_world_chunks`
shows chunk-loading pressure from players exploring, and `mc_jvm_gc_seconds_total` shows whether
garbage collection is eating the tick budget.

**What is the difference between TPS and MSPT?**
TPS counts ticks per second and is capped at 20, so a healthy server and a server with 60% headroom
both read exactly 20.0. MSPT measures how long each tick actually took, against a 50 ms budget. MSPT
degrades visibly long before TPS moves, which makes it the earlier warning.

**Does it work on Spigot, Bukkit, Purpur or Folia?**
Paper and Paper forks only. The exporter depends on Paper's `getTickTimes()` and its O(1) per-world
counters — the very things that keep it cheap. Purpur and other Paper forks should work; plain
Spigot and Bukkit will not.

**Does it work with Velocity or BungeeCord?**
No. This is a backend server plugin. Run it on each Paper instance and distinguish them with
`server-id`.

**Will it slow my server down?**
Sampling costs roughly 2–3 ms every five seconds, and the plugin reports its own cost as
`mc_collection_duration_seconds` so you can confirm rather than trust. Scrapes never touch the
server thread at all — see [Design](#design).

**Do I need Prometheus, or can I use something else?**
Any scraper that speaks the Prometheus text format works, including Grafana Alloy, VictoriaMetrics
and OpenTelemetry Collector. The endpoint is plain HTTP with no authentication, so keep it bound to
loopback or behind a firewall.

## Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn clean package
```

The jar lands in `target/`. `paper-api` is a `provided` dependency and is never bundled.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

`paper-api` is itself GPL-3.0 with no linking exception, so a plugin compiled against it is a
derivative work; GPL-3.0 is the licence that is unambiguously compatible with it.

## Acknowledgements

No code from any other project is included here. Two are worth naming anyway:

- [UnifiedMetrics](https://github.com/Cubxity/UnifiedMetrics) (LGPL-3.0) — its collectors were read
  to decide *which* JVM, process and event metrics were worth covering.
- [mineGrafana](https://github.com/seraphicness/mineGrafana) (MIT) — the prior art this replaced.

Metric naming follows the conventions established by Prometheus'
[client_java](https://github.com/prometheus/client_java), which is also where UnifiedMetrics' names
originate.

## Author

Gabe Zimbric ([@gzimbric](https://github.com/gzimbric))
