#!/bin/sh
set -eu
rpc_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "${JAVA:-java}" --add-opens=java.base/sun.nio.fs=ALL-UNNAMED -jar "$rpc_root/dist/dan-tramp-rpc.jar" "$@"
