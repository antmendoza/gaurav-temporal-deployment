#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-temporal}"
RELEASE="${RELEASE:-temporal}"
CLUSTER_NAME="${CLUSTER_NAME:-temporal-dev}"
REGION="${REGION:-ap-southeast-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CHART_DIR="$REPO_ROOT/helm-charts/charts/temporal"
VALUES_FILE="$SCRIPT_DIR/values.yaml"
TF_DIR="$REPO_ROOT/terraform/cluster"

EKS_ADMIN_ROLE_ARN="$(terraform -chdir="$TF_DIR" output -raw eks_admin_role_arn)"
aws eks update-kubeconfig \
  --name "$CLUSTER_NAME" \
  --region "$REGION" \
  --role-arn "$EKS_ADMIN_ROLE_ARN"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
  || kubectl create namespace "$NAMESPACE"

DB_PASSWORD="$(terraform -chdir="$TF_DIR" output -raw db_password)"
kubectl create secret generic temporal-db \
  --namespace "$NAMESPACE" \
  --from-literal=password="$DB_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$VALUES_FILE" \
  --timeout 900s

echo
echo "Web UI hostname:"
kubectl -n "$NAMESPACE" get svc temporal-web \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
echo
