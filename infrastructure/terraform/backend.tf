terraform {
  backend "s3" {
    bucket       = "mmiv-terraform-state-741444304127"
    key          = "terraform.tfstate"
    region       = "ap-southeast-1"
    encrypt      = true
    use_lockfile = true
  }
}