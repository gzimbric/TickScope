#!/usr/bin/env bash
# Check that every URL published by this repo still resolves.
#
# Some URLs are supposed to be unreachable and are skipped rather than reported:
#   - 127.0.0.1 / localhost   the endpoint on the reader's own server
#   - maven.apache.org/POM    an XML namespace identifier, not a link
#   - www.w3.org/             ditto
#   - www.gnu.org/licences    intermittently rejects GitHub-hosted runners
#   - *.example               RFC 2606 reserves this for documentation
#
# Runs in CI weekly, and by hand: bash .github/scripts/check-links.sh
#
# Stays within POSIX-ish Bash 3.2 so that it also runs on a stock macOS shell; `mapfile`
# is Bash 4 only and is deliberately avoided.
set -uo pipefail

SKIP='127\.0\.0\.1|localhost|maven\.apache\.org/POM|www\.w3\.org/|www\.gnu\.org/licenses|api\.modrinth\.com/v2$|\.example([:/]|$)'
WIKI_DIR=${WIKI_DIR:-_wiki}
# Keep in step with the path filters in .github/workflows/link-check.yml. CHANGELOG.md is
# included because the release workflow publishes it verbatim as the release notes.
SEARCH_PATHS=(README.md SECURITY.md CHANGELOG.md pom.xml .github src/main/resources src/main/java assets/grafana)

if [[ -d "$WIKI_DIR" ]]; then
  SEARCH_PATHS+=("$WIKI_DIR")
fi

URLS=()
while IFS= read -r url; do
  [[ -n "$url" ]] && URLS+=("$url")
done < <(
  grep -rhoE --exclude-dir=.git 'https?://[^)"'"'"' <>]+' \
       "${SEARCH_PATHS[@]}" 2>/dev/null \
  | sed -e 's/[].,;:)`]*$//' -e 's/\\$//' \
  | grep -v '\$' \
  | grep -vE "$SKIP" \
  | sort -u
)

echo "Checking ${#URLS[@]} URLs"
echo

fail=0
# Bash 3.2 treats an empty array as unset under `set -u`, hence the guarded expansion.
for u in ${URLS[@]+"${URLS[@]}"}; do
  # Retry: shields.io and GitHub occasionally rate-limit a burst of requests,
  # and a 429 is not a rotted link.
  code=$(curl -sSL -o /dev/null -w '%{http_code}' \
              --max-time 25 --retry 3 --retry-delay 5 --retry-all-errors \
              -A 'TickScope-link-check' "$u" 2>/dev/null || echo 000)
  case "$code" in
    200|301|302|204) printf '  ok    %-4s %s\n' "$code" "$u" ;;
    *)               printf '  BROKEN %-4s %s\n' "$code" "$u"; fail=1 ;;
  esac
done

echo
if [ "$fail" -ne 0 ]; then
  echo "One or more links are broken."
else
  echo "All links resolve."
fi

python3 .github/scripts/check-markdown-links.py README.md SECURITY.md CHANGELOG.md "$WIKI_DIR" || fail=1
exit "$fail"
