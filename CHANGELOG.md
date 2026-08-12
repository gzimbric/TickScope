# Changelog

Release notes are written here by hand. The release workflow reads the section matching the
version being tagged and uses it verbatim for the GitHub release and the Modrinth changelog,
so this file is the one place a user-facing change gets described.

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
