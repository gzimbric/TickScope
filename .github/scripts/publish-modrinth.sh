#!/usr/bin/env bash
# Publish one release jar to Modrinth and keep the project page synchronized.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 VERSION JAR CHANGELOG" >&2
  exit 2
fi

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required}"

VERSION=$1
ARTIFACT=$2
CHANGELOG_FILE=$3
PROJECT_ID=A0ZakExK
API=https://api.modrinth.com/v2
AUTH_HEADER="Authorization: $MODRINTH_TOKEN"
USER_AGENT="User-Agent: gzimbric/TickScope release workflow (github.com/gzimbric/TickScope)"

# The artifact is only needed when this version has not been uploaded yet, so a re-run that
# just re-synchronizes the page does not have to rebuild the jar.
[[ -f "$CHANGELOG_FILE" ]] || { echo "missing changelog: $CHANGELOG_FILE" >&2; exit 2; }
jq -e 'type == "array" and length > 0' .github/modrinth-game-versions.json >/dev/null

versions=$(curl --fail-with-body -sS -H "$AUTH_HEADER" -H "$USER_AGENT" \
  "$API/project/$PROJECT_ID/version?include_changelog=false")
version_id=$(jq -r --arg version "$VERSION" \
  '[.[] | select(.version_number == $version) | .id][0] // empty' <<<"$versions")

changelog=$(<"$CHANGELOG_FILE")

if [[ -z "$version_id" ]]; then
  [[ -f "$ARTIFACT" ]] || { echo "missing artifact: $ARTIFACT" >&2; exit 2; }
  game_versions=$(<.github/modrinth-game-versions.json)
  data=$(jq -cn \
    --arg name "TickScope $VERSION" \
    --arg version "$VERSION" \
    --arg project "$PROJECT_ID" \
    --arg changelog "$changelog" \
    --argjson game_versions "$game_versions" \
    '{name:$name,version_number:$version,project_id:$project,changelog:$changelog,
      version_type:"release",loaders:["paper","purpur","folia"],game_versions:$game_versions,
      featured:true,status:"listed",environment:"server_only",file_parts:["artifact"],
      primary_file:"artifact",dependencies:[]}')

  response=$(curl --fail-with-body -sS -X POST -H "$AUTH_HEADER" -H "$USER_AGENT" \
    -F "data=$data;type=application/json" \
    -F "artifact=@$ARTIFACT;type=application/java-archive" \
    "$API/version")
  version_id=$(jq -er '.id' <<<"$response")
  echo "Published Modrinth version $VERSION ($version_id)."
else
  echo "Modrinth version $VERSION already exists ($version_id); skipping upload."
fi

# Always push the changelog, so correcting release notes after the fact reaches Modrinth too.
# This project ships one universal jar, so only the newest version should be featured.
curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
  -H "Content-Type: application/json" \
  --data "$(jq -cn --arg changelog "$changelog" '{featured:true,changelog:$changelog}')" \
  "$API/version/$version_id"
while IFS= read -r old_id; do
  [[ -n "$old_id" ]] || continue
  curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
    -H "Content-Type: application/json" --data '{"featured":false}' "$API/version/$old_id"
done < <(jq -r --arg current "$version_id" '.[] | select(.featured and .id != $current) | .id' <<<"$versions")

# Modrinth does not update the project body when a version is uploaded.
body=$(perl -0pe '
  s#src="assets/icon\.png"#src="https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/icon.png"#g;
  s#src="assets/grafana/dashboard-preview\.png"#src="https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/grafana/dashboard-preview.png"#g;
  s#\]\(assets/grafana/tickscope-dashboard\.json\)#](https://raw.githubusercontent.com/gzimbric/TickScope/main/assets/grafana/tickscope-dashboard.json)#g;
  s#\]\(LICENSE\)#](https://github.com/gzimbric/TickScope/blob/main/LICENSE)#g
' README.md)
project_data=$(jq -cn --arg body "$body" \
  --arg wiki "https://github.com/gzimbric/TickScope/wiki" \
  '{body:$body,wiki_url:$wiki}')
curl --fail-with-body -sS -o /dev/null -X PATCH -H "$AUTH_HEADER" -H "$USER_AGENT" \
  -H "Content-Type: application/json" --data "$project_data" "$API/project/$PROJECT_ID"

echo "Synchronized the Modrinth project page."
