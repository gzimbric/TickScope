<img src="assets/icon.png" align="right" width="120" alt="TickScope icon">

# TickScope — Minecraft server monitoring for Prometheus and Grafana

[![latest release](https://img.shields.io/github/v/release/gzimbric/TickScope?label=download&color=brightgreen)](https://github.com/gzimbric/TickScope/releases/latest)
[![build](https://github.com/gzimbric/TickScope/actions/workflows/build.yml/badge.svg)](https://github.com/gzimbric/TickScope/actions/workflows/build.yml)
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

Getting Minecraft server metrics into Grafana usually means running a monitoring framework inside
your game server. TickScope takes the opposite approach: read what Paper already tracks, write it
out in Prometheus' text format, and add nothing else.

There is no metrics library, because the exposition format is a few lines of string building. There
is no embedded web framework, because the JDK already ships an HTTP server. Nothing is shaded, so
the published jar contains its own classes and two YAML files and that is all — 28 KB, and no new
entries in your dependency tree.

## Requirements

- Paper **1.18.2** or newer
- Java **17** or newer

One jar covers every version. It is built against the 1.18.2 API and targets Java 17, which is
Minecraft 1.18's own runtime requirement, so a single build runs unchanged from 1.18.2 through the
current release. Verified on 1.18.2, 1.19.4, 1.20.6, 1.21.4 and 26.2: identical metric families,
identical label values.

Spigot and Bukkit are not supported: the exporter relies on Paper's `getTickTimes()` and its O(1)
per-world counters, which is precisely what keeps it cheap. Purpur works. **Folia does not** — see
the [FAQ](#faq).

## Installing

**[⬇ Download the latest release](https://github.com/gzimbric/TickScope/releases/latest)** —
current version **v1.1.0**, a single 28 KB jar with nothing to install alongside it.

1. Drop the downloaded `TickScope-*.jar` into your server's `plugins/` folder.
2. Start the server. It serves `http://127.0.0.1:9101/metrics` immediately, no configuration needed.
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

`plugins/TickScope/config.yml`:

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
| `/tickscope status` | `tickscope.admin` | Current tick, player and collection figures |
| `/tickscope reload` | `tickscope.admin` | Re-read config and rebind the HTTP server |

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

**`mc_world_entities_by_type` only reports types that are loaded right now.** When the last
zombie unloads, the series stops being emitted rather than dropping to `0`, so
`mc_world_entities_by_type{type="zombie"}` returns *nothing* rather than a zero.

That is deliberate — emitting a permanent zero for every entity type that has ever loaded would
leave hundreds of dead series behind. But it does mean a panel can look broken when it is simply
empty. Ask for an explicit zero if you want the line to stay put:

```promql
mc_world_entities_by_type{type="zombie"} or vector(0)
```

The same applies to `topk()` over this metric: it ranks what is loaded at that moment, so the set
of series legitimately changes as the world does. The other `mc_world_*` metrics are not affected —
worlds are always loaded, so those series are stable.

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
Paper and Purpur, yes. Spigot and Bukkit, no — the exporter depends on Paper's `getTickTimes()`
and its O(1) per-world counters, which is exactly what keeps it cheap.

**Folia is not supported.** Sampling runs on the Bukkit scheduler, which Folia replaces with
regional schedulers, and the plugin does not declare `folia-supported`, so Folia will refuse to
load it rather than misbehave.

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

## Privacy

TickScope makes **no outbound network connections of any kind**. There is no telemetry, no bStats,
no update check, and nothing is sent anywhere. The plugin only *listens* on the port you configure;
your metrics go exactly where you point your own scraper and nowhere else.

The endpoint is unauthenticated by design, so keep it bound to loopback or behind a firewall — the
data includes player names only in so far as ping aggregates, but chunk and entity counts still
describe your server.

## Building

You do not need to build anything — prebuilt jars are attached to every
[release](https://github.com/gzimbric/TickScope/releases). To build it yourself:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn clean package
```

The jar lands in `target/`. `paper-api` is a `provided` dependency and is never bundled. Any JDK 17
or newer will do; the build pins `maven.compiler.release` to 17 so the jar loads on every supported
server, whichever JDK you compile with. Every push is built the same way by
[CI](https://github.com/gzimbric/TickScope/actions), so the released jar is reproducible.

## Releases

| Version | Paper | Notes |
|---|---|---|
| [v1.1.0](https://github.com/gzimbric/TickScope/releases/tag/v1.1.0) | 26.2+ | First public release |

## Feedback and issues

- **Something broken?** [Open a bug report](https://github.com/gzimbric/TickScope/issues/new?template=bug_report.yml).
  The form asks for your Paper, Java and TickScope versions up front, because those
  three resolve most reports on their own.
- **Want a metric that isn't there?** [Open a feature request](https://github.com/gzimbric/TickScope/issues/new?template=feature_request.yml).
  Say what you're trying to diagnose rather than just naming the metric — that usually
  leads to a better answer, and sometimes it turns out to be possible already.
- **Not sure which it is?** [Ask in Discussions](https://github.com/gzimbric/TickScope/discussions).
  Scrape configs, PromQL, and "is this number normal?" all belong there.

Bug reports about Spigot, Bukkit or Folia will be closed — see
[Requirements](#requirements) for why those cannot work.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

`paper-api` is itself GPL-3.0 with no linking exception, so a plugin compiled against it is a
derivative work; GPL-3.0 is the licence that is unambiguously compatible with it.

## Acknowledgements

No code from any other project is included here. Two are worth naming anyway:

- [UnifiedMetrics](https://github.com/Cubxity/UnifiedMetrics) (LGPL-3.0) — its collectors were read
  to decide *which* JVM, process and event metrics were worth covering.
- [mineGrafana](https://github.com/seraphicness/mineGrafana) (MIT) — prior art in this space.

Metric naming follows the conventions established by Prometheus'
[client_java](https://github.com/prometheus/client_java), which is also where UnifiedMetrics' names
originate.

## Author

Gabe Zimbric ([@gzimbric](https://github.com/gzimbric))
