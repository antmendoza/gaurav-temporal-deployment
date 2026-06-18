output "bucket_name" {
  value       = aws_s3_bucket.tfstate.id
  description = "Name of the S3 bucket holding remote Terraform state. Plug this into the backend.hcl files for the cluster and serverless-worker modules."
}

output "region" {
  value       = var.region
  description = "Region the state bucket lives in."
}
