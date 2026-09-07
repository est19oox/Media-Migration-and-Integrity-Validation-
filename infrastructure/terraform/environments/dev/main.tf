module "budget" {
  source = "C:/Users/phang/OneDrive/Desktop/Media Migration and Integrity Validation/Media-Migration-and-Integrity-Validation-/infrastructure/terraform/modules/budget"

  budget_name   = "media-migration-dev-budget"
  monthly_limit = 20
  alert_email   = var.budget_alert_email
}