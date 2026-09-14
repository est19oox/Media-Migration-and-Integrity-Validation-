output "developer_user_names" {
  description = "Names of the developer IAM users"
  value       = [for user in aws_iam_user.developers : user.name]
}

output "developer_group_name" {
  description = "Name of the developer IAM group"
  value       = aws_iam_group.developers.name
}

output "s3_policy_arn" {
  description = "ARN of the developer S3 access policy"
  value       = aws_iam_policy.s3_access.arn
}