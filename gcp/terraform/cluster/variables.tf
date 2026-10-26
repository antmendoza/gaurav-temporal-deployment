variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "cluster_name" {
  type    = string
  default = "temporal-dev"
}

variable "name_suffix" {
  type        = string
  description = "Suffix for resource names, e.g. your name. Press Enter to use the region."
}

variable "temporal_namespace" {
  type        = string
  description = "Namespace Temporal runs in. Workload Identity binds this namespace's SA to the temporal GSA."
  default     = "temporal"
}

variable "temporal_service_account" {
  type        = string
  description = "Service account Temporal pods run as."
  default     = "default"
}
