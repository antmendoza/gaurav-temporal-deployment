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

- AWS CLI v2, an SSO profile for the target account (`aws configure sso`)
- Terraform >= 1.10 (for native S3 state locking via `use_lockfile`)
- kubectl, helm

## Set up your shell

Run this first, in every new terminal — every step below depends on it:

```sh
export AWS_PROFILE=<your-sso-profile>
export AWS_REGION=eu-west-3 TF_VAR_region=eu-west-3 REGION=eu-west-3

aws sso login
aws sts get-caller-identity   # must print the account you expect
```

Don't skip the last line. Terraform reads credentials from the environment, so an
unset `AWS_PROFILE` falls back to `[default]` — often a different account — and
surfaces minutes later as a confusing `403 ForbiddenException`.

## Bootstrap remote state

`cluster/` and `serverless-worker/` both write state to an S3 bucket named
`temporal-dev-tfstate-<account>-<suffix>`, where `<suffix>` is the `name_suffix`
you're prompted for. Create it once before initializing the other modules:

```sh
cd terraform/bootstrap
terraform init
terraform workspace new "$AWS_REGION" 2>/dev/null || terraform workspace select "$AWS_REGION"
terraform apply

export TF_STATE_BUCKET="$(terraform output -raw bucket_name)"
cd ../..
```

Reusing a bucket that already exists? Skip this and set `TF_STATE_BUCKET` by hand.

The backend blocks in `cluster/` and `serverless-worker/` deliberately omit
`bucket` and `region` so there's no stale default to inherit — both are passed
via `-backend-config` at init time, as shown in the next step.

The bootstrap module itself uses local state, one workspace per region — its
only resource is the bucket, and if you ever lose the state file you can
re-import it. Set `-var bucket_name=...` to override the derived name.

## Bring up the cluster

```sh
cd terraform/cluster
terraform init -reconfigure \
  -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config="region=$AWS_REGION"
terraform apply
aws eks update-kubeconfig \
  --name "$(terraform output -raw cluster_name)" \
  --region "$AWS_REGION" \
  --role-arn "$(terraform output -raw eks_admin_role_arn)"
kubectl get nodes
```

The `--role-arn` flag bakes the `temporal-dev-<suffix>-eks-admin` role into the kubeconfig's
exec plugin, so `kubectl` auto-assumes it on every call. See "EKS admin access"
below for why.

`terraform apply` prompts for `name_suffix`, which is appended to every resource
name — two deployments in one account collide otherwise, since IAM role names are
account-global. Press Enter to use the region, or type a name (yours, a ticket
number). Answer it the same way on every apply for a given deployment: changing
it renames everything, which Terraform does by destroy-and-recreate. Set
`TF_VAR_name_suffix` or pin it in `terraform.tfvars` to avoid the prompt.

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

## Console links

These links are for region `eu-west-3` and `name_suffix` `antonio`. For another deployment,
change those two values in the URLs. Resource names start with `temporal-dev-antonio`.

Infrastructure (`terraform/cluster`):

- [EKS cluster](https://eu-west-3.console.aws.amazon.com/eks/clusters/temporal-dev-antonio?region=eu-west-3)
- [Node group](https://eu-west-3.console.aws.amazon.com/eks/clusters/temporal-dev-antonio?region=eu-west-3&selectedTab=cluster-compute-tab)
- [Node instances](https://eu-west-3.console.aws.amazon.com/ec2/home?region=eu-west-3#Instances:tag:eks:cluster-name=temporal-dev-antonio)
- [RDS instance](https://eu-west-3.console.aws.amazon.com/rds/home?region=eu-west-3#database:id=temporal-dev-antonio-db)
- [VPC](https://eu-west-3.console.aws.amazon.com/vpcconsole/home?region=eu-west-3#vpcs:tag:Name=temporal-dev-antonio-vpc)
- [NAT gateways](https://eu-west-3.console.aws.amazon.com/vpcconsole/home?region=eu-west-3#NatGateways:)
- [Elastic IPs](https://eu-west-3.console.aws.amazon.com/ec2/home?region=eu-west-3#Addresses:) (the NAT egress IP)
- [IAM role `temporal-dev-antonio-eks-admin`](https://us-east-1.console.aws.amazon.com/iam/home#/roles/details/temporal-dev-antonio-eks-admin)
- [IAM role `temporal-dev-antonio-pod-identity`](https://us-east-1.console.aws.amazon.com/iam/home#/roles/details/temporal-dev-antonio-pod-identity)
- [S3 buckets](https://eu-west-3.console.aws.amazon.com/s3/buckets?region=eu-west-3) (state bucket: `temporal-dev-tfstate-<account>-antonio`)

Temporal (`helm/deploy.sh`):

- [Workloads](https://eu-west-3.console.aws.amazon.com/eks/clusters/temporal-dev-antonio?region=eu-west-3&selectedTab=cluster-resources-tab)
- [Load balancers](https://eu-west-3.console.aws.amazon.com/ec2/home?region=eu-west-3#LoadBalancers:) (frontend and Web UI NLBs)
- [Security groups](https://eu-west-3.console.aws.amazon.com/ec2/home?region=eu-west-3#SecurityGroups:) (the source-range allowlist)

## Tear down

```sh
cd terraform/cluster
terraform destroy
```

## AWS auth notes (SSO)

`aws`, `kubectl`, `helm` and `terraform` all work straight off the SSO session
once `AWS_PROFILE` is exported (see "Set up your shell"). Nothing here needs
long-lived access keys — if your account uses IAM Identity Center there is no IAM
user to create them for.

Two failure modes worth telling apart:

- `Token has expired and refresh failed` — the session lapsed. `aws sso login`.
- `403 ForbiddenException: No access` on `GetRoleCredentials` — the token is
  valid but the profile's `sso_account_id`/`sso_role_name` isn't assigned to you.
  Usually a stale profile pointing at an old account; re-run `aws configure sso`
  to see what you actually have.

If you instead export static credentials with `aws configure export-credentials`,
they're pinned at export time. When the session expires, `unset
AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
AWS_CREDENTIAL_EXPIRATION`, re-login, and re-export — otherwise every tool keeps
reading the stale token.

## EKS admin access

The cluster grants admin to a stable IAM role (`temporal-dev-<suffix>-eks-admin`) rather
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
