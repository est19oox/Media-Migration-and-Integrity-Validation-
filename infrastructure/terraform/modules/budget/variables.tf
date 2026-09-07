variable "budget_name" {
  description = "Name of the AWS monthly budget"
  type        = string
  default     = "media-migration-monthly-budget"
}

variable "monthly_limit" {
  description = "Monthly AWS budget in USD"
  type        = number
  default     = 5
}

variable "alert_email" {
  description = "Email address that receives AWS budget alerts"
  type        = string
}