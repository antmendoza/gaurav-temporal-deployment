#!/usr/bin/env bash
set -euo pipefail

CLOUD="${1:?usage: deploy-worker.sh aws|gcp}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TAG="${TAG:-$(date +%Y%m%d%H%M%S)}"

case "$CLOUD" in
  aws)
    TF_DIR="$REPO_ROOT/terraform/cluster"
    REGION="$(terraform -chdir="$TF_DIR" output -raw region)"
    ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
    REGISTRY="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
    IMAGE="$REGISTRY/temporal-failover-worker:$TAG"
    aws ecr describe-repositories --region "$REGION" --repository-names temporal-failover-worker >/dev/null 2>&1 \
      || aws ecr create-repository --region "$REGION" --repository-name temporal-failover-worker >/dev/null
    aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
    aws eks update-kubeconfig \
      --name "$(terraform -chdir="$TF_DIR" output -raw cluster_name)" \
      --region "$REGION" \
      --role-arn "$(terraform -chdir="$TF_DIR" output -raw eks_admin_role_arn)"
    ;;
  gcp)
    TF_DIR="$REPO_ROOT/gcp/terraform/cluster"
    REGION="$(terraform -chdir="$TF_DIR" output -raw region)"
    PROJECT="$(terraform -chdir="$TF_DIR" output -raw project_id)"
    REGISTRY="$REGION-docker.pkg.dev"
    IMAGE="$REGISTRY/$PROJECT/temporal-demo/failover-worker:$TAG"
    gcloud artifacts repositories describe temporal-demo --project "$PROJECT" --location "$REGION" >/dev/null 2>&1 \
      || gcloud artifacts repositories create temporal-demo --project "$PROJECT" --location "$REGION" --repository-format docker
    gcloud auth configure-docker "$REGISTRY" --quiet
    gcloud container clusters get-credentials \
      "$(terraform -chdir="$TF_DIR" output -raw cluster_name)" \
      --region "$REGION" --project "$PROJECT"
    ;;
  *)
    echo "usage: deploy-worker.sh aws|gcp" >&2
    exit 1
    ;;
esac

# Both clusters run amd64 nodes; build for them regardless of the laptop's arch.
docker build --platform linux/amd64 -t "$IMAGE" "$SCRIPT_DIR"
# Layers the registry already has are skipped, so a retry only re-sends what didn't finish.
for attempt in 1 2 3 4 5; do
  docker push "$IMAGE" && break
  [ "$attempt" = 5 ] && exit 1
  echo "push failed (attempt $attempt/5), retrying in 5s..." >&2
  sleep 5
done

IMAGE="$IMAGE" CLOUD="$CLOUD" envsubst '${IMAGE} ${CLOUD}' < "$SCRIPT_DIR/k8s/worker.yaml" | kubectl apply -f -
kubectl -n temporal rollout status deployment/failover-worker --timeout=180s
