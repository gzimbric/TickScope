<img src="assets/icon.png" align="right" width="120" alt="TickScope icon">

# TickScope — Minecraft monitoring for Prometheus and Grafana

[![latest release](https://img.shields.io/github/v/release/gzimbric/TickScope?label=download&color=brightgreen)](https://github.com/gzimbric/TickScope/releases/latest)
[![build](https://github.com/gzimbric/TickScope/actions/workflows/build.yml/badge.svg)](https://github.com/gzimbric/TickScope/actions/workflows/build.yml)
[![licence GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-blue.svg)](LICENSE)
[![Paper 1.18.2+](https://img.shields.io/badge/Paper-1.18.2%2B-orange.svg)](https://papermc.io)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-red.svg)](https://adoptium.net)

TickScope is a lightweight Prometheus exporter for Minecraft Paper servers. The dependency-free
34 KB plugin exposes exact MSPT percentiles, TPS, players, per-world entity and chunk counts, JVM
and CPU statistics, and player event counters for dashboards in Grafana.

```text
mc_mspt_ms{quantile="p99"} 21.6336
mc_tps{window="1m"} 20.0
mc_players_online 4
mc_world_entities_by_type{world="world",type="chicken"} 35
```

## Highlights

- Exact p50, p95, and p99 tick times from Paper's raw tick-duration window
- Per-world entities, entity types, tile entities, chunks, and players
- JVM memory, threads, garbage collection, process CPU, and uptime
- No runtime dependencies, outbound telemetry, database, or embedded framework
- Main-thread-safe snapshots; HTTP scrapes never call Bukkit or block a tick
- Optional bearer authentication and failure-safe configuration reloads
- One jar for Paper and Purpur 1.18.2 through 26.2 on Java 17 or newer

## Quick start

1. [Download the latest release](https://github.com/gzimbric/TickScope/releases/latest).
2. Put `TickScope-*.jar` in the server's `plugins/` directory and restart.
3. Scrape the default endpoint at `http://127.0.0.1:9101/metrics`.

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["127.0.0.1:9101"]
```

The default endpoint is open but bound to loopback. When Minecraft runs in Docker, follow the
[Docker installation notes](https://github.com/gzimbric/TickScope/wiki/Installation#running-in-docker)
before changing the bind address. Configure bearer authentication and TLS whenever metrics cross
a network you do not fully control.

## Documentation

The full manual lives in the [TickScope wiki](https://github.com/gzimbric/TickScope/wiki):

| Guide | Covers |
|---|---|
| [Installation](https://github.com/gzimbric/TickScope/wiki/Installation) | Requirements, upgrades, Prometheus, and Docker |
| [Configuration](https://github.com/gzimbric/TickScope/wiki/Configuration) | Every setting, bearer authentication, and commands |
| [Metrics reference](https://github.com/gzimbric/TickScope/wiki/Metrics) | Metric names, labels, semantics, and PromQL notes |
| [Grafana and Prometheus](https://github.com/gzimbric/TickScope/wiki/Grafana-and-Prometheus) | Scrape, remote-write, and dashboard examples |
| [Design and privacy](https://github.com/gzimbric/TickScope/wiki/Design-and-Privacy) | Architecture, collection cost, and security model |
| [Troubleshooting](https://github.com/gzimbric/TickScope/wiki/Troubleshooting) | Compatibility, missing series, and lag diagnosis |
| [Development and releases](https://github.com/gzimbric/TickScope/wiki/Development-and-Releases) | Building, testing, releases, and support |

## Compatibility

Paper 1.18.2+ and Paper-compatible Purpur servers are supported. Spigot, Bukkit, Folia, Velocity,
and BungeeCord are not supported. Install TickScope on each Paper backend in a proxy network and
assign each server a unique `server-id`.

## Download and support

- [Download on GitHub](https://github.com/gzimbric/TickScope/releases/latest)
- [Download on Modrinth](https://modrinth.com/plugin/tickscope)
- [Report a bug](https://github.com/gzimbric/TickScope/issues/new?template=bug_report.yml)
- [Request a feature](https://github.com/gzimbric/TickScope/issues/new?template=feature_request.yml)
- [Ask a question](https://github.com/gzimbric/TickScope/discussions)

TickScope is maintained by [Gabe Zimbric](https://github.com/gzimbric) and licensed under
[GPL-3.0-or-later](LICENSE).
