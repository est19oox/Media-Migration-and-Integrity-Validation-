output "bucket_arn" {
  description = "ARN of the S3 landing bucket"
  value       = aws_s3_bucket.landing.arn
}

output "bucket_name" {
  description = "Name of the S3 landing bucket"
  value       = aws_s3_bucket.landing.bucket
}