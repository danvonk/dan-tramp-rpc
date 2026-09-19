#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes dist
find server/src -name '*.java' | sort > build/sources
javac --release 11 -Xlint:all -Werror -d build/classes @build/sources
jar --create --file dist/dan-tramp-rpc.jar --main-class dan.tramp.rpc.Server -C build/classes .
printf '%s\n' 'Built dist/dan-tramp-rpc.jar (Java 11)'
