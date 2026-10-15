#!/bin/sh
# Fake `claude` for platform and IDE tests: replays a recorded transcript, then reads stdin until the host
# closes it, as the real CLI does. Ignores its arguments.
cat "$(dirname "$0")/fixtures/plain-reply.ndjson"
cat > /dev/null
