output "bucket_name" {
  value       = google_storage_bucket.tfstate.name
  description = "GCS bucket holding remote Terraform state. Pass it to the cluster module via -backend-config."
}

output "region" {
  value = var.region
}
