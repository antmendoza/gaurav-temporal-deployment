#!/bin/sh
# Runs once in the admin-tools container: joins the two clusters and creates the replicated
# namespace, as steps 6 and 7 of ../../README.md do on the real ones.
set -eu

retry() {
  for _ in $(seq 60); do
    "$@" && return
    sleep 2
  done
  return 1
}

retry temporal operator cluster health --address aws:7233
retry temporal operator cluster health --address gcp:7233
retry temporal operator cluster upsert --address aws:7233 --frontend-address gcp:7233 \
  --enable-connection --enable-replication
retry temporal operator cluster upsert --address gcp:7233 --frontend-address aws:7233 \
  --enable-connection --enable-replication
if ! temporal operator namespace describe --address aws:7233 --namespace replicated >/dev/null 2>&1; then
  # aws accepts "gcp" as a cluster name only once its cluster metadata refreshes, a few seconds.
  retry temporal operator namespace create --address aws:7233 --namespace replicated --global \
    --cluster aws --cluster gcp --active-cluster aws
fi
retry temporal operator namespace describe --address gcp:7233 --namespace replicated >/dev/null
echo "clusters joined; namespace replicated is active on aws"
