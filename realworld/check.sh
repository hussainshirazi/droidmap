#!/usr/bin/env bash
# Runs `droidmap check` on each project in projects.txt (fetched at its fixed commit, read and never built)
# and compares the findings (rule, file and line) with expected/<name>.txt. A difference fails: either a
# change added a false positive or lost a real finding, or the expected file needs updating.
#
#   realworld/check.sh path/to/droidmap.jar [work folder]
set -euo pipefail
export LC_ALL=C
jar="$1"
work="${2:-${RUNNER_TEMP:-/tmp}/droidmap-realworld}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$work"
failed=0
while read -r name url sha; do
  case "$name" in ""|\#*) continue ;; esac
  dir="$work/$name"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$sha" ]; then
    rm -rf "$dir"
    git init -q "$dir"
    git -C "$dir" fetch -q --depth 1 "$url" "$sha"
    git -C "$dir" checkout -q FETCH_HEAD
  fi
  start=$(date +%s)
  java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -jar "$jar" check "$dir" --format json --fail-on never \
    | jq -r '.[] | "\(.rule) \(.file // "-"):\(.line // "-")"' | sort > "$work/$name.txt"
  took=$(( $(date +%s) - start ))
  if diff "$work/$name.txt" <(sort "$here/expected/$name.txt") > "$work/$name.diff"; then
    echo "ok    $name: $(wc -l < "$work/$name.txt" | tr -d ' ') findings, ${took}s"
  else
    echo "FAIL  $name: the findings changed (< now, > expected)"
    cat "$work/$name.diff"
    failed=1
  fi
done < "$here/projects.txt"
if [ "$failed" = 1 ]; then
  echo "If the new findings are right, copy them into realworld/expected/ (the file per project above is in $work)."
fi
exit "$failed"
