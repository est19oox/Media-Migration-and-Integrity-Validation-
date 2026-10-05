output "iam_users" {
  description = "CSV-managed IAM users"

  value = {
    for username, user in aws_iam_user.users :
    username => {
      name = user.name
      arn  = user.arn
    }
  }
}

output "iam_groups" {
  description = "IAM permission groups"

  value = {
    for permission, group in aws_iam_group.permission_groups :
    permission => group.name
  }
}

output "iam_policy_arns" {
  description = "S3 permission policy ARNs"

  value = {
    for permission, policy in aws_iam_policy.s3_permissions :
    permission => policy.arn
  }
}

output "user_permissions" {
  description = "Permission assigned to each CSV user"

  value = {
    for username, user in local.csv_users :
    username => user.permission
  }
}