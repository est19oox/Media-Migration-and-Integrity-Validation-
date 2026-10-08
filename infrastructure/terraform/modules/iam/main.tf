locals {
  csv_users_raw = csvdecode(file(var.csv_file))

  csv_users = {
    for user in local.csv_users_raw :
    user.username => user
  }

  permission_actions = {
    "s3-read" = [
      "s3:GetObject"
    ]

    "s3-read-write" = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]

    "s3-admin" = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:GetObjectVersion",
      "s3:DeleteObjectVersion"
    ]
  }

  project_admin_usernames = toset([
    "mm-dev-zhao-youbo",
    "mm-dev-wang-yutong"
  ])

  project_admin_policies = {
    ecr = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
    ec2 = "arn:aws:iam::aws:policy/AmazonEC2FullAccess"
    iam = "arn:aws:iam::aws:policy/IAMFullAccess"
    vpc = "arn:aws:iam::aws:policy/AmazonVPCFullAccess"
    s3  = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
  }
}


# ============================================================
# CSV-MANAGED IAM USERS
# ============================================================

resource "aws_iam_user" "users" {
  for_each = local.csv_users

  #checkov:skip=CKV_AWS_273:CSV-managed coursework identities are required until the team account enables IAM Identity Center.

  name = each.value.username

  tags = {
    Name        = each.value.name
    Role        = each.value.role
    Permission  = each.value.permission
    Project     = "Media-Migration"
    Environment = var.environment
    ManagedBy   = "Terraform-CSV"
  }
}


# ============================================================
# PERMISSION GROUPS
# ============================================================

resource "aws_iam_group" "permission_groups" {
  for_each = local.permission_actions

  name = "media-migration-${var.environment}-${each.key}"
}

resource "aws_iam_group" "project_admins" {
  name = "media-migration-${var.environment}-project-admins"
}

resource "aws_iam_group" "deployment_operators" {
  name = "media-migration-${var.environment}-deployment-operators"
}

resource "aws_iam_group" "cloudshell_users" {
  name = "media-migration-${var.environment}-cloudshell-users"
}


# ============================================================
# USER -> GROUP
# ============================================================

resource "aws_iam_user_group_membership" "users" {
  for_each = local.csv_users

  user = aws_iam_user.users[each.key].name

  groups = concat(
    [aws_iam_group.permission_groups[each.value.permission].name],
    contains(local.project_admin_usernames, each.key) ? [aws_iam_group.project_admins.name] : [],
    each.key == "mm-dev-wang-yutong" ? [
      aws_iam_group.deployment_operators.name,
      aws_iam_group.cloudshell_users.name
    ] : []
  )
}


# ============================================================
# S3 PERMISSION POLICIES
# ============================================================

resource "aws_iam_policy" "s3_permissions" {
  for_each = local.permission_actions

  name = "media-migration-${var.environment}-${each.key}"

  description = "Media Migration ${each.key} access to the ${var.environment} S3 bucket"

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Action = [
          "s3:ListAllMyBuckets"
        ]

        Resource = "*"
      },

      {
        Effect = "Allow"

        Action = [
          "s3:ListBucket",
          "s3:GetBucketLocation"
        ]

        Resource = var.bucket_arn
      },

      {
        Effect   = "Allow"
        Action   = each.value
        Resource = "${var.bucket_arn}/*"
      }
    ]
  })
}


# ============================================================
# S3 POLICY -> GROUP
# ============================================================

resource "aws_iam_group_policy_attachment" "s3_permissions" {
  for_each = local.permission_actions

  group = aws_iam_group.permission_groups[each.key].name

  policy_arn = aws_iam_policy.s3_permissions[
    each.key
  ].arn
}


# ============================================================
# ECR READ POLICY
# ============================================================

resource "aws_iam_policy" "ecr_read" {
  name        = "media-migration-${var.environment}-ecr-read"
  description = "Allows MMIV developers to pull container images from ECR"

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Action = [
          "ecr:GetAuthorizationToken"
        ]

        Resource = "*"
      },

      {
        Effect = "Allow"

        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage"
        ]

        Resource = var.ecr_repository_arn
      }
    ]
  })
}


# ============================================================
# ECR READ POLICY -> S3-READ GROUP
# ============================================================

resource "aws_iam_group_policy_attachment" "ecr_read" {
  group      = aws_iam_group.permission_groups["s3-read"].name
  policy_arn = aws_iam_policy.ecr_read.arn
}


# ============================================================
# PROJECT ADMIN ACCESS
# ============================================================

resource "aws_iam_group_policy_attachment" "project_admins" {
  for_each = local.project_admin_policies

  #checkov:skip=CKV2_AWS_56:The temporary coursework admin group needs IAM provisioning access until Identity Center permission sets replace it.

  group      = aws_iam_group.project_admins.name
  policy_arn = each.value
}


# ============================================================
# WANG YUTONG - SSM DEPLOYMENT ACCESS
# ============================================================

resource "aws_iam_policy" "deployment_ssm_access" {
  name        = "media-migration-${var.environment}-deployment-ssm"
  description = "Allows deployment operators to deploy MMIV through Systems Manager"

  #checkov:skip=CKV_AWS_290:The write action is limited to the regional AWS document and account EC2 instances; discovery APIs require wildcard resources.
  #checkov:skip=CKV_AWS_355:AWS Describe and command-result APIs in this policy do not support resource-level permissions.

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Sid    = "DiscoverDeploymentResources"
        Effect = "Allow"

        Action = [
          "ec2:DescribeInstances",
          "s3:ListAllMyBuckets"
        ]

        Resource = "*"
      },

      {
        Sid    = "SendDeploymentCommand"
        Effect = "Allow"

        Action = [
          "ssm:SendCommand"
        ]

        Resource = [
          "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript",
          "arn:aws:ec2:${var.aws_region}:${var.aws_account_id}:instance/*"
        ]
      },

      {
        Sid    = "ReadDeploymentCommandResult"
        Effect = "Allow"

        Action = [
          "ssm:StartSession",
          "ssm:ResumeSession",
          "ssm:TerminateSession",
          "ssm:DescribeSessions",
          "ssm:GetConnectionStatus",
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations"
        ]

        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_group_policy_attachment" "deployment_ssm_access" {
  group      = aws_iam_group.deployment_operators.name
  policy_arn = aws_iam_policy.deployment_ssm_access.arn
}

# ============================================================
# WANG YUTONG - CLOUDSHELL ACCESS
# ============================================================
# Full access to AWS CloudShell.
resource "aws_iam_group_policy_attachment" "wang_yutong_cloudshell_full_access" {
  group      = aws_iam_group.cloudshell_users.name
  policy_arn = "arn:aws:iam::aws:policy/AWSCloudShellFullAccess"
}
