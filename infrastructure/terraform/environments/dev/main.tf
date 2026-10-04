# ============================================================
# AWS BUDGET
# ============================================================

module "budget" {
  source = "../../modules/budget"

  budget_name   = "media-migration-dev-budget"
  monthly_limit = 20
  alert_email   = var.budget_alert_email
}


# ============================================================
# S3 LANDING BUCKET
# ============================================================

module "s3" {
  source = "../../modules/s3"

  bucket_name = "media-migration-dev-landing"
  environment = "dev"

  academic_year = "AY2026-2027"

  semesters = [
    "Semester_1",
    "Semester_2"
  ]

  courses = [
    "CS2103",
    "CS2107",
    "CS2109"
  ]

  content_folders = [
    "Lectures",
    "Tutorials",
    "Labs",
    "Assignments"
  ]
}


# ============================================================
# IAM
# ============================================================

module "iam" {
  source = "../../modules/iam"

  csv_file = "../../../../config/iam_users.csv"

  bucket_arn  = module.s3.bucket_arn
  environment = "dev"
}