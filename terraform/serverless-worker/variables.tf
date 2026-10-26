variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "function_name" {
  type    = string
  default = "temporal-worker"
}

variable "state_bucket" {
  type        = string
  description = "S3 bucket holding the cluster module's state. Same value as the -backend-config bucket; export TF_VAR_state_bucket=\"$TF_STATE_BUCKET\"."
}

variable "name_suffix" {
  type        = string
  description = "Suffix for globally-scoped names (IAM roles), e.g. your name. Press Enter to use the region."
}

variable "external_id" {
  type        = string
  description = "ExternalId the caller must present when assuming the worker role. Configure the matching value in whoever invokes the role (Temporal Cloud namespace, IRSA-bound worker, etc.)."
  sensitive   = true
}

variable "trusted_principal_arn" {
  type        = string
  description = "ARN allowed to assume the worker role. Defaults to this account's root, which lets any IAM principal in the account assume it (gated by ExternalId). Replace with Temporal Cloud's AWS account root ARN once you have it."
  default     = null
}

variable "temporal_address" {
  type        = string
  description = "host:port of the Temporal frontend the Lambda dials. Set to the NLB DNS from `kubectl -n temporal get svc temporal-frontend` plus :7233."
}

