variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "cluster_name" {
  type    = string
  default = "temporal-dev"
}

variable "kubernetes_version" {
  type    = string
  default = "1.35"
}

variable "temporal_namespace" {
  type        = string
  description = "Namespace Temporal runs in. The pod-identity association binds this namespace's SA to the temporal-pod-identity role."
  default     = "temporal"
}

variable "temporal_service_account" {
  type        = string
  description = "Service account Temporal pods run as."
  default     = "default"
}
