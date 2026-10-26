locals {
  # Same prompt/fallback as the cluster module.
  name_suffix = lower(replace(coalesce(trimspace(var.name_suffix), var.region), "/[^a-zA-Z0-9-]/", "-"))

  # GCS bucket names are globally unique, so scope by project as well as suffix.
  bucket_name = coalesce(
    var.bucket_name,
    trimsuffix(substr("temporal-dev-tfstate-${var.project_id}-${local.name_suffix}", 0, 63), "-"),
  )
}
