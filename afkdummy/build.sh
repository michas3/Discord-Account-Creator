#!/usr/bin/env bash
set -e

# AFK Dummy — local build helper
# Requires: Java 21+, Gradle 8.10.2
# Usage:    bash build.sh
# Output:   build/libs/afkdummy.jar

cd "$(dirname "$0")"

gradle shadowJar --no-daemon

echo ""
echo "✓  JAR ready: $(pwd)/build/libs/afkdummy.jar"
