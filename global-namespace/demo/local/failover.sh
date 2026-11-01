#!/usr/bin/env bash
# ./local/failover.sh gcp   make gcp the active cluster for the replicated namespace
# ./local/failover.sh       show which cluster each side thinks is active
set -euo pipefail

# The global CLI profile can carry Cloud credentials; these commands only talk to the local clusters.
export TEMPORAL_CONFIG_FILE=/dev/null

if [ -n "${1:-}" ]; then
  temporal operator namespace update --address 127.0.0.1:7233 --namespace replicated --active-cluster "$1"
fi
for cluster in aws=127.0.0.1:7233 gcp=127.0.0.1:9233; do
  printf '%s sees active cluster: ' "${cluster%%=*}"
  temporal operator namespace describe --address "${cluster#*=}" --namespace replicated -o json |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["replicationConfig"]["activeClusterName"])'
done
