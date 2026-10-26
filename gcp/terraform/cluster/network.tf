resource "google_compute_network" "vpc" {
  name                    = "${local.name}-vpc"
  auto_create_subnetworks = false

  depends_on = [google_project_service.this]
}

resource "google_compute_subnetwork" "nodes" {
  name                     = "${local.name}-nodes"
  network                  = google_compute_network.vpc.id
  region                   = var.region
  ip_cidr_range            = "10.0.0.0/20"
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.4.0.0/14"
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.8.0.0/20"
  }
}

# Private nodes have no external IPs; NAT gives them egress for image pulls.
resource "google_compute_router" "nat" {
  name    = "${local.name}-router"
  network = google_compute_network.vpc.id
  region  = var.region
}

# Reserved so the egress IP is stable: the AWS frontend allowlists it for replication.
resource "google_compute_address" "nat" {
  name   = "${local.name}-nat"
  region = var.region
}

resource "google_compute_router_nat" "nat" {
  name                               = "${local.name}-nat"
  router                             = google_compute_router.nat.name
  region                             = var.region
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = [google_compute_address.nat.self_link]
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# Cloud SQL is reached through a Private Service Connect endpoint, not VPC
# peering: the org policy compute.restrictVpcPeering denies all peering.
resource "google_compute_address" "sql_psc" {
  name         = "${local.name}-sql-psc"
  region       = var.region
  subnetwork   = google_compute_subnetwork.nodes.id
  address_type = "INTERNAL"
}

resource "google_compute_forwarding_rule" "sql_psc" {
  name                  = "${local.name}-sql-psc"
  region                = var.region
  network               = google_compute_network.vpc.id
  ip_address            = google_compute_address.sql_psc.id
  target                = google_sql_database_instance.temporal.psc_service_attachment_link
  load_balancing_scheme = ""
}
