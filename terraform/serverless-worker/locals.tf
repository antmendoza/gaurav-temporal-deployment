locals {
  # Names collide whenever two deployments land in the same account: IAM roles
  # are account-global, and everything else shares a region. Terraform prompts
  # for name_suffix; empty means region.
  name_suffix = lower(replace(coalesce(trimspace(var.name_suffix), var.region), "/[^a-zA-Z0-9-]/", "-"))

  # Every resource in this module is named off local.name, never var.function_name.
  name = "${var.function_name}-${local.name_suffix}"
}