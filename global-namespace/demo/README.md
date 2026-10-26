# Failover demo: Java workers in both clouds

A workflow that runs one activity every 10 seconds. Each activity records **which cloud and pod**
ran it. Fail the `replicated` namespace over mid-run and the history shows steps moving from one
cloud to the other, with no change to the workers.

```
FailoverWorkflow.run(steps)
  for step in 1..steps:
    recordStep(step)  ->  "step 7 ran on gcp (failover-worker-…) at …"
    sleep 10s
```

| Piece | What it is |
|---|---|
| `src/…/FailoverWorkflow*.java` | Workflow + `progress` query |
| `src/…/StepActivities*.java` | Activity that reports `CLOUD` and hostname |
| `src/…/WorkerMain.java` | Worker; configured by env vars (below) |
| `Dockerfile` | Multi-stage Maven build → JRE 21 image |
| `k8s/worker.yaml` | Deployment in the `temporal` namespace, pointed at the local frontend |
| `deploy-worker.sh` | Builds, pushes to ECR or Artifact Registry, applies the Deployment |

Worker env vars: `TEMPORAL_ADDRESS` (default `localhost:7233`), `TEMPORAL_NAMESPACE`
(`replicated`), `TASK_QUEUE` (`replicated-demo`), `CLOUD` (`local`).

## Prerequisites

Both clusters are deployed and connected, and the `replicated` namespace exists, as in steps 1–7
of the [global-namespace guide](../README.md). You also need Docker and `envsubst`
(`brew install gettext`).

## 1. Deploy a worker in each cloud

From the repo root, in the matching terminal:

```sh
# terminal AWS
./global-namespace/demo/deploy-worker.sh aws

# terminal GCP
./global-namespace/demo/deploy-worker.sh gcp
```

The script creates the registry repo if needed (`temporal-failover-worker` in ECR,
`temporal-demo` in Artifact Registry), builds for `linux/amd64`, pushes, switches kubectl to that
cluster, and applies `k8s/worker.yaml` with `CLOUD` set to `aws` or `gcp`. Each worker talks only
to its **local** frontend: `temporal-frontend.temporal.svc.cluster.local:7233`.

If the GKE pod is stuck in `ImagePullBackOff`, the node service account can't read Artifact
Registry:

```sh
PROJECT_NUMBER="$(gcloud projects describe "$TF_VAR_project_id" --format='value(projectNumber)')"
gcloud artifacts repositories add-iam-policy-binding temporal-demo --location "$TF_VAR_region" \
  --member "serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role roles/artifactregistry.reader
```

## 2. Start the workflow (namespace active on AWS)

```sh
# terminal AWS
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal workflow start --namespace replicated --task-queue replicated-demo \
    --type FailoverWorkflow --workflow-id failover-demo --input 60
```

60 steps × 10 s ≈ 10 minutes. Watch the AWS worker do the work:

```sh
kubectl -n temporal logs -f deploy/failover-worker      # terminal AWS: steps logged
kubectl -n temporal logs -f deploy/failover-worker      # terminal GCP: idle
```

## 3. Fail over to GCP

```sh
# terminal GCP
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator namespace update --namespace replicated --active-cluster gcp
```

Within a few seconds the GCP worker log starts printing steps and the AWS one goes quiet. Nothing
was restarted or reconfigured. While the namespace was passive on GCP, the GCP worker's polls
only received queries. After the failover, the same polls receive workflow and activity tasks.

## 4. Check the result

```sh
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal workflow query --namespace replicated --workflow-id failover-demo --name progress
```

```
step 1 ran on aws (failover-worker-…) at …
…
step 18 ran on aws (failover-worker-…) at …
step 19 ran on gcp (failover-worker-…) at …
…
```

Run the same query on the other cluster; both clusters hold the same history. Fail back with
`--active-cluster aws` and the steps move back.

Things to try:

- **Kill the active side's worker** (`kubectl scale deploy/failover-worker --replicas=0`). The
  workflow pauses and resumes when you fail over or scale back up.
- **Signal or start from the passive side.** With `selected-apis-forwarding`, the call is
  forwarded to the active cluster.
- **Unplanned failover.** Fail over while the active side is unreachable. Steps that hadn't
  replicated yet run again on the new side, so activities must be idempotent.

## Run locally

```sh
cd global-namespace/demo
mvn package
temporal server start-dev --namespace replicated &
CLOUD=local java -jar target/failover-worker.jar
temporal workflow execute --namespace replicated --task-queue replicated-demo \
  --type FailoverWorkflow --workflow-id local-1 --input 3
```

## Clean up

```sh
kubectl -n temporal delete deployment failover-worker   # in each cluster
```
