data "aws_caller_identity" "current" {}

# Stable IAM role for cluster admin access. SSO users assume this role before
# running kubectl/helm/terraform, so cluster auth survives SSO permission-set
# rotation.
resource "aws_iam_role" "eks_admin" {
  name = "${var.cluster_name}-eks-admin"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringLike = {
          "aws:PrincipalArn" = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/aws-reserved/sso.amazonaws.com/*/AWSReservedSSO_AWSAdministratorAccess_*"
        }
      }
    }]
  })
}
