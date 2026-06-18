# temporal-deployment

Deploy Temporal to AWS EKS.

## Layout

- `terraform/bootstrap/` — S3 bucket holding remote state for the other two modules (local state, applied once)
- `terraform/cluster/` — VPC, EKS, RDS, IAM
- `terraform/serverless-worker/` — Lambda + IAM role Temporal can assume to invoke it
- `worker/` — Lambda handler source (zipped at apply time)
- `helm/` — values + scripts for installing Temporal via Helm
- `helm-charts/` — git subtree of [temporalio/helm-charts](https://github.com/temporalio/helm-charts) (`main`, squashed)

## Updating the helm-charts subtree

Pull the latest upstream `main`:

```sh
git subtree pull --prefix=helm-charts git@github.com:temporalio/helm-charts.git main --squash
```

## Prerequisites

- AWS CLI configured (`aws configure`)
- Terraform >= 1.10 (for native S3 state locking via `use_lockfile`)
- kubectl

## Bootstrap remote state

`cluster/` and `serverless-worker/` both write state to S3 bucket
`temporal-dev-tfstate` in `ap-southeast-2`. Create it once before initializing
the other modules:

```sh
cd terraform/bootstrap
terraform init
terraform apply
```

The bootstrap module itself uses local state — its only resource is the bucket,
and if you ever lose the state file you can re-import the bucket. Change the
default `bucket_name` (in `terraform/bootstrap/variables.tf`) if you hit an S3
global-namespace collision, and update the matching `bucket = ...` lines in
`terraform/cluster/versions.tf` and `terraform/serverless-worker/versions.tf`.

## Bring up the cluster

```sh
cd terraform/cluster
terraform init
terraform apply
aws eks update-kubeconfig --name temporal-dev --region ap-southeast-2 \
  --role-arn "$(terraform output -raw eks_admin_role_arn)"
kubectl get nodes
```

The `--role-arn` flag bakes the `temporal-dev-eks-admin` role into the kubeconfig's
exec plugin, so `kubectl` auto-assumes it on every call. See "EKS admin access"
below for why.

## Deploy Temporal

```sh
./helm/deploy.sh
```

The script:

1. Runs `aws eks update-kubeconfig` against the `temporal-dev` cluster, baking
   in the `eks_admin_role_arn` from the cluster Terraform output so subsequent
   `kubectl`/`helm` calls auto-assume the admin role.
2. Creates the `temporal` namespace if missing.
3. Syncs the `temporal-db` secret from the `db_password` Terraform output.
4. `helm upgrade --install`s the chart from the vendored subtree with
   `helm/values.yaml`.

Idempotent — safe to re-run. `CLUSTER_NAME`, `REGION`, `NAMESPACE`, and
`RELEASE` are overridable via env vars.

`helm/values.yaml` exposes both the frontend gRPC service and the Web UI as
internet-facing NLBs. The script prints the Web UI hostname at the end; open
`http://<hostname>:8080` in a browser.

## Tear down

```sh
cd terraform/cluster
terraform destroy
```

## AWS auth notes (SSO)

`aws` CLI and `kubectl` work straight off your SSO session — just `aws login` (or `aws sso login`) and you're set.

Terraform is different: the AWS provider can't read the new `login_session` config, so you must export your SSO creds into env vars in the same shell:

```sh
aws login
eval "$(aws configure export-credentials --format env)"
terraform apply
```

Gotcha: those env vars are pinned at export time. When the SSO session expires, `unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_CREDENTIAL_EXPIRATION`, re-login, and re-export — otherwise `kubectl` and `aws` will keep reading the stale token.

## EKS admin access

The cluster grants admin to a stable IAM role (`temporal-dev-eks-admin`) rather
than to your SSO permission-set role directly. SSO permission sets get a fresh
underlying IAM role ARN whenever they're re-provisioned — pinning the SSO ARN
in `access_entries` would lock you out the next time that happens. The stable
role's trust policy uses a `StringLike` condition on `aws:PrincipalArn` that
matches any `AWSReservedSSO_AWSAdministratorAccess_*` principal in this account,
so SSO churn doesn't break access.

The `--role-arn` flag in `aws eks update-kubeconfig` (above) handles assume-role
transparently for `kubectl`, `helm`, and `k9s` — every API call goes through the
exec plugin which signs as the admin role.

`terraform` and other `aws` CLI commands use your regular SSO session and don't
need to assume this role — your SSO user already has the AWS permissions they
need.
