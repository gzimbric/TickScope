# Changelog

Release notes are written here by hand. The release workflow reads the section matching the
version being tagged and uses it verbatim for the GitHub release and the Modrinth changelog,
so this file is the one place a user-facing change gets described.

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
