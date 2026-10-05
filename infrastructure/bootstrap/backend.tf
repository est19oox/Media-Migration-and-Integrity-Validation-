terraform {
  backend "s3" {
    bucket       = "mmiv-terraform-state-275069781906"
    key          = "bootstrap/terraform.tfstate"
    region       = "ap-southeast-1"
    encrypt      = true
    use_lockfile = true
  }
}
