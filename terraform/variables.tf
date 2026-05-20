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
