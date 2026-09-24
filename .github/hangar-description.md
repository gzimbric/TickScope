# TickScope

Prometheus metrics and Grafana dashboards for Paper and Folia servers.

TickScope exports tick performance, player activity, world statistics, JVM health, and
collection freshness through a lightweight HTTP endpoint. Use the included dashboards
and alert rules to monitor a single server or a network of backends.

## Features

- Paper TPS and exact p50, p95, and p99 tick-time percentiles.
- Folia regional TPS sampled at player locations, plus regional average MSPT where the server API supports it.
- Player counts and ping, world chunks and players, JVM memory and garbage collection, CPU readings, and player event counters.
- Paper world entity totals, tile-entity totals, and entity-type breakdowns on a slower scan interval.
- A per-tick chunk budget for Paper world scans, so large worlds are inspected over multiple ticks.
- Collection freshness and failure metrics, with bounded Folia sampling and partial-coverage counts.
- A Grafana dashboard with server selectors, plus an optional overview with a row for each server.
- Eight Prometheus alert rules for exporter availability, stale collection, tick health, heap pressure, and incomplete player coverage.
- Optional bearer authentication, strict configuration validation, world exclusions, and an entity-type allowlist.
- Cached metrics responses and a per-address connection limit for the HTTP endpoint.
- No runtime libraries to install and no outbound telemetry.

## Requirements and compatibility

Install TickScope on Paper or a compatible server. The project supports Paper/Purpur 1.18.2+
and Folia-compatible servers, including Canvas on supported modern releases. The plugin targets
Java 17; use the Java version required by your Minecraft server, which may be newer.

Spigot, Bukkit-only servers, Velocity, and BungeeCord are not supported. In a proxy network,
install the plugin on each backend and configure a unique `server-id` for each one.

Folia does not have a single server-wide tick. Paper's global TPS/MSPT metrics are therefore
replaced by regional summaries sampled at online player locations. Several players may sample
the same region, and idle regions are not represented. World-wide entity and tile-entity scans
are unavailable on Folia. The documentation explains these differences and partial sampling.

TickScope 2.0 reports tick duration and ping in seconds and uses conventional metric names.
If upgrading from 1.x, update custom queries with the [metric migration guide](https://github.com/gzimbric/TickScope/blob/v2.0.0/docs/metric-migration.md).

## Getting started

1. Download the release JAR and place it in the server's `plugins` directory.
2. Restart the server.
3. Configure Prometheus to scrape `127.0.0.1:9101` when Prometheus runs on the same host.
4. Import a TickScope dashboard into Grafana and select your Prometheus data source.

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["127.0.0.1:9101"]
```

The default endpoint is `http://127.0.0.1:9101/metrics`. Docker and remote scrapers need the
appropriate bind address and network configuration. Configure bearer authentication and a
TLS-terminating, rate-limiting proxy when traffic crosses an untrusted network. Prometheus and Grafana run
separately; they are not bundled into the plugin.

## Commands and permissions

- `/tickscope status` — show endpoint settings, collection freshness, and failure counts.
- `/tickscope reload` — validate and apply configuration changes.
- `/ts` — command alias.
- `tickscope.admin` — permission for these commands; granted to operators by default.

## Documentation and support

- [Installation and configuration](https://github.com/gzimbric/TickScope/wiki)
- [Collection health, filters, and alerts](https://github.com/gzimbric/TickScope/blob/v2.0.0/docs/monitoring.md)
- [Grafana dashboard](https://raw.githubusercontent.com/gzimbric/TickScope/v2.0.0/assets/grafana/tickscope-dashboard.json)
- [Per-server dashboard](https://raw.githubusercontent.com/gzimbric/TickScope/v2.0.0/assets/grafana/tickscope-per-server-dashboard.json)
- [Prometheus alert rules](https://raw.githubusercontent.com/gzimbric/TickScope/v2.0.0/assets/prometheus/tickscope-alerts.yml)
- [Source code](https://github.com/gzimbric/TickScope)
- [Report a bug](https://github.com/gzimbric/TickScope/issues)
- [Release notes](https://github.com/gzimbric/TickScope/releases/tag/v2.0.0)

Free and open source under GPL-3.0-or-later. Maintained by Gabe Zimbric.
