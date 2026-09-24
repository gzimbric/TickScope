# Upgrading metrics from 1.x to 2.0

TickScope 2.0 changes the names and units below. Update Prometheus recording rules, alert rules,
Grafana panels, and any other queries when replacing a 1.x jar. The bundled dashboards and alert
rules in this release already use the 2.0 names.

| 1.x series | 2.0 series | Query change |
|---|---|---|
| `mc_mspt_ms{quantile="p99"}` | `mc_tick_duration_seconds{statistic="p99"}` | Divide old millisecond thresholds by 1,000; use `statistic`. |
| `mc_folia_region_mspt_ms` | `mc_folia_region_tick_duration_seconds` | Divide old millisecond thresholds by 1,000. |
| `mc_folia_region_mspt_samples` | `mc_folia_region_tick_duration_samples` | Name only. |
| `mc_player_ping_avg_ms` | `mc_player_ping_seconds{statistic="avg"}` | Divide millisecond thresholds by 1,000. |
| `mc_player_ping_max_ms` | `mc_player_ping_seconds{statistic="max"}` | Divide millisecond thresholds by 1,000. |
| `mc_jvm_memory_bytes_used` | `mc_jvm_memory_used_bytes` | Name only. |
| `mc_jvm_memory_bytes_committed` | `mc_jvm_memory_committed_bytes` | Name only. |
| `mc_jvm_memory_bytes_max` | `mc_jvm_memory_max_bytes` | Name only. |
| `mc_jvm_memory_bytes_init` | `mc_jvm_memory_init_bytes` | Name only. |

For example, `mc_folia_region_mspt_ms{statistic="max"} > 50` becomes
`mc_folia_region_tick_duration_seconds{statistic="max"} > 0.05`.

The original 1.x series stop when the 2.0 jar replaces the plugin. Prometheus retains their
historical samples according to its retention policy; a query spanning the upgrade needs both
names if you want one continuous graph. New duration values use full `double` precision rather
than four decimal places, preserving short collection times.

Paper's world scan also changes: 2.0 inspects a configurable number of loaded chunks each tick,
then publishes complete per-world totals when the scan finishes. Existing configurations use the
default of 32 chunks per tick. Larger worlds may take more ticks to refresh, and chunks that
change during a scan make the result an approximate view of that period.
