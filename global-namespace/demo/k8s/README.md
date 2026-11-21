# Failover demo on EKS + GKE

Runs the [failover demo](../README.md) on the two real clusters: a worker in each cloud, the deck
on the laptop, and a failover of the `replicated` namespace mid-talk.

| File | What it is |
|---|---|
| `worker.yaml` | Deployment in the `temporal` namespace, pointed at the local frontend. Its `prometheus.io/*` annotations let the cluster's Prometheus scrape the SDK metrics on `:8077` |
| `deploy-worker.sh` | Builds, pushes to ECR or Artifact Registry, applies `worker.yaml` |

## Prerequisites

Both clusters are deployed and connected, and the `replicated` namespace exists, as in steps 1–7
of the [global-namespace guide](../../README.md). You also need Docker and `envsubst`
(`brew install gettext`).

## 1. Deploy a worker in each cloud

From the repo root, in the matching terminal:

```sh
# terminal AWS
./global-namespace/demo/k8s/deploy-worker.sh aws

# terminal GCP
./global-namespace/demo/k8s/deploy-worker.sh gcp
```

The script creates the registry repo if needed (`temporal-failover-worker` in ECR,
`temporal-demo` in Artifact Registry), builds for `linux/amd64`, pushes, switches kubectl to that
cluster, and applies `worker.yaml` with `CLOUD` set to `aws` or `gcp`. It pins the kubectl
context before the build, so both terminals can deploy at the same time. Each worker talks only
to its **local** frontend: `temporal-frontend.temporal.svc.cluster.local:7233`.

If the GKE pod is stuck in `ImagePullBackOff`, the node service account can't read Artifact
Registry:

```sh
PROJECT_NUMBER="$(gcloud projects describe "$TF_VAR_project_id" --format='value(projectNumber)')"
gcloud artifacts repositories add-iam-policy-binding temporal-demo --location "$TF_VAR_region" \
  --member "serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role roles/artifactregistry.reader
```

## 2. Open the UI on the laptop

The UI talks to one frontend for the whole talk. Port-forward the AWS one:

```sh
# terminal AWS
kubectl -n temporal port-forward svc/temporal-frontend 7233:7233

# terminal UI
cd global-namespace/demo && mvn -q package
java -cp target/failover-worker.jar io.temporal.demo.failover.UiMain
open http://localhost:8080
```

Until the workflow exists, the footer shows `workflow not found`. The page keeps checking, so
slide 1 appears by itself when the workflow starts.

The iframe needs the cluster's Temporal UI under `/temporal`, reached through `TEMPORAL_UI`.
The Helm values here don't set a public path yet.

## 3. Start the workflow on stage (namespace active on AWS)

```sh
temporal workflow start --address localhost:7233 --namespace replicated \
  --task-queue replicated-demo --type PresentationWorkflow --workflow-id presentation
```

Slide 1 appears, served by `aws`, and the iframe switches to the new run. Each Next/Previous
click is one Workflow Update.

## 4. Fail over to GCP

```sh
# terminal GCP
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator namespace update --namespace replicated --active-cluster gcp
```

Click Next. The footer now says `served by gcp (failover-worker-…)`, and the deck is still on the
right slide. The UI is still connected to the AWS frontend. With `selected-apis-forwarding`,
`UpdateWorkflowExecution` is forwarded to the active cluster. Fail back with
`--active-cluster aws`.

Things to try:

- **Kill the active side's worker** (`kubectl scale deploy/failover-worker --replicas=0`). The
  click hangs. When you scale back up, the update completes on the same slide.
- **Start from the passive side.** With `selected-apis-forwarding`, the call is forwarded to the
  active cluster.
- **Unplanned failover.** Fail over while the active side is unreachable. Clicks that hadn't
  replicated yet are lost, and the deck may step back a slide on the new side.

## Scenario: steady order load

Starts an `OrderWorkflow` (three ~2 s activities) every `START_EVERY_SECONDS`, so workflows are in
flight when you fail over. See [../README.md](../README.md#scenario-steady-order-load).

The workers need an image that includes `OrderWorkflow`. If they were deployed before this
scenario existed, redeploy both (step 1):
```sh
# terminal AWS
./global-namespace/demo/k8s/deploy-worker.sh aws

# terminal GCP
./global-namespace/demo/k8s/deploy-worker.sh gcp
```

Run the load generator on the laptop, through the AWS port-forward from step 2:
```sh
# terminal LOAD
cd global-namespace/demo && mvn -q package
START_EVERY_SECONDS=10 TEMPORAL_ADDRESS=localhost:7233 \
  java -cp target/failover-worker.jar io.temporal.demo.failover.LoadMain
```

Fail over as in step 4. After the failover, starts that reach the AWS frontend are forwarded to
GCP, and running orders finish their remaining steps there. The result lists where each step ran:
```sh
temporal workflow list --address localhost:7233 --namespace replicated --query 'WorkflowType="OrderWorkflow"'
temporal workflow result --address localhost:7233 --namespace replicated --workflow-id <order-id>
```

Stop the load with Ctrl-C.

## Clean up

```sh
temporal workflow terminate --namespace replicated --workflow-id presentation
kubectl -n temporal delete deployment failover-worker   # in each cluster
```
