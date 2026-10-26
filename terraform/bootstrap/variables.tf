variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "name_suffix" {
  type        = string
  description = "Suffix for globally-scoped names (IAM roles, this bucket), e.g. your name. Press Enter to use the region."
}

variable "bucket_name" {
  type        = string
  description = "Overrides the derived state bucket name (temporal-dev-tfstate-<account>-<suffix>). Must be globally unique."
  default     = null
}
