resource "random_password" "db" {
  length  = 32
  special = false
}

resource "google_sql_database_instance" "temporal" {
  name             = "${local.name}-db"
  database_version = "POSTGRES_16"
  region           = var.region

  deletion_protection = false

  settings {
    tier              = "db-custom-1-3840"
    edition           = "ENTERPRISE"
    availability_type = "ZONAL"
    disk_type         = "PD_SSD"
    disk_size         = 20
    disk_autoresize   = true

    ip_configuration {
      ipv4_enabled = false
      ssl_mode     = "ENCRYPTED_ONLY"

      psc_config {
        psc_enabled               = true
        allowed_consumer_projects = [var.project_id]
      }
    }

    backup_configuration {
      enabled = true
    }
  }
}

resource "google_sql_database" "temporal" {
  name     = "temporal"
  instance = google_sql_database_instance.temporal.name
}

resource "google_sql_user" "temporal" {
  name     = "temporal"
  instance = google_sql_database_instance.temporal.name
  password = random_password.db.result
}
