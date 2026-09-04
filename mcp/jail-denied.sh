#!/usr/bin/env bash
# Bound over blacklisted binaries inside the swaygentic jail.
name="$(basename "$0")"
echo "swaygentic jail: refused to run '$name' (blacklisted)." >&2
echo "  If you need this on the host, use SWAYGENTIC_OFF=1 or call grok directly." >&2
exit 126
