#!/usr/bin/env bash
# Builds every view of the site into the local `pages` branch with mike: "all", then one per CLI version.
# Usage: ./build.sh <fixtures dir>   (then: git push origin pages)
set -euo pipefail
cd "$(dirname "$0")"
fixtures=$1
versions=$(python3 gen.py "$fixtures" all)
mike deploy --branch pages --title "All versions" all
for v in $versions; do
  python3 gen.py "$fixtures" "$v" > /dev/null
  mike deploy --branch pages --title "CLI $v" "$v"
done
mike set-default --branch pages all || true  # fails with "nothing changed" once the default is set
