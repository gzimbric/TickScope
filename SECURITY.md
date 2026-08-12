# Security policy

## Supported versions

Security fixes are made against the latest TickScope release. Users should update to the newest
release before reporting a problem that may already have been fixed.

## Reporting a vulnerability

Use [GitHub's private vulnerability reporting form](https://github.com/gzimbric/TickScope/security/advisories/new).
Do not open a public issue for a vulnerability that has not been fixed.

Include the TickScope version; the server software and version (Paper, Purpur, Folia or Canvas);
the Minecraft and Java versions; the relevant configuration;
steps to reproduce; and the security impact. Redact authentication tokens, private addresses, and
unrelated server data.

The maintainer will acknowledge the report, investigate it, and coordinate disclosure and a fix
through the private advisory. No response or resolution deadline is guaranteed.

## Endpoint security model

TickScope supports Paper, Purpur, Folia and Canvas, and binds to `127.0.0.1` by default. A
configured bearer token prevents anonymous reads but does not encrypt the HTTP connection.
Use a firewall or loopback-only binding for network isolation and a TLS-terminating reverse
proxy whenever metrics cross an untrusted network.

TickScope makes no outbound network connections and exports no player names. Metrics still reveal
player activity and world-level entity and chunk information, so the endpoint should not be exposed
to the public internet without appropriate controls.
