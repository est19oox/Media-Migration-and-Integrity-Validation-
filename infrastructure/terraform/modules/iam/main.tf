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
}


# ============================================================
# CSV-MANAGED IAM USERS
# ============================================================

resource "aws_iam_user" "users" {
  for_each = local.csv_users

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


# ============================================================
# USER -> GROUP
# ============================================================

resource "aws_iam_user_group_membership" "users" {
  for_each = local.csv_users

  user = aws_iam_user.users[each.key].name

  groups = [
    aws_iam_group.permission_groups[
      each.value.permission
    ].name
  ]
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
# ZHAO YOUBO - PROJECT ADMIN ACCESS
# ============================================================

# Full access to Amazon ECR.
resource "aws_iam_user_policy_attachment" "zhao_ecr_full_access" {
  user       = aws_iam_user.users["mm-dev-zhao-youbo"].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}

# Full access to Amazon EC2.
resource "aws_iam_user_policy_attachment" "zhao_ec2_full_access" {
  user       = aws_iam_user.users["mm-dev-zhao-youbo"].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2FullAccess"
}

# Full access to IAM.
resource "aws_iam_user_policy_attachment" "zhao_iam_full_access" {
  user       = aws_iam_user.users["mm-dev-zhao-youbo"].name
  policy_arn = "arn:aws:iam::aws:policy/IAMFullAccess"
}

# Full access to Amazon VPC.
resource "aws_iam_user_policy_attachment" "zhao_vpc_full_access" {
  user       = aws_iam_user.users["mm-dev-zhao-youbo"].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonVPCFullAccess"
}

# Full access to Amazon S3.
resource "aws_iam_user_policy_attachment" "zhao_s3_full_access" {
  user       = aws_iam_user.users["mm-dev-zhao-youbo"].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}
