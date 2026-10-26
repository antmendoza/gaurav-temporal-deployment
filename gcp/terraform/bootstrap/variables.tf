variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "name_suffix" {
  type        = string
  description = "Suffix for globally-scoped names (this bucket), e.g. your name. Press Enter to use the region."
}

variable "bucket_name" {
  type        = string
  description = "Overrides the derived state bucket name (temporal-dev-tfstate-<project>-<suffix>). Must be globally unique."
  default     = null
}
