terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

resource "google_compute_instance" "windows_test_vm" {
  name         = var.vm_name
  machine_type = var.machine_type
  zone         = var.zone

  boot_disk {
    initialize_params {
      image = var.boot_image
      size  = var.boot_disk_size_gb
      type  = var.boot_disk_type
    }
  }

  network_interface {
    network    = var.network
    subnetwork = var.subnetwork

    access_config {}
  }

  tags = var.network_tags

  metadata = {
    enable-oslogin = "FALSE"
  }

  service_account {
    email  = var.service_account_email
    scopes = var.service_account_scopes
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }
}

resource "google_compute_firewall" "allow_rdp" {
  count = var.create_rdp_firewall_rule ? 1 : 0

  name    = "${var.vm_name}-allow-rdp"
  network = var.network

  allow {
    protocol = "tcp"
    ports    = ["3389"]
  }

  source_ranges = var.rdp_source_ranges
  target_tags   = var.network_tags
}
