# Changelog

Release notes are written here by hand. The release workflow reads the section matching the
version being tagged and uses it verbatim for the GitHub release and the Modrinth changelog,
so this file is the one place a user-facing change gets described.

## Unreleased

Fixes:

- A plugin disabled and enabled again without a server restart, for example through a plugin
  manager, collected nothing on Folia: the shutdown flag and the closed player sampler were
  never reset. Both are reset on enable, and the event counters survive the round trip.
- On Folia, a reload replaced the published snapshot with an all-zero placeholder for one tick
  and dropped the ping and regional summaries for one interval. A reload keeps the current
  snapshot and carries the last accepted player sample into the new collector.
- The placeholder published before the first collection reported zero heap, zero uptime and a
  1970 start time. JVM, process and event readings are real from the start; only the values the
  server owns wait for the first collection.
- A failing first sample during `/tickscope reload` left the collector with no scheduled tasks
  and the endpoint frozen. That sample is guarded like every scheduled one, and the
  configuration file is read before the runtime lock is taken, so Folia's global region never
  waits on disk I/O.
- `mc_events_total{event="chat"}` counted only messages no plugin cancelled, which left it at
  zero on servers whose chat plugin cancels and re-sends every message. Cancelled chat is now
  counted: the series counts messages players sent.
- A token containing non-ASCII characters never authenticated, because request headers were
  decoded byte by byte. Headers are decoded as UTF-8.
- `entity-types.allowlist` entries written as `Zombie` or `minecraft:zombie` silently matched
  nothing. Entries are normalised to lowercase registry keys without a namespace, and an entry
  that cannot be a registry name is rejected.
- A `path` starting with `//` or containing `%` was accepted but unreachable; a bracketed IPv6
  `bind-address` such as `[::1]` produced an invalid URL in `/tickscope status`; an
  `auth-token` of only spaces silently disabled authentication. All three are handled at parse
  time.
- The exchange deadline starts when a connection is accepted rather than when a worker picks it
  up, so a connection queued behind stalled clients can no longer wait without any deadline.
  Cancelled watchdog tasks leave the scheduler at once instead of lingering for 15 seconds.
- A renderer failure answers 500 and is logged once a minute instead of closing the connection
  in silence; an over-long request line or header line answers 414 or 431 instead of a bare
  close; a blank line ahead of the request line is tolerated. With a token configured, every
  request without it is answered 401 before the path or method is looked at.
- `mc_tick_duration_seconds` is absent until Paper's tick ring has samples, instead of reporting
  0 s for every statistic on a freshly started server. `mc_tick_samples` still says how many
  ticks the window holds.
- Every metric family is written as one contiguous block in a stable order. Prometheus accepted
  the interleaved output, but stricter text-format parsers split such families into untyped
  fragments.
- `/tickscope status` says `disabled` for the world scan when neither per-world nor entity-type
  series are enabled, and says so when startup failed instead of raising an error.
- On Folia, a regional-metrics method with an unexpected return type is ignored instead of
  failing every player sample.

Features:

- `mc_tick_time_seconds`, a cumulative histogram of tick durations on Paper fed by the
  end-of-tick event: `histogram_quantile` across any window and across servers, the uncapped
  tick rate from its count, and the mean tick time over any range.
- `mc_world_chunk_loads_total`, `mc_world_chunk_unloads_total` and
  `mc_world_chunk_generations_total` per world, on Paper and Folia.
- `mc_jvm_memory_pool_*` per memory pool, including the occupancy right after the pool's last
  collection, and `mc_jvm_buffer_pool_*` for the NIO buffer pools the JDK tracks.
- `mc_process_open_fds` and `mc_process_max_fds`, read on the HTTP thread rather than the tick
  thread; absent where the JVM cannot report them.
- `mc_server_view_distance`, `mc_server_simulation_distance`, `mc_server_online_mode`,
  `mc_server_whitelist_enabled` and `mc_server_whitelist_enforced`, plus
  `mc_world_view_distance` and `mc_world_simulation_distance`.
- `mc_events_total` gains the `command` (player-typed, not cancelled) and `kick` values.
- `mc_http_connections_rejected_total` counts connections the endpoint turned away, by reason.
- `auth-tokens` accepts further tokens beside `auth-token`, so a token can be rotated without a
  scrape failing in between. Token changes apply in place; the listener is no longer restarted.
- `max-connections-per-address` makes the per-address connection cap (2 by default)
  configurable for scrapers that share a reverse proxy or NAT address.
- The Grafana dashboard gains panels for the histogram quantiles, the tick-time distribution,
  the actual tick rate, chunk churn and generation, memory pools after collection, buffer
  pools, file descriptors and rejected connections. The alert bundle gains
  `TickScopeFileDescriptorsNearLimit`.

Changes:

- Tile-entity totals come from one pass over each world's chunk holders per scan instead of a
  per-chunk walk that allocated a block state for every block entity. A scan takes each
  world's chunk list when it reaches that world rather than every list up front.
- Shipped configuration comments state the enforced minimums, the chunk budget range, the
  accepted allowlist form, the connection cap and what `entity-types.interval-ticks` measures:
  the idle gap between two scans, not the scan period.
- The release workflow refreshes the GitHub release notes on a retry, the Modrinth project body
  comes from a script that fails on a relative link, the link checkers cover `docs/` and the
  alert rules, and CI compiles and tests against Paper's stable 26.3 API.

**Upgrading:** existing configuration files continue to work. Every new series is an addition;
nothing is renamed or removed. `mc_events_total` has two more `event` values, so a
`sum(rate(mc_events_total[5m]))` without `by (event)` now includes commands and kicks.
`mc_tick_duration_seconds` is absent for the first few seconds after a restart, and
`mc_events_total{event="chat"}` counts more on servers with a cancelling chat plugin. Re-import
the dashboard to see the new panels.

## 2.0.1

- Add Minecraft 26.3 to the Paper compatibility listings on Modrinth and Hangar. The same
  Java 17 plugin remains compatible with older supported versions; it is also compiled and
  tested against the Paper 26.3 API in CI.
- Paper's 26.3 server builds are still marked alpha. Runtime compatibility on 26.3 has not
  been smoke-tested, so use a stable Paper release for production servers.

## 2.0.0

- Metrics use base units and conventional names. Tick durations and player ping are in seconds;
  JVM memory names end in `_bytes`. The Paper tick percentile label is now `statistic`.
  Update custom queries with the [1.x to 2.0 migration guide](https://github.com/gzimbric/TickScope/blob/v2.0.0/docs/metric-migration.md).
- Paper world scans process a configurable maximum number of loaded chunks per tick instead of
  walking every entity and tile entity in one tick. The default is 32 chunks per tick, and the
  last complete sample remains available while the next scan runs.
- Repeated scrapes share a rendered metrics body until the snapshot or collection health changes.
  The HTTP server also limits simultaneous connections from one address so a single slow client
  cannot occupy every worker.
- The bundled Grafana dashboards and Prometheus alert rules use the 2.0 metric names and units.
- Release retries now verify any existing GitHub jar before replacing release assets. The release
  workflow also publishes the same jar to Hangar and checks its checksum on retries.

**Upgrading:** existing configuration files continue to work. Custom queries and alerts that use
renamed 1.x metrics must be updated. The scan now gives a per-tick chunk budget; use
`entity-types.chunks-per-tick` to tune it. Scans can span multiple ticks, so per-world totals
represent an approximate view over the scan period. The endpoint still binds to loopback by
default; protect a network-facing listener with a firewall or rate-limiting proxy.

## 1.5.0

- **Collection health is now visible even when scrapes succeed.** New freshness timestamps,
  enabled-stage gauges, and failure counters distinguish a working HTTP endpoint from a
  stalled collector. `/tickscope status` also reports collection age and failures.
- **Eight ready-to-use Prometheus alerts** cover unreachable exporters, stale collections and
  world scans, sustained low Paper and Folia TPS, high regional MSPT, heap pressure, and
  incomplete Folia player sampling. The bundle includes tested hold durations and excludes
  disabled collection stages and unsupported readings.
- **Folia sampling no longer builds a queue behind a lagging player.** Only one task per
  player can remain outstanding. An unfinished batch publishes its available readings when
  the next collection cycle starts, with completed and expected player counts showing the
  coverage. Expired callbacks cannot overwrite the published results.
- Folia sample data and its generation are now published atomically, fixing a race where an
  older callback could overwrite a newer reading after passing the generation check.
- **World exclusions and an entity-type allowlist** let operators narrow collection scope.
  `exclude-worlds` skips named worlds before scanning; `entity-types.allowlist` limits the
  per-type series while preserving full entity totals for included worlds.
- The Grafana dashboard now includes server names in legends, collection age, collection
  failures, and Folia player coverage. An additional per-server overview repeats a row for
  each selected backend.
- Malformed YAML and missing configuration files now reject a reload instead of silently
  applying defaults, including an empty authentication token. Oversized port values are
  rejected before integer conversion can turn them into a different valid port.
- Reloading or disabling TickScope now closes active and queued HTTP connections, including
  clients that never read the response. Shutdown no longer leaves those sockets waiting
  after their deadline watchdog has stopped.
- Disabling world metrics or narrowing filters removes the corresponding cached series
  immediately. Still-enabled cached readings survive a reload until the next scan.
- Scheduled collection exceptions retain the last snapshot, increment failure counters,
  and retry on the next cycle. Health readings remain available independently of snapshot
  publication.

**Upgrading:** existing configuration files continue to work; the new filters default to
including every world and entity type. Add the optional settings manually to use them, and
re-import the dashboard to see the new panels. Collection-health timestamps and failure
counters reset on configuration reload. Folia ping and regional summaries can now represent
partial batches, so consult the coverage metrics alongside them. The default alert thresholds
assume the standard collection intervals; adjust them if you sample less often. Alertmanager
must be configured separately to deliver notifications. Setup instructions and metric semantics
are in the [monitoring guide](https://github.com/gzimbric/TickScope/blob/v1.5.0/docs/monitoring.md).

## 1.4.0

- **The metrics endpoint can no longer be silenced by a stalled client.** A connection that sent
  a partial request previously held the only HTTP worker indefinitely, and every scrape timed out
  until that client disconnected — with no token required, since parsing stalls before
  authentication. The endpoint now runs on its own socket with per-connection deadlines and
  recovers on its own. Still no runtime dependencies.
- `Authorization: bearer <token>` is now accepted alongside `Bearer`, as the HTTP specification
  requires. A scrape configured with a lowercase scheme used to fail like a mistyped token.
- **Per-world entity and tile-entity totals moved to the slower scan interval**
  (`entity-types.interval-ticks`). Counting tile entities walks every loaded chunk, and before
  Minecraft 26 counting entities walked every entity, so neither belonged on the 5-second
  collection cycle. `mc_world_chunks` and `mc_world_players` are unchanged and still sampled
  every collection.
- These three series are now omitted on Folia rather than read from the global region, which does
  not own the world data they walk.
- Readings the platform cannot supply are omitted instead of reported as zero. CPU load on a JVM
  that does not expose it no longer looks like an idle server, and player ping is published only
  when a player was actually measured.
- On Folia, a batch of per-player samples that finishes late is now used rather than thrown
  away. With several regions lagging at staggered times no batch ever finished before the
  next began, so ping and regional figures could stay frozen at the last success.
- Deaths cancelled by another plugin are no longer counted.
- A configuration value of the wrong type is now rejected with the setting named. `port: "9200"`
  previously bound the default port and `per-world: "false"` silently stayed enabled.
- `/tickscope reload` reports what actually happened, including when a rollback failed, and two
  simultaneous reloads on Folia can no longer leave an endpoint running that nothing can close.
- The entity-type series survive a reload instead of disappearing until the next scan, and
  `/tickscope status` prints a valid URL for an IPv6 bind address.
- The bundled Grafana dashboard gained a Folia regions row; its tick panels were blank on Folia,
  which is the one platform where those metrics do not exist.
- Release jars are now reproducible, so a download can be checked against its published checksum
  by rebuilding the tag.

**Upgrading:** two panels can look empty after this release. `mc_player_ping_avg_ms` and
`mc_player_ping_max_ms` are absent while nobody is online, and the CPU ratios are absent on a JVM
that cannot report them, instead of both reading zero. That is the documented "missing series"
behaviour rather than a removal — add `or vector(0)` where a panel needs a visible zero, exactly
as the entity-type series already require. Per-world entity and tile-entity totals now refresh
every `entity-types.interval-ticks` (600 ticks) rather than every collection, so those graphs are
coarser; they are absent entirely on Folia.

## 1.3.0

- **Folia and Canvas support.** TickScope now runs on Folia-compatible servers and exports
  `mc_folia_region_tps`, sampled at the locations of online players.
- **Regional MSPT** (`mc_folia_region_mspt_ms`) is exported where the server API exposes
  per-region tick times, which today means Canvas. Pure Folia reports regional TPS only.
- Server-wide `mc_mspt_ms` and `mc_tps` are intentionally absent on Folia, which has no
  truthful global equivalent, rather than being reported as a misleading single number.
- Entity-by-type metrics are disabled on Folia, where a world-wide entity walk has no safe
  scheduler to run on.
- Release jars now ship a `.sha256` file alongside them so downloads can be verified.

Still one jar for Paper and Purpur 1.18.2 through 26.2, still no runtime dependencies.

## 1.2.2

- Login counting moved to the async pre-login event, which the modern login pipeline is
  guaranteed to fire.
- Added the Grafana dashboard and a security policy; long-form documentation moved to the wiki.

## 1.2.1

- `/tickscope reload` is failure-safe and no longer leaks listeners or HTTP executors, while
  event counters still span the process lifetime.
- Fresh installs bind to loopback by default, and the endpoint is validated at startup.
- Added build and runtime information plus separate entity-scan timing metrics.
- Correct GET, HEAD, 404, and 405 behavior on the endpoint.

## 1.2.0

- One jar now covers Paper 1.18.2 through 26.2.
- Entity type labels come from the registry key rather than the enum name, so the same entity
  reports the same `type` label on every supported version.
- Optional bearer token on `/metrics`. Leaving `auth-token` empty keeps the endpoint open,
  exactly as before.

## 1.1.0

- First release under the TickScope name.
