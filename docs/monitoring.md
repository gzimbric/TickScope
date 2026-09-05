# Collection health, filters, and alerts

TickScope serves the last snapshot without asking a tick thread to do any work during a
scrape. A successful HTTP scrape therefore does not prove that the snapshot is current.

## Collection health

Every new metric includes the existing `server` label. The health families also have a
`stage` label: `main` (normal collection), `world` (slow world scan), or `players` (Folia
player sampling).

| Metric | Meaning |
|---|---|
| `mc_collection_enabled` | 1 when this stage is enabled; 0 when disabled or unsupported. |
| `mc_collection_last_success_timestamp_seconds` | Unix time of the last successful collection; 0 before the first success. |
| `mc_collection_failures_total` | Collection exceptions, or incomplete Folia batches, since the current configuration was activated. |
| `mc_folia_player_samples_completed` | Players with a successful ping reading in the last finished or expired batch. |
| `mc_folia_player_samples_expected` | Players requested in that same batch, including skipped or retired players. |

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
use `mc_folia_region_tps_samples` and `mc_folia_region_mspt_samples` for regional API coverage.
Multiple players can sample the same region, so averages remain weighted by player locations.

`/tickscope status` also reports stage freshness, failure counts, and filter sizes.

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
world's entities. World exclusions and the scan interval control that traversal cost. Scans
remain a consistent single-tick operation; incremental scans have not been introduced.

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
regional MSPT where supported, sustained heap pressure, and incomplete player coverage.

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
above 50 for five minutes, and heap usage above 90% for ten minutes. Increase freshness
thresholds if you lengthen collection intervals, and allow room for server lag and scraping.
Disabled stages do not trigger stale alerts, unsupported Folia MSPT metrics do not trigger
MSPT alerts, and unbounded heap maxima do not trigger heap-pressure alerts.

Validate the bundle and its behavioral tests with:

```sh
promtool check rules assets/prometheus/tickscope-alerts.yml
promtool test rules assets/prometheus/tickscope-alerts.test.yml
```
