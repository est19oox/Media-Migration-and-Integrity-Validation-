variable "developers" {
  description = "IAM users for the development team"
  type        = set(string)
}

variable "bucket_arn" {
  description = "ARN of the S3 landing bucket"
  type        = string
}