terraform {
  required_version = ">= 1.10"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.0"
    }
  }

  # bucket comes from -backend-config; see "Bootstrap remote state". GCS locks natively.
  backend "gcs" {
    prefix = "cluster"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
