#!/usr/bin/env bash
set -e

# ── AFK Dummy — local build helper ──────────────────────────────────────────
# Requires: Java 21+   (brew install --cask temurin@21)
#           Gradle     (brew install gradle)
# Usage:    bash build.sh
# Output:   build/libs/afkdummy.jar
# ────────────────────────────────────────────────────────────────────────────

cd "$(dirname "$0")"

# Patch to a verified-compatible version pair
sed -i '' 's|2\.0\.0-beta\.14|1.7.7|g'                        build.gradle.kts
sed -i '' 's|1\.26\.3-R0\.1-SNAPSHOT|1.21.4-R0.1-SNAPSHOT|g' build.gradle.kts

gradle shadowJar --no-daemon

echo ""
echo "✓  JAR ready: $(pwd)/build/libs/afkdummy.jar"
