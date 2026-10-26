# Global namespaces: AWS ⇄ GCP

> This guide assumes you have access to AWS and GCP accounts.


End-to-end guide: deploy Temporal on AWS (EKS) and GCP (GKE) as two replicated clusters, then
create a global namespace and fail it over between clouds.

| File | Layers on | Cluster name | `initialFailoverVersion` |
|---|---|---|---|
| `aws-values.yaml` | `helm/values.yaml` | `aws` | 1 |
| `gcp-values.yaml` | `gcp/helm/values.yaml` | `gcp` | 2 |

Both share `failoverVersionIncrement: 10`, `masterClusterName: aws` and 512 history shards. The
remote cluster is registered at runtime (step 6). GCP also lists `aws` as a disabled placeholder,
because a cluster won't start unless its `masterClusterName` appears in `clusterInformation`.

The cluster name and failover version are persisted on a cluster's **first start**, so each
cluster must be installed with its overlay against an **empty** database. Without the overlays,
both deployments behave as standalone installs.

## Before you start

- Everything in the AWS [README](../README.md) and the GCP [README](../gcp/README.md)
  prerequisites: AWS CLI + SSO profile, gcloud + `gke-gcloud-auth-plugin`, Terraform >= 1.10,
  kubectl, helm.
- **Use two terminals**, one per cloud. Both setups export `TF_VAR_region`, with different values.
- Run every command from the **repo root** unless a step says otherwise.

Terminal **AWS**:

```sh
export AWS_PROFILE=<your-sso-profile>
export AWS_REGION=eu-west-3
export TF_VAR_region=$AWS_REGION
export REGION=$AWS_REGION
export TF_VAR_name_suffix=<suffix>
export KUBECONFIG=~/.kube/config-aws
aws sso login && aws sts get-caller-identity
```

| Variable | Purpose |
|---|---|
| `AWS_PROFILE` | SSO profile name in `~/.aws/config` |
| `AWS_REGION` | AWS region to deploy to; used by the `aws` CLI and the Terraform backend |
| `TF_VAR_region` | Region Terraform deploys everything into (defaults to `ap-southeast-2` if unset) |
| `REGION` | Optional. Overrides the region `helm/deploy.sh` passes to `aws eks update-kubeconfig` |
| `TF_VAR_name_suffix` | Appended to every resource name. Keep it the same on every apply, since changing it destroys and recreates |
| `KUBECONFIG` | Per-terminal kubeconfig, so the two clouds' contexts don't clash |

Terminal **GCP**:

```sh
gcloud auth login && gcloud auth application-default login
export KUBECONFIG=~/.kube/config-gcp
export TF_VAR_project_id=dse-demos
gcloud config set project "$TF_VAR_project_id"
export TF_VAR_region=europe-west1
export TF_VAR_name_suffix=<suffix>
```

| Variable | Purpose |
|---|---|
| `KUBECONFIG` | Per-terminal kubeconfig, so the two clouds' contexts don't clash |
| `TF_VAR_project_id` | GCP project to deploy into; it must already exist |
| `TF_VAR_region` | Region Terraform deploys everything into |
| `TF_VAR_name_suffix` | Appended to every resource name. Keep it the same on every apply, since changing it destroys and recreates |

Each terminal gets its own kubeconfig, so `get-credentials` / `update-kubeconfig` in one can't
switch the other's context.

## 1. AWS infrastructure (terminal AWS)

```sh
cd terraform/bootstrap
terraform init
terraform workspace new "$AWS_REGION" 2>/dev/null || terraform workspace select "$AWS_REGION"
terraform apply
export TF_STATE_BUCKET="$(terraform output -raw bucket_name)"

cd ../cluster
terraform init -reconfigure \
  -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config="region=$AWS_REGION"
terraform apply
cd ../..
```

## 2. GCP infrastructure (terminal GCP)

Skip this step if the GCP cluster is already up.

```sh
cd gcp/terraform/bootstrap
terraform init
terraform workspace new "$TF_VAR_region" 2>/dev/null || terraform workspace select "$TF_VAR_region"
terraform apply
export TF_STATE_BUCKET="$(terraform output -raw bucket_name)"

cd ../cluster
terraform init -reconfigure -backend-config="bucket=$TF_STATE_BUCKET"
terraform apply
cd ../../..
```

## 3. Collect the egress IPs

Replication is gRPC between the frontends on 7233, **in both directions**. Each frontend's
allowlist needs the other cloud's NAT egress IP.

```sh
# terminal AWS: NAT gateway EIP
terraform -chdir=terraform/cluster state show 'module.vpc.aws_eip.nat[0]' | grep public_ip

# terminal GCP: Cloud NAT IP (reserved, static)
terraform -chdir=gcp/terraform/cluster output -raw nat_ip
```

Also note your own IP (`curl ifconfig.me`).

## 4. Fill in the overlays

Set `load-balancer-source-ranges` in each overlay. The overlay **replaces** the base annotation
rather than appending to it, so keep your own IPs in the list:

| File | Must contain |
|---|---|
| `aws-values.yaml` | your IPs + **GCP** Cloud NAT IP (replace `<GCP_NAT_IP>`) |
| `gcp-values.yaml` | your IPs + **AWS** NAT EIP (replace `<AWS_NAT_EIP>`) |

Both NAT IPs are static (an Elastic IP on AWS, a reserved address on GCP), so this is done once per
deployment. They only change if the infrastructure is destroyed and recreated.

## 5. Deploy Temporal on both clusters

The GCP cluster must start against empty databases. If Temporal was already installed there,
reset it first (terminal GCP):

```sh
helm uninstall temporal -n temporal
kubectl -n temporal run psql --rm -it --restart=Never --image=postgres:16 \
  --env=PGPASSWORD="$(terraform -chdir=gcp/terraform/cluster output -raw db_password)" \
  -- psql "host=$(terraform -chdir=gcp/terraform/cluster output -raw db_host) user=temporal dbname=postgres sslmode=require" \
  -c 'DROP DATABASE IF EXISTS temporal WITH (FORCE);' \
  -c 'DROP DATABASE IF EXISTS temporal_visibility WITH (FORCE);' \
  -c 'CREATE DATABASE temporal;'
```

`temporal_visibility` is recreated by the chart (`createDatabase: true`); `temporal` is not, so it
has to be created here.

Then deploy:

```sh
# terminal AWS
EXTRA_VALUES=global-namespace/aws-values.yaml,global-namespace/monitoring-values.yaml ./helm/deploy.sh

# terminal GCP
EXTRA_VALUES=global-namespace/gcp-values.yaml,global-namespace/monitoring-values.yaml ./gcp/helm/deploy.sh
```

`EXTRA_VALUES` takes a comma-separated list; Helm applies the files in order.
`monitoring-values.yaml` is optional. It adds Prometheus and Grafana to the same release (see
[Monitor replication lag](#monitor-replication-lag)).

Wait until all pods are `Running` on both sides: `kubectl -n temporal get pods`.

## 6. Connect the clusters

Get each frontend address:

```sh
# terminal AWS: NLB hostname
kubectl -n temporal get svc temporal-frontend -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# terminal GCP: IP
kubectl -n temporal get svc temporal-frontend -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

Register each cluster with the other:

```sh
# terminal AWS: points at GCP
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator cluster upsert --frontend-address <gcp-frontend-ip>:7233 --enable-connection

# terminal GCP: points at AWS
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator cluster upsert --frontend-address <aws-frontend-nlb>:7233 --enable-connection
```

Verify. Both sides must list `aws` and `gcp`:

```sh
kubectl -n temporal exec deploy/temporal-admintools -- temporal operator cluster list
```

If `upsert` times out, the other side's allowlist is missing your NAT IP (step 4).

## 7. Create a global namespace (terminal AWS)

```sh
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator namespace create --namespace replicated --global \
    --cluster aws --cluster gcp --active-cluster aws
```

Check that it replicated (terminal GCP):

```sh
kubectl -n temporal exec deploy/temporal-admintools -- \
  temporal operator namespace describe --namespace replicated
```

## 8. Run the failover demo

Java workers in both clouds, a long-running workflow, and a failover mid-run: see
[`demo/README.md`](demo/README.md).

## See the workflows in the UI

Each cluster's Web UI is on its own load balancer, port 8080:

```sh
# terminal AWS: NLB hostname
kubectl -n temporal get svc temporal-web -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# terminal GCP: IP
kubectl -n temporal get svc temporal-web -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

Open `http://<address>:8080/namespaces/replicated/workflows`. If your IP isn't in the allowlist,
run `kubectl -n temporal port-forward svc/temporal-web 8080:8080` and use `localhost:8080` instead.

## Monitor replication lag

With `monitoring-values.yaml`, each cluster runs its own Prometheus, which scrapes the Temporal
server pods, and a Grafana that opens on the **Temporal replication** dashboard:

```sh
kubectl -n temporal port-forward svc/grafana 3000:3000
```

Open http://localhost:3000. Each Grafana shows **its own cluster's** history metrics, so:

- **Lag from AWS to GCP** shows in GCP's Grafana, and **lag from GCP to AWS** in AWS's.
- **The backlog panel** shows the stream coming into this cluster. `from_cluster` and
  `to_cluster` are cluster IDs (the `initialFailoverVersion` values): `1` = aws, `2` = gcp.

| Panel | Metric | Watch for |
|---|---|---|
| Replication lag p95/p99 | `replication_latency` (created at source → applied here) | Above 5 s (orange) or 30 s (red). This is what an unplanned failover would lose |
| Transmission latency p95 | `replication_task_transmission_latency` | Close to the total lag: the bottleneck is the network or stream |
| Tasks applied / s | `replication_latency_count` | Drops to 0 while the active side has traffic |
| Stream backlog p95 | `replication_tasks_recv_backlog` | Sustained growth |
| Failed tasks / stream errors | `replication_tasks_failed`, `replication_stream_error` | Anything above 0. Stream errors usually mean the other frontend is unreachable (allowlist, NAT IP) |

The panels only have data once tasks replicate: run the [failover demo](demo/README.md) to
generate some.

## Tear down

Uninstall Temporal first, on both sides. The `LoadBalancer` services own cloud load balancers that
Terraform doesn't know about, and they block the VPC from being deleted:

```sh
helm uninstall temporal -n temporal
```

Then destroy each cluster's infrastructure:

```sh
# terminal AWS
cd terraform/cluster && terraform destroy

# terminal GCP
cd gcp/terraform/cluster && terraform destroy
```

## Notes

- Replication crosses the internet in plaintext, gated only by the IP allowlists. That's fine for
  a demo; anything real needs mTLS on the frontends or a VPN between the VPCs.
- `dcRedirectionPolicy: selected-apis-forwarding` forwards calls that reach the standby cluster
  to the active one, so clients (start, signal, update, query) can use either frontend. Workers
  get tasks only from the active cluster; run them in both clouds (see the demo).
- Each cluster has its own visibility store, rebuilt from replicated history, so lists on the
  standby can trail the active side briefly.
- `failoverVersionIncrement` must stay larger than the number of clusters; a third cluster would
  take `initialFailoverVersion: 3`.
