output "vm_name" {
  description = "Name of the created VM."
  value       = google_compute_instance.windows_test_vm.name
}

output "vm_zone" {
  description = "Zone of the created VM."
  value       = google_compute_instance.windows_test_vm.zone
}

output "vm_external_ip" {
  description = "External IPv4 address for RDP."
  value       = google_compute_instance.windows_test_vm.network_interface[0].access_config[0].nat_ip
}

output "set_windows_password_command" {
  description = "Command to generate a Windows password for RDP login."
  value       = "gcloud compute reset-windows-password ${google_compute_instance.windows_test_vm.name} --zone ${google_compute_instance.windows_test_vm.zone} --user <USERNAME>"
}
