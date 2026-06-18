variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "bucket_name" {
  type        = string
  description = "Name of the S3 bucket used to store remote Terraform state for the cluster and serverless-worker modules. Must be globally unique."
  default     = "temporal-dev-tfstate"
}
