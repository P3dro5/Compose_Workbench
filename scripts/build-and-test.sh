#!/bin/sh
# Compiles core + UI + tests with kotlinc and runs the dependency-free test harness. Needs JDK 17+ and Kotlin 2.x.
# Usage: KOTLINC=/path/to/kotlinc/bin/kotlinc scripts/build-and-test.sh      (defaults to `kotlinc` on PATH)
set -e
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
mkdir -p out
"$KOTLINC" workbench-core/src/main/kotlin workbench-ui/src/main/kotlin workbench-tests/src/main/kotlin -include-runtime -d out/cw-tests.jar
java -Djava.awt.headless=true -cp out/cw-tests.jar dev.composeworkbench.tests.MainKt
