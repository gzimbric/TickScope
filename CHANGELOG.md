# Changelog

Release notes are written here by hand. The release workflow reads the section matching the
version being tagged and uses it verbatim for the GitHub release and the Modrinth changelog,
so this file is the one place a user-facing change gets described.

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
