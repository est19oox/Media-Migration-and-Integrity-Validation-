# Outputs the MMIV EC2 instance ID for deployment.
output "mmiv_instance_id" {
  value = aws_instance.mmiv.id
}

# Outputs the migration S3 bucket name for deployment.
output "migration_bucket_name" {
  value = aws_s3_bucket.migration.bucket
}