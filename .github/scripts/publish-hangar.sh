#!/usr/bin/env bash
# Upload the same tagged jar to Hangar, or verify an existing version on retry.
set -euo pipefail

VERSION=${1:?usage: $0 VERSION ARTIFACT CHANGELOG}
ARTIFACT=${2:?usage: $0 VERSION ARTIFACT CHANGELOG}
CHANGELOG=${3:?usage: $0 VERSION ARTIFACT CHANGELOG}
REPO_ROOT=${REPO_ROOT:-.}
API=https://hangar.papermc.io/api/v1
PROJECT=gzimbric/TickScope
USER_AGENT='gzimbric/TickScope release workflow (github.com/gzimbric/TickScope)'

: "${HANGAR_API_TOKEN:?Set the HANGAR_API_TOKEN repository secret with create_version permission}"
test -f "$ARTIFACT"
test -f "$CHANGELOG"
jq -e '.PAPER | type == "array" and length > 0' \
  "$REPO_ROOT/.github/hangar-paper-versions.json" >/dev/null

# Temporary files come from mktemp rather than RUNNER_TEMP, so a failed upload can be
# retried by hand outside GitHub Actions as well.
existing=$(mktemp)
metadata=$(mktemp)
trap 'rm -f "$existing" "$metadata"' EXIT

local_sha=$(sha256sum "$ARTIFACT" | cut -d' ' -f1)
version_url="$API/projects/$PROJECT/versions/$VERSION"
status=$(curl -sS -L -o "$existing" -w '%{http_code}' \
  -H "User-Agent: $USER_AGENT" "$version_url")
if [[ "$status" == 200 ]]; then
  published_sha=$(jq -r '.downloads.PAPER.fileInfo.sha256Hash // empty' "$existing")
  if [[ -z "$published_sha" || "$published_sha" != "$local_sha" ]]; then
    echo "Existing Hangar version $VERSION differs from the tagged jar; refusing to replace it." >&2
    exit 1
  fi
  echo "Hangar version $VERSION already holds the same jar."
elif [[ "$status" != 404 ]]; then
  echo "Could not determine Hangar version state (HTTP $status)." >&2
  exit 1
fi

session=$(curl --fail-with-body -sS -G -X POST \
  -H "User-Agent: $USER_AGENT" --data-urlencode "apiKey=$HANGAR_API_TOKEN" \
  "$API/authenticate")
jwt=$(jq -er '.token' <<<"$session")
if [[ "$status" == 404 ]]; then
  jq -n --arg version "$VERSION" --rawfile description "$CHANGELOG" \
    --slurpfile platforms "$REPO_ROOT/.github/hangar-paper-versions.json" \
    '{version:$version,channel:"Release",description:$description,
      files:[{platforms:["PAPER"]}],platformDependencies:$platforms[0]}' > "$metadata"

  curl --fail-with-body -sS -X POST -H "User-Agent: $USER_AGENT" \
    -H "Authorization: Bearer $jwt" \
    -F "versionUpload=<$metadata;type=application/json" \
    -F "files=@$ARTIFACT;type=application/java-archive" \
    "$API/projects/$PROJECT/upload" >/dev/null
  echo "Published Hangar version $VERSION."
fi

jq -n --rawfile content "$REPO_ROOT/.github/hangar-description.md" \
  '{content:$content}' > "$metadata"
curl --fail-with-body -sS -X PATCH -H "User-Agent: $USER_AGENT" \
  -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
  --data-binary "@$metadata" "$API/pages/editmain/$PROJECT" >/dev/null
echo "Synchronized the Hangar project page."
