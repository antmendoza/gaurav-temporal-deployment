# temporal-deployment

Deploy Temporal to AWS EKS.

## Layout

- `terraform/` — VPC and a small EKS cluster
- `helm-charts/` — git subtree of [temporalio/helm-charts](https://github.com/temporalio/helm-charts) (`main`, squashed)

## Updating the helm-charts subtree

Pull the latest upstream `main`:

```sh
git subtree pull --prefix=helm-charts git@github.com:temporalio/helm-charts.git main --squash
```

## Prerequisites

- AWS CLI configured (`aws configure`)
- Terraform >= 1.5
- kubectl

## Bring up the cluster

```sh
cd terraform
terraform init
terraform apply
aws eks update-kubeconfig --name temporal-dev --region ap-southeast-2
kubectl get nodes
```

## Tear down

```sh
cd terraform
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
