#!/usr/bin/env bash
# Build the sgmo Java helper (dexlib2 + apksig).
#
# - fetches any missing dependency jars listed in deps.lock into $SGMO_CACHE/deps,
# - verifies the SHA-256 of every jar (hard failure on mismatch),
# - compiles the sources with `javac --release 17`,
# - packs a runnable jar at $SGMO_CACHE/helper/sgmo-helper-<sources-sha256[:12]>.jar,
# - prints the jar path on stdout (and nothing else on stdout).
#
# The jar name embeds a hash of the sources, so an unchanged tree reuses the built jar
# and a changed tree builds a fresh one. The Python side (javahelper.py) mirrors this logic.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache="${SGMO_CACHE:-$HOME/.cache/sgm-offline}"
deps_dir="$cache/deps"
helper_dir="$cache/helper"
lock="$here/deps.lock"

log() { printf '%s\n' "$*" >&2; }

sha256_of() {
  # Print the lowercase hex SHA-256 of a file.
  sha256sum "$1" | cut -d' ' -f1
}

mkdir -p "$deps_dir" "$helper_dir"

# --- dependencies -----------------------------------------------------------
classpath=""
while read -r sha url; do
  [ -z "${sha:-}" ] && continue
  case "$sha" in \#*) continue ;; esac
  jar="$deps_dir/$(basename "$url")"
  if [ ! -f "$jar" ]; then
    log "fetching $(basename "$url")"
    curl -fsSL --retry 3 -o "$jar.tmp" "$url"
    mv "$jar.tmp" "$jar"
  fi
  actual="$(sha256_of "$jar")"
  if [ "$actual" != "$sha" ]; then
    log "FATAL: sha256 mismatch for $jar"
    log "  expected $sha"
    log "  actual   $actual"
    rm -f "$jar"
    exit 1
  fi
  classpath="$classpath${classpath:+:}$jar"
done < "$lock"

# --- sources fingerprint ----------------------------------------------------
# Hash the sorted source files' contents so the jar name tracks the sources.
sources_sha="$(find "$here/src" -name '*.java' -type f | sort | xargs cat | sha256sum | cut -c1-12)"
jar_out="$helper_dir/sgmo-helper-$sources_sha.jar"

if [ -f "$jar_out" ]; then
  printf '%s\n' "$jar_out"
  exit 0
fi

# --- compile & pack ---------------------------------------------------------
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
classes_dir="$work/classes"
mkdir -p "$classes_dir"

log "compiling helper sources"
find "$here/src" -name '*.java' -type f > "$work/sources.txt"
javac --release 17 -cp "$classpath" -d "$classes_dir" @"$work/sources.txt"

log "packing $jar_out"
jar --create --file "$jar_out.tmp" --main-class sgmo.helper.Main -C "$classes_dir" .
mv "$jar_out.tmp" "$jar_out"

printf '%s\n' "$jar_out"
