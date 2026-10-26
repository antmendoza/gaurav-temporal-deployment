data "aws_caller_identity" "current" {}

locals {
  # Same prompt/fallback as the cluster and serverless-worker modules.
  name_suffix = lower(replace(coalesce(trimspace(var.name_suffix), var.region), "/[^a-zA-Z0-9-]/", "-"))

  # S3 bucket names are globally unique, so scope by account as well as suffix.
  bucket_name = coalesce(
    var.bucket_name,
    trimsuffix(substr("temporal-dev-tfstate-${data.aws_caller_identity.current.account_id}-${local.name_suffix}", 0, 63), "-"),
  )
}