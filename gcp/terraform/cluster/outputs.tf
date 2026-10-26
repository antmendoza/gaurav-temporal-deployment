output "cluster_name" {
  value = google_container_cluster.temporal.name
}

output "cluster_endpoint" {
  value = google_container_cluster.temporal.endpoint
}

output "project_id" {
  value = var.project_id
}

output "region" {
  value = var.region
}

output "db_host" {
  value = google_compute_address.sql_psc.address
}

output "db_port" {
  value = 5432
}

output "db_username" {
  value = google_sql_user.temporal.name
}

output "db_password" {
  value     = random_password.db.result
  sensitive = true
}

output "temporal_gsa_email" {
  value = google_service_account.temporal.email
}

output "nat_ip" {
  value = google_compute_address.nat.address
}
