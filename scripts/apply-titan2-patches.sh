#!/usr/bin/env bash
# Apply our Titan 2 layer patches (patches/${2:-titan2}/, for A16 titan2-a16/<project as in phh: _ instead of />/*.patch)
# to the tree android/${1:-lineage-22} (crdroid-16 etc.) — after the TrebleDroid patches (applied by android-sync-trebledroid.sh).
# Already applied ones are skipped. Do not run during a build.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/home/builder \
  -v "$ROOT/android/home:/home/builder" -v "$ROOT/android/${1:-lineage-22}:/src" \
  -v "$ROOT/patches/${2:-titan2}:/patches:ro" -w /src titan2-aosp bash -euo pipefail -c '
# "already applied" = a commit with the same patch-id is in the recent history; the reverse dry run
# alone misses a patch whose lines a later patch of the series changed again (0003 -> 0006)
for dir in /patches/*/; do
  p=$(basename "$dir" | tr _ /)
  # (the shallow root of a --depth=1 checkout is excluded: its diff is the whole tree)
  ids=$(cd "$p" && sh=$(git rev-parse --git-path shallow) &&
        git log -p -n 300 --no-merges HEAD $( [ -f "$sh" ] && sed "s/^/^/" "$sh" ) |
        git patch-id --stable | cut -d" " -f1)
  for patch in "$dir"*.patch; do
    id=$(git patch-id --stable < "$patch" | cut -d" " -f1)
    if [ -n "$id" ] && printf "%s\n" "$ids" | grep -qxF "$id"; then echo "already applied: $patch"
    elif (cd "$p" && patch -f -p1 --dry-run -R < "$patch" >/dev/null 2>&1); then echo "already applied: $patch"
    else git -C "$p" am -q "$patch" && echo "applied: $patch"; fi
  done
done'
