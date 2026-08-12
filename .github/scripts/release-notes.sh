#!/usr/bin/env bash
# Emit the release notes for one version.
#
# Notes are hand-written in CHANGELOG.md so that what users read describes the release
# rather than the commit log, which carries version bumps and release plumbing that mean
# nothing outside the repository.
#
#   bash .github/scripts/release-notes.sh 1.3.0 [PREVIOUS_TAG]
set -euo pipefail

VERSION=${1:?usage: $0 VERSION [PREVIOUS_TAG]}
PREVIOUS=${2:-}
CHANGELOG=${CHANGELOG:-CHANGELOG.md}
REPO=${GITHUB_REPOSITORY:-gzimbric/TickScope}

[[ -f "$CHANGELOG" ]] || { echo "missing $CHANGELOG" >&2; exit 1; }

# The section runs from its own "## <version>" heading to the next "## " heading.
section=$(awk -v want="## $VERSION" '
  $0 == want { grabbing = 1; next }
  grabbing && /^## / { exit }
  grabbing { print }
' "$CHANGELOG")

# Trim leading and trailing blank lines.
section=$(sed -e '/./,$!d' <<<"$section" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')

if [[ -z "$section" ]]; then
  echo "no '## $VERSION' section in $CHANGELOG; add one before tagging" >&2
  exit 1
fi

echo "## Changes"
echo
echo "$section"

if [[ -n "$PREVIOUS" ]]; then
  echo
  echo "**Full changelog:** https://github.com/$REPO/compare/$PREVIOUS...v$VERSION"
fi
