#!/usr/bin/env bash
set -eu
set -o nounset

# --server runs sbt in the foreground (one JVM, runs the commands, exits); autostart=false ensures
# no background sbt server is started or reused, so this invocation shares no state with any other.
sbt -Dfile.encoding=UTF-8 \
  -J-XX:ReservedCodeCacheSize=512M \
  -J-Xms1024M -J-Xmx2048M -J-server \
  --server \
  -Dsbt.server.autostart=false \
  scalafmtCheckAll \
  scalafmtSbtCheck \
  Test/compile \
  doc \
  crossTestBridges \
  test \
  scripted
