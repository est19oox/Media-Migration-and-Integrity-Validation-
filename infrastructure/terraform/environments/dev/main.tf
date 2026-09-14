module "budget" {
  source = "../../modules/budget"

  budget_name   = "media-migration-dev-budget"
  monthly_limit = 20
  alert_email   = var.budget_alert_email
}

module "s3" {
  source = "../../modules/s3"

  bucket_name = "media-migration-dev-landing"
}

module "iam" {
  source = "../../modules/iam"

  developers = [
    "media-migration-wang-shengxi",
    "media-migration-phang-zi-kai",
    "media-migration-wang-yutong",
    "media-migration-huang-haihong",
    "media-migration-zhao-youbo"
  ]

  bucket_arn = module.s3.bucket_arn
}