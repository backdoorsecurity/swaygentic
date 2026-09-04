#!/bin/sh
# Convenience wrapper → system/start_server.sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
exec "$HERE/system/start_server.sh" "$@"
