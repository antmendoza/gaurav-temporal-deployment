locals {
  # Names collide whenever two deployments land in the same project. Terraform
  # prompts for name_suffix; empty means region.
  name_suffix = lower(replace(coalesce(trimspace(var.name_suffix), var.region), "/[^a-zA-Z0-9-]/", "-"))

  # Every resource in this module is named off local.name, never var.cluster_name.
  name = "${var.cluster_name}-${local.name_suffix}"
}
