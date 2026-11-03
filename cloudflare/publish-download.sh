#!/usr/bin/env bash
# Puts a released installer on znkeyboard.2amz.com: uploads it to R2, records it in latest.json
# (version, file, size, SHA-256, release notes) and removes the platform's previous installer, since
# only the latest is kept here (older ones stay on GitHub).
#
# Usage: cloudflare/publish-download.sh <android|macos|windows> <version> <file> <release-notes-url>
set -euo pipefail
cd "$(dirname "$0")"
PLATFORM=$1 VERSION=$2 FILE=$3 NOTES=$4
BUCKET=znkeyboard-downloads
case "$PLATFORM" in android|macos|windows) ;; *) echo "platform must be android, macos or windows" >&2; exit 1;; esac
[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 1; }
NAME=$(basename "$FILE")
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

npx wrangler r2 object get "$BUCKET/latest.json" --remote --file "$TMP/latest.json" >/dev/null 2>&1 || echo '{}' > "$TMP/latest.json"
OLD=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], {}).get("file", ""))' "$TMP/latest.json" "$PLATFORM")

npx wrangler r2 object put "$BUCKET/$PLATFORM/$NAME" --remote --file "$FILE" >/dev/null
python3 - "$TMP/latest.json" "$PLATFORM" "$VERSION" "$NAME" "$(stat -f %z "$FILE")" "$(shasum -a 256 "$FILE" | cut -d' ' -f1)" "$NOTES" <<'PY'
import json, sys
path, platform, version, name, size, sha256, notes = sys.argv[1:]
data = json.load(open(path))
data[platform] = {"version": version, "file": name, "size": int(size), "sha256": sha256, "notes": notes}
json.dump(data, open(path, "w"), indent=2, sort_keys=True)
PY
npx wrangler r2 object put "$BUCKET/latest.json" --remote --file "$TMP/latest.json" --content-type application/json >/dev/null
if [ -n "$OLD" ] && [ "$OLD" != "$NAME" ]; then
    npx wrangler r2 object delete "$BUCKET/$PLATFORM/$OLD" --remote >/dev/null
fi
echo "Published $PLATFORM $VERSION: https://znkeyboard.2amz.com/download/$PLATFORM"
