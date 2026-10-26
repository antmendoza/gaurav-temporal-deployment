# Base GCP identity for Temporal pods (the EKS pod-identity equivalent).
# Downstream modules grant roles to this GSA.
resource "google_service_account" "temporal" {
  account_id   = substr("${local.name}-wi", 0, 30)
  display_name = "Temporal pods (${local.name})"
}

resource "google_service_account_iam_member" "temporal_wi" {
  service_account_id = google_service_account.temporal.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.temporal_namespace}/${var.temporal_service_account}]"

  depends_on = [google_container_cluster.temporal]
}
