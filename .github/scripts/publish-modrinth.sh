#!/usr/bin/env bash
# Publish one release jar to Modrinth and keep the project page synchronized.
#
# REPO_ROOT selects where README.md and the game-version list are read from, so a release
# workflow can build from a tag while taking page content from the release branch.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 VERSION JAR CHANGELOG" >&2
  exit 2
fi

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required}"

VERSION=$1
ARTIFACT=$2
CHANGELOG_FILE=$3
REPO_ROOT=${REPO_ROOT:-.}
# Featuring is for the newest release only; a back-sync of an older version must not
# take the flag away from it.
FEATURE=${MODRINTH_FEATURE:-true}
[[ "$FEATURE" == true || "$FEATURE" == false ]] || { echo "MODRINTH_FEATURE must be true or false" >&2; exit 2; }
PROJECT_ID=A0ZakExK
API=https://api.modrinth.com/v2
AUTH_HEADER="Authorization: $MODRINTH_TOKEN"
USER_AGENT="User-Agent: gzimbric/TickScope release workflow (github.com/gzimbric/TickScope)"

# The artifact is only needed when this version has not been uploaded yet, so a re-run that
# just re-synchronizes the page does not have to rebuild the jar.
[[ -f "$CHANGELOG_FILE" ]] || { echo "missing changelog: $CHANGELOG_FILE" >&2; exit 2; }
[[ -f "$REPO_ROOT/README.md" ]] || { echo "missing $REPO_ROOT/README.md" >&2; exit 2; }
jq -e 'type == "array" and length > 0' "$REPO_ROOT/.github/modrinth-game-versions.json" >/dev/null

versions=$(curl --fail-with-body -sS -H "$AUTH_HEADER" -H "$USER_AGENT" \
  "$API/project/$PROJECT_ID/version?include_changelog=false")
version_id=$(jq -r --arg version "$VERSION" \
  '[.[] | select(.version_number == $version) | .id][0] // empty' <<<"$versions")

changelog=$(<"$CHANGELOG_FILE")

if [[ -z "$version_id" ]]; then
  [[ -f "$ARTIFACT" ]] || { echo "missing artifact: $ARTIFACT" >&2; exit 2; }
  game_versions=$(<"$REPO_ROOT/.github/modrinth-game-versions.json")
  data=$(jq -cn \
    --arg name "TickScope $VERSION" \
    --arg version "$VERSION" \
    --arg project "$PROJECT_ID" \
    --arg changelog "$changelog" \
    --argjson game_versions "$game_versions" \
    --argjson feature "$FEATURE" \
    '{name:$name,version_number:$version,project_id:$project,changelog:$changelog,
      version_type:"release",loaders:["paper","purpur","folia"],game_versions:$game_versions,
      featured:$feature,status:"listed",environment:"server_only",file_parts:["artifact"],
      primary_file:"artifact",dependencies:[]}')

  # The metadata goes via a file, never inline. curl -F treats ';' as the separator before
  # ";type=", so an inline value is silently truncated at the first semicolon the release notes
  # happen to contain, and Modrinth rejects the half a JSON document that arrives.
  metadata=$(mktemp)
  trap 'rm -f "$metadata"' EXIT
  printf '%s' "$data" > "$metadata"

  # Capture the status separately: on failure curl's body goes to stdout, which is captured
  # here, so a rejected upload otherwise failed with nothing but "error: 400".
  response=$(curl -sS -w '\n%{http_code}' -X POST -H "$AUTH_HEADER" -H "$USER_AGENT" \
    -F "data=<$metadata;type=application/json" \
    -F "artifact=@$ARTIFACT;type=application/java-archive" \
    "$API/version")
  status=$(tail -n1 <<<"$response")
  body=$(sed '$d' <<<"$response")
  if [[ "$status" != 2* ]]; then
    echo "Modrinth rejected the upload with HTTP $status:" >&2
    echo "$body" >&2
    echo "--- request metadata sent (artifact omitted) ---" >&2
    jq . <<<"$data" >&2 || echo "$data" >&2
    exit 1
  fi
  version_id=$(jq -er '.id' <<<"$body")
  echo "Published Modrinth version $VERSION ($version_id)."
else
  echo "Modrinth version $VERSION already exists ($version_id); skipping upload."
  # Skipping the upload is only safe if the bytes match. Otherwise a retry would leave
  # GitHub and Modrinth serving different jars under one version number.
  if [[ -f "$ARTIFACT" ]]; then
    published_sha512=$(jq -r --arg version "$VERSION" \
      '[.[] | select(.version_number == $version) | .files[] | select(.primary) | .hashes.sha512][0] // empty' \
      <<<"$versions")
    local_sha512=$(sha512sum "$ARTIFACT" | cut -d' ' -f1)
    if [[ -n "$published_sha512" && "$published_sha512" != "$local_sha512" ]]; then
      echo "the jar built here does not match the file already published as $VERSION" >&2
      echo "  published sha512: $published_sha512" >&2
      echo "  local sha512:     $local_sha512" >&2
      exit 1
    fi
    echo "Local jar is byte-identical to the published Modrinth file."
  fi
fi

# Always push the changelog, so correcting release notes after the fact reaches Modrinth too.
patch=$(jq -cn --arg changelog "$changelog" --argjson feature "$FEATURE" \
  'if $feature then {featured:true,changelog:$changelog} else {changelog:$changelog} end')
curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
  -H "Content-Type: application/json" --data "$patch" "$API/version/$version_id"

# This project ships one universal jar, so only the newest version should be featured. Re-syncing
# an older version must not steal that flag, so the sweep only runs when featuring is requested.
if [[ "$FEATURE" == true ]]; then
  while IFS= read -r old_id; do
    [[ -n "$old_id" ]] || continue
    curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
      -H "Content-Type: application/json" --data '{"featured":false}' "$API/version/$old_id"
  done < <(jq -r --arg current "$version_id" '.[] | select(.featured and .id != $current) | .id' <<<"$versions")
fi

# Modrinth does not update the project body when a version is uploaded. Relative repository
# links also have to become absolute, and everything pointing back to GitHub is removed: the
# whole shields.io badge row and the download calls to action. Modrinth has its own Files tab
# and shows platform, version and licence in its sidebar, so those badges are duplicated
# clutter that also sends readers off-platform, costing the download about to happen here.
body=$(perl -0pe '
  s#src="assets/icon\.png"#src="https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/icon.png"#g;
  s#src="assets/grafana/dashboard-preview\.png"#src="https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/grafana/dashboard-preview.png"#g;
  s#\]\(assets/grafana/tickscope-dashboard\.json\)#](https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/grafana/tickscope-dashboard.json)#g;
  s#\]\(LICENSE\)#](https://github.com/gzimbric/TickScope/blob/main/LICENSE)#g;
  s{^\[!\[[^\n]*\n}{}mg;
  s{(^\# [^\n]*\n)\n+}{$1\n}m;
  s#^1\. \[Download the latest release\]\([^)]*\)\.#1. Download the latest jar from the **Files** tab at the top of this page.#m;
  s#^- \[Download on GitHub\]\([^)]*\)\n##m;
  s#^- \[Download on Modrinth\]\([^)]*\)\n##m;
  s{^## Download and support$}{## Support}m;
' "$REPO_ROOT/README.md")
project_data=$(jq -cn --arg body "$body" \
  --arg wiki "https://github.com/gzimbric/TickScope/wiki" \
  '{body:$body,wiki_url:$wiki}')
curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
  -H "Content-Type: application/json" --data "$project_data" "$API/project/$PROJECT_ID"

echo "Synchronized the Modrinth project page."
