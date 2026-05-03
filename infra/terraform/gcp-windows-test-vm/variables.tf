variable "project_id" {
  description = "GCP project ID where the VM will be created."
  type        = string
}

variable "region" {
  description = "GCP region for provider-level operations."
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP zone where the VM will be created."
  type        = string
  default     = "us-central1-a"
}

variable "vm_name" {
  description = "Name of the Windows VM."
  type        = string
  default     = "schoolmanager-win-clean"
}

variable "machine_type" {
  description = "Compute machine type."
  type        = string
  default     = "e2-standard-4"
}

variable "boot_image" {
  description = "Source image for the Windows VM."
  type        = string
  default     = "projects/windows-cloud/global/images/family/windows-2022"
}

variable "boot_disk_size_gb" {
  description = "Boot disk size in GB."
  type        = number
  default     = 100
}

variable "boot_disk_type" {
  description = "Boot disk type."
  type        = string
  default     = "pd-standard"
}

variable "network" {
  description = "VPC network name."
  type        = string
  default     = "default"
}

variable "subnetwork" {
  description = "Subnetwork self-link or name. Set null to let GCP pick default for the region."
  type        = string
  default     = null
}

variable "network_tags" {
  description = "Network tags applied to VM; used by firewall targeting."
  type        = list(string)
  default     = ["allow-rdp-schoolmanager"]
}

variable "create_rdp_firewall_rule" {
  description = "Whether to create an RDP firewall rule."
  type        = bool
  default     = true
}

variable "rdp_source_ranges" {
  description = "CIDR ranges allowed to connect over RDP."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "service_account_email" {
  description = "Service account email to attach to the VM. Use default compute SA if null."
  type        = string
  default     = null
}

variable "service_account_scopes" {
  description = "OAuth scopes for the attached service account."
  type        = list(string)
  default     = ["https://www.googleapis.com/auth/cloud-platform"]
}
