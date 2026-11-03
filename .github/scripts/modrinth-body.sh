#!/usr/bin/env bash
# Turn README.md into the Modrinth project body.
#
# Modrinth does not resolve repository-relative links, so every relative target becomes an
# absolute GitHub URL: images, dashboards and rule files are served raw so they display and
# download; pages are browsed. Everything that points back at GitHub is removed: the whole
# shields.io badge row and the download calls to action. Modrinth has its own Files tab and
# shows platform, version and licence in its sidebar, so those badges are duplicated clutter
# that also sends readers off-platform, costing the download about to happen here.
#
# Fails when a relative link survives the rewrite, so a link added to README.md cannot reach
# Modrinth broken. CI runs it on every push for exactly that reason.
#
#   bash .github/scripts/modrinth-body.sh README.md
set -euo pipefail

README=${1:?usage: $0 README.md}
# Composed from a variable so the weekly link check, which extracts every literal URL from
# these scripts, does not probe the bare base path (a directory, which raw.githubusercontent
# answers with 404).
PROJECT=gzimbric/TickScope
RAW="https://raw.githubusercontent.com/${PROJECT}/main"
BLOB="https://github.com/${PROJECT}/blob/main"

body=$(RAW="$RAW" BLOB="$BLOB" perl -0pe '
  s#src="((?!https?://)[^"]+)"#src="$ENV{RAW}/$1"#g;
  s#\]\(((?!https?://|\#)[^)]+\.(?:png|jpg|jpeg|gif|svg|json|ya?ml))\)#]($ENV{RAW}/$1)#g;
  s#\]\(((?!https?://|\#)[^)]+)\)#]($ENV{BLOB}/$1)#g;
  s{^\[!\[[^\n]*\n}{}mg;
  s{(^\# [^\n]*\n)\n+}{$1\n}m;
  s#^1\. \[Download the latest release\]\([^)]*\)\.#1. Download the latest jar from the **Files** tab at the top of this page.#m;
  s#^- \[Download on GitHub\]\([^)]*\)\n##m;
  s#^- \[Download on Modrinth\]\([^)]*\)\n##m;
  s#^- \[Download on Hangar\]\([^)]*\)\n##m;
  s{^## Download and support$}{## Support}m;
' "$README")

# A relative link that the rules above did not catch would be a dead link on Modrinth.
leftover=$(perl -ne 'print "$.: $_" if /\]\((?!https?:\/\/|#)/ || /src="(?!https?:\/\/)/' <<<"$body" || true)
if [[ -n "$leftover" ]]; then
  echo "modrinth-body.sh: relative links survived the rewrite:" >&2
  echo "$leftover" >&2
  exit 1
fi

printf '%s\n' "$body"
