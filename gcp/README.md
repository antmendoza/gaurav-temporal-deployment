# temporal-deployment — GCP

Deploy Temporal to GKE. A port of the AWS setup in the repo root. The Helm chart is shared: it is
the `helm-charts/` subtree at the repo root.

## Layout

- `terraform/bootstrap/`: the GCS bucket that holds remote state for `cluster/` (local state, applied once)
- `terraform/cluster/`: VPC + Cloud NAT, GKE, Cloud SQL (Postgres 16, private IP), Workload Identity GSA
- `helm/`: values + deploy script

There is no serverless-worker module. The Lambda + ExternalId model is AWS-only.

## Prerequisites

- gcloud, with `gke-gcloud-auth-plugin` (`gcloud components install gke-gcloud-auth-plugin`)
- Terraform >= 1.10
- kubectl, helm

## Set up your shell

```sh
gcloud auth login
gcloud auth application-default login   # Terraform reads ADC, not the gcloud login

export TF_VAR_project_id=<project> TF_VAR_region=europe-west1 -> export TF_VAR_project_id=dse-demos TF_VAR_region=europe-west1 
gcloud config set project "$TF_VAR_project_id"
```

## Bootstrap remote state

```sh
cd gcp/terraform/bootstrap
terraform init
terraform workspace new "$TF_VAR_region" 2>/dev/null || terraform workspace select "$TF_VAR_region"
terraform apply

export TF_STATE_BUCKET="$(terraform output -raw bucket_name)"
cd ../../..
```

## Bring up the cluster

```sh
cd gcp/terraform/cluster
terraform init -reconfigure -backend-config="bucket=$TF_STATE_BUCKET"
terraform apply
```

`name_suffix` works the same as on AWS: it is appended to every resource name. Answer it the same
way on every apply. The first apply takes about 15 minutes, most of it GKE and Cloud SQL.

Cloud SQL is reached through a Private Service Connect endpoint rather than private IP, because
the org policy `compute.restrictVpcPeering` denies all VPC peering in `dse-demos`.

## Deploy Temporal

```sh
./gcp/helm/deploy.sh
```

The script:

1. Runs `gcloud container clusters get-credentials` for the cluster.
2. Creates the `temporal` namespace if it's missing.
3. Annotates the namespace's `default` KSA with the Temporal GSA (Workload Identity).
4. Syncs the `temporal-db` secret from the `db_password` Terraform output.
5. Runs `helm upgrade --install` with `helm/values.yaml`, injecting the Cloud SQL private IP as
   `connectAddr`.

`CLUSTER_NAME`, `REGION`, `PROJECT_ID`, `NAMESPACE`, and `RELEASE` can be overridden with env vars.

The frontend and Web UI are external passthrough Network Load Balancers, restricted by the
`load-balancer-source-ranges` annotation in `values.yaml`.

## Open the Web UI

The script prints the Web UI IP at the end. Open `http://<ip>:8080`: plain http on port 8080,
not https.

If the page doesn't load:

1. **Your IP isn't on the allowlist.** Find it with `curl ifconfig.me`. If it isn't in
   `load-balancer-source-ranges`, add it under both `server.frontend.service` and `web.service`
   in `helm/values.yaml`, then re-run `./gcp/helm/deploy.sh`.
2. **The pods aren't ready yet.** The first start runs the schema setup, which takes a few
   minutes. Wait until `temporal-web` and `temporal-frontend` are `Running`:

   ```sh
   kubectl -n temporal get pods
   ```

To skip the allowlist, port-forward and open http://localhost:8080:

```sh
kubectl -n temporal port-forward svc/temporal-web 8080:8080
```

## Inspect the database

Use **Cloud SQL Studio**, the query editor in the console:
[open it for `temporal-dev-antonio-db`](https://console.cloud.google.com/sql/instances/temporal-dev-antonio-db/studio?project=dse-demos).

- Database: `temporal` (core state) or `temporal_visibility` (list/search)
- User: `temporal`
- Password: `terraform -chdir=gcp/terraform/cluster output -raw db_password`
- Requires `roles/cloudsql.studioUser` on the project, or an admin role that includes it

The instance has no public IP. If Studio can't connect, allow the Data API on the instance:

```sh
gcloud sql instances patch temporal-dev-antonio-db --data-api-access=ALLOW_DATA_API
```

Most Temporal state is in proto-encoded `data` blobs, so SQL shows which rows exist, not what
they contain. Useful starting points:

```sql
SELECT * FROM schema_version;
SELECT name, is_global FROM namespaces;
SELECT workflow_id, run_id, state, status FROM current_executions LIMIT 20;

-- in temporal_visibility
SELECT workflow_id, workflow_type_name, status, start_time, close_time
FROM executions_visibility ORDER BY start_time DESC LIMIT 20;
```

## Console links

These links are for project `dse-demos`, region `europe-west1`, `name_suffix` `antonio`. For
another deployment, change those three values in the URLs. `dse-demos` is shared, so the list pages
also show other people's resources; look for names starting with `temporal-dev-antonio`.

Infrastructure (`terraform/cluster`):

- [GKE cluster](https://console.cloud.google.com/kubernetes/clusters/details/europe-west1/temporal-dev-antonio/details?project=dse-demos)
- [Node pool](https://console.cloud.google.com/kubernetes/nodepool/europe-west1/temporal-dev-antonio/default?project=dse-demos)
- [Cloud SQL instance](https://console.cloud.google.com/sql/instances/temporal-dev-antonio-db/overview?project=dse-demos)
- [PSC endpoints](https://console.cloud.google.com/net-services/psc/list/consumers?project=dse-demos)
- [VPC](https://console.cloud.google.com/networking/networks/details/temporal-dev-antonio-vpc?project=dse-demos)
- [Subnet](https://console.cloud.google.com/networking/subnetworks/details/europe-west1/temporal-dev-antonio-nodes?project=dse-demos)
- [Cloud Router](https://console.cloud.google.com/hybrid/routers/details/europe-west1/temporal-dev-antonio-router?project=dse-demos)
- [Cloud NAT](https://console.cloud.google.com/net-services/nat/details/europe-west1/temporal-dev-antonio-router/temporal-dev-antonio-nat?project=dse-demos)
- [IP addresses](https://console.cloud.google.com/networking/addresses/list?project=dse-demos)
- [Service accounts](https://console.cloud.google.com/iam-admin/serviceaccounts?project=dse-demos) (`temporal-dev-antonio-wi`)
- [Terraform state bucket](https://console.cloud.google.com/storage/browser/temporal-dev-tfstate-dse-demos-antonio?project=dse-demos)

Temporal (`helm/deploy.sh`):

- [Workloads](https://console.cloud.google.com/kubernetes/workload/overview?project=dse-demos)
- [Services and their external IPs](https://console.cloud.google.com/kubernetes/discovery?project=dse-demos)
- [Load balancers](https://console.cloud.google.com/net-services/loadbalancing/list/loadBalancers?project=dse-demos)
- [Firewall rules](https://console.cloud.google.com/net-security/firewall-manager/firewall-policies/list?project=dse-demos) (the source-range allowlist)

## Tear down

```sh
cd gcp/terraform/cluster
terraform destroy
```
