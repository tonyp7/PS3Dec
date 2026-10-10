#!/usr/bin/env bash
# Build the engine benchmark and run it on an attached device through app_process (no APK needed).
#
#   android/tools/benchmark.sh [size-in-MiB] [pool sizes] [file|null]
#
# Defaults: 4096 MiB, pools 1,2,4,8, output to files. Use "null" as the third argument to send the
# output to /dev/null and take storage write speed out of the measurement. Needs ANDROID_HOME, an
# authorized device, and enough free space on /data/local/tmp (about 2x the image size).
set -euo pipefail

size="${1:-4096}"
pools="${2:-1,2,4,8}"
target="${3:-file}"

here="$(cd "$(dirname "$0")" && pwd)"
android="$(cd "$here/.." && pwd)"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"

build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
platform_jar="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)"
stdlib="$(find "$HOME/.gradle" -name 'kotlin-stdlib-[0-9]*.jar' ! -name '*sources*' | sort -V | tail -1)"
[ -n "$stdlib" ] || { echo "kotlin-stdlib jar not found in ~/.gradle; run ./gradlew :engine:test first" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

(cd "$android" && ./gradlew --quiet :engine:jar :engine:testClasses)

# Only the benchmark and its helpers: the other test classes have names dex cannot represent.
classes="$android/engine/build/classes/kotlin/test"
pkg="io/github/tonyp7/ps3dec/engine"
mkdir -p "$work/classes"
(cd "$classes" && cp --parents "$pkg"/BenchmarkKt*.class "$pkg"/FlushingChannel*.class "$pkg"/SplitMix64*.class "$pkg"/SyntheticImage*.class "$work/classes/")
jar cf "$work/helpers.jar" -C "$work/classes" .

"$build_tools/d8" --release --min-api 30 --lib "$platform_jar" --output "$work/bench.jar" \
    "$android/engine/build/libs/engine.jar" "$work/helpers.jar" "$stdlib" >/dev/null

adb="$ANDROID_HOME/platform-tools/adb"
"$adb" push "$work/bench.jar" /data/local/tmp/bench.jar >/dev/null
"$adb" shell "CLASSPATH=/data/local/tmp/bench.jar app_process /system/bin \
    io.github.tonyp7.ps3dec.engine.BenchmarkKt /data/local/tmp $size $pools $target" | tr -d '\r'
