# Base AWS identity for Temporal pods. Downstream workload modules
# (e.g. serverless-worker) attach AssumeRole permissions onto this role.
resource "aws_iam_role" "temporal_pod_identity" {
  name = "${local.name}-pod-identity"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_eks_pod_identity_association" "temporal" {
  cluster_name    = module.eks.cluster_name
  namespace       = var.temporal_namespace
  service_account = var.temporal_service_account
  role_arn        = aws_iam_role.temporal_pod_identity.arn
}
