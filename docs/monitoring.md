# Collection health, filters, and alerts

TickScope serves the last snapshot without asking a tick thread to do any work during a
scrape. A successful HTTP scrape therefore does not prove that the snapshot is current.

## Collection health

Every new metric includes the existing `server` label. The three `mc_collection_*` families
also have a `stage` label: `main` (normal collection), `world` (slow world scan), or `players`
(Folia player sampling). The two `mc_folia_player_samples_*` gauges carry only `server`.

| Metric | Meaning |
|---|---|
| `mc_collection_enabled` | 1 when this stage is enabled; 0 when disabled or unsupported. |
| `mc_collection_last_success_timestamp_seconds` | Unix time of the last successful collection; 0 before the first success. |
| `mc_collection_failures_total` | Collection exceptions, or incomplete Folia batches, since the current configuration was activated. |
| `mc_folia_player_samples_completed` | Players with a successful ping reading in the last finished or expired batch. |
| `mc_folia_player_samples_expected` | Players requested in that same batch, including skipped or retired players. |
| `mc_http_connections_rejected_total` | Connections the endpoint closed before reading a request, by `reason`: `per_address` (the client address exceeded `max-connections-per-address`) or `saturated` (every worker and queue slot was held by stalled clients). Process lifetime; not reset by a reload. |

Health readings are updated independently of the data snapshot, so an exception increases
the failure counter even when the previous snapshot remains in use. Scheduled collection
exceptions are logged and the next cycle retries. Health timestamps and failure counters reset
on configuration reload. The old heavy-world cache can survive a reload, but the new world's
health timestamp stays at zero until a scan actually succeeds under the new settings.

A Folia batch expires when the next collection cycle starts, publishing any available
readings. There is at most one outstanding task per player, including across configuration
reloads. A stalled player's later batches are counted as missing until its old task completes
or retires. An expired callback cannot republish its data. This deadline follows global-region
collection cycles, not wall-clock time; if the global region itself stalls, the health timestamp
stops advancing and the stale-collection alert detects it.

A partially successful batch refreshes the player timestamp. A batch with no successful
readings and at least one requested player does not. An empty server is a successful empty
batch. Coverage refers to ping reads, not unique regions or successful regional API reads;
use `mc_folia_region_tps_samples` and `mc_folia_region_tick_duration_samples` for regional API coverage.
Multiple players can sample the same region, so averages remain weighted by player locations.

`/tickscope status` also reports stage freshness, failure counts, and filter sizes.

## Tick-time histogram

On Paper, `mc_tick_time_seconds` is a cumulative histogram of every tick since the server
started, fed by Paper's end-of-tick event. The exact `mc_tick_duration_seconds` percentiles
describe one five-second window per sample; the histogram aggregates across any window and
across servers, at the resolution of its buckets (5, 10, 20, 30, 40, 50, 75, 100, 150, 250, 500,
1,000 and 2,500 ms).

```promql
histogram_quantile(0.99, sum by (le, server) (rate(mc_tick_time_seconds_bucket[5m])))
rate(mc_tick_time_seconds_count[1m])                                   # ticks per second, uncapped
rate(mc_tick_time_seconds_sum[5m]) / rate(mc_tick_time_seconds_count[5m])  # mean tick time
```

The family is absent on Folia, which has no single server tick, and until the first tick has
been observed. Paper 1.21 and later pause ticking while nobody is online, which shows as a
tick rate of zero rather than a stall.

## Chunk churn

`mc_world_chunk_loads_total`, `mc_world_chunk_unloads_total` and
`mc_world_chunk_generations_total` count chunk events per world for the life of the process,
on Paper and Folia alike. The loaded-chunk gauge hides churn because loads and unloads cancel
out; `rate()` over these counters shows it, and generation is the most expensive thing a tick
does. `exclude-worlds` applies when the snapshot is taken, so excluding and re-including a
world never resets its counts.

## JVM pools and file descriptors

`mc_jvm_memory_pool_*` break the heap and non-heap areas down by pool (names depend on the
collector: `G1 Old Gen`, `ZHeap`, `Metaspace`, the code heaps). The
`collection_used` variant is the occupancy right after the pool was last collected, which is
the honest leak signal; the live figure oscillates with every allocation.
`mc_jvm_buffer_pool_*` cover the NIO buffers the JDK tracks (`direct`, `mapped`). Netty's
pooled network buffers are allocated outside that accounting on HotSpot, so treat `direct` as
a lower bound for direct memory.

`mc_process_open_fds` and `mc_process_max_fds` are read when the metrics body is rendered,
on the HTTP thread rather than the tick thread, because counting descriptors walks
`/proc/self/fd`. Both are absent on Windows and on JVMs that cannot report them.

## Server configuration

`mc_server_view_distance`, `mc_server_simulation_distance`, `mc_server_online_mode`,
`mc_server_whitelist_enabled` and `mc_server_whitelist_enforced` are gauges rather than info
labels, so a flag that flips does not start a new series. `mc_world_view_distance` and
`mc_world_simulation_distance` follow `per-world` and `exclude-worlds`.

## Filter expensive collection

Add these settings to `plugins/TickScope/config.yml` and run `/tickscope reload`:

```yaml
exclude-worlds: [lobby, minigames]
entity-types:
  enabled: true
  interval-ticks: 600
  allowlist: [zombie, chicken, villager]
```

World exclusions use exact, case-sensitive world names. Excluded worlds contribute no
per-world series and are skipped before entity or chunk walks. Server-wide players and
player events, and Folia player-location sampling, still cover the whole server.

The allowlist uses registry key names such as `zombie` (without `minecraft:`); an empty list
means all types. It filters only the entity-type breakdown. Total entities still count every
entity in included worlds. An allowlist reduces exported series, but still walks each included
world's entities. World exclusions, the scan interval, and `entity-types.chunks-per-tick` control
that traversal cost. The scan processes a bounded number of loaded chunks per Paper tick.
Chunks may unload or change while a scan runs, so totals approximate the world over the scan
period. The previous complete sample remains visible until the next scan finishes.

A reload removes disabled or newly excluded cached series immediately. Widening filters
populates newly included series at the next slow scan. Malformed YAML, missing files, and
invalid values reject the reload and preserve the active runtime settings.

## Dashboards

Import [the main dashboard](../assets/grafana/tickscope-dashboard.json) for all metrics, or
[the per-server overview](../assets/grafana/tickscope-per-server-dashboard.json) for a row
repeated for each selected server. Legends include server IDs. The main dashboard adds
collection age, failure increases, and Folia player coverage. Age is intentionally large
when an enabled stage has never succeeded; disabled stages are hidden. With no players,
coverage displays zero rather than dividing by zero.

## Prometheus alerts

[The alert bundle](../assets/prometheus/tickscope-alerts.yml) covers an unreachable exporter,
stale normal/player collection, stale world scans, sustained low Paper/Folia TPS, high
regional MSPT where supported, sustained heap pressure, incomplete player coverage, and a
process close to its file descriptor limit.

Load it in the Prometheus configuration:

```yaml
rule_files:
  - /etc/prometheus/rules/tickscope-alerts.yml
```

The endpoint-down rule assumes the scrape job is named `minecraft`; change that selector
if your job uses another name. The other rules select TickScope metrics across jobs.
These rules define alerts; configure Alertmanager separately to deliver notifications.

The included thresholds are starting points: 30 seconds for main/player freshness,
120 seconds for world freshness, TPS below 18 for five minutes, regional average MSPT
above 50 for five minutes, heap usage above 90% for ten minutes, and more than 80% of the
file descriptor limit in use for ten minutes. Increase freshness
thresholds if you lengthen collection intervals, and allow room for server lag and scraping.
The world scan's period is `entity-types.interval-ticks` plus the scan itself, which takes
about one tick per `entity-types.chunks-per-tick` loaded chunks, so size the world-freshness
threshold above that sum; `mc_entity_collection_duration_seconds` reports the scan's CPU time.
Disabled stages do not trigger stale alerts, unsupported Folia MSPT metrics do not trigger
MSPT alerts, and unbounded heap maxima do not trigger heap-pressure alerts.

Validate the bundle and its behavioral tests with:

```sh
promtool check rules assets/prometheus/tickscope-alerts.yml
promtool test rules assets/prometheus/tickscope-alerts.test.yml
```
