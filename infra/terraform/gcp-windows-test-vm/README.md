# GCP Windows VM (Terraform)

This Terraform config creates a disposable Windows VM on GCP for testing `setup.bat` in a clean environment.

## Prerequisites

- Terraform `>= 1.5`
- Google Cloud SDK (`gcloud`) installed
- A GCP project with Compute Engine API enabled
- Authenticated CLI session:

```powershell
gcloud auth application-default login
gcloud config set project <YOUR_PROJECT_ID>
```

## Configure

```powershell
cd infra/terraform/gcp-windows-test-vm
copy terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`:
- Set `project_id`
- Optionally change `region`, `zone`, `machine_type`
- For security, set `rdp_source_ranges` to your public IP CIDR (for example `["203.0.113.10/32"]`)

## Create VM

```powershell
terraform init
terraform plan
terraform apply
```

After apply:
- Get external IP from Terraform output.
- Generate Windows credentials:

```powershell
gcloud compute reset-windows-password <VM_NAME> --zone <ZONE> --user <USERNAME>
```

Then RDP to the VM.

## Destroy VM

```powershell
terraform destroy
```

Use this when done to avoid ongoing Windows VM costs.
