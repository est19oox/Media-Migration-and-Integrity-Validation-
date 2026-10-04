# ============================================================
# S3 BUCKET ARN
# ============================================================

output "bucket_arn" {
  description = "ARN of the S3 landing bucket"
  value       = aws_s3_bucket.landing.arn
}


# ============================================================
# S3 BUCKET NAME
# ============================================================

output "bucket_name" {
  description = "Name of the S3 landing bucket"
  value       = aws_s3_bucket.landing.bucket
}


# ============================================================
# S3 FOLDER PREFIXES
# ============================================================

output "folder_prefixes" {
  description = "S3 folder prefixes created in the landing bucket"
  value       = sort(keys(local.folder_map))
}