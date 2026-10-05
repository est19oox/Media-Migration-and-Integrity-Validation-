# Gets information about the AWS account currently running Terraform.
data "aws_caller_identity" "current" {}


# ------------------------------------------------------------
# GitHub OIDC Provider
# ------------------------------------------------------------

# Allows GitHub Actions to authenticate to AWS using OIDC
# instead of long-lived AWS access keys.
resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = [
    "sts.amazonaws.com"
  ]

  tags = {
    Name      = "GitHub Actions OIDC"
    Project   = "MMIV"
    ManagedBy = "Terraform"
  }
}


# ------------------------------------------------------------
# GitHub Actions - Terraform Role
# ------------------------------------------------------------

# Role used by the Terraform GitHub Actions workflow.
resource "aws_iam_role" "github_terraform" {
  name = "GitHubActions-MMIV-Terraform"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }

        Action = "sts:AssumeRoleWithWebIdentity"

        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }

          StringLike = {
            "token.actions.githubusercontent.com:sub" = "repo:est19oox/Media-Migration-and-Integrity-Validation-:ref:refs/heads/main"
          }
        }
      }
    ]
  })

  tags = {
    Name      = "GitHub Actions MMIV Terraform"
    Project   = "MMIV"
    ManagedBy = "Terraform"
  }
}


# Allows Terraform GitHub Actions to manage ECR.
resource "aws_iam_role_policy_attachment" "github_terraform_ecr" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}


# Allows Terraform GitHub Actions to manage EC2.
resource "aws_iam_role_policy_attachment" "github_terraform_ec2" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2FullAccess"
}


# Allows Terraform GitHub Actions to manage IAM.
resource "aws_iam_role_policy_attachment" "github_terraform_iam" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/IAMFullAccess"
}


# Allows Terraform GitHub Actions to manage VPC resources.
resource "aws_iam_role_policy_attachment" "github_terraform_vpc" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonVPCFullAccess"
}


# Allows Terraform GitHub Actions to manage S3.
resource "aws_iam_role_policy_attachment" "github_terraform_s3" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}


# ------------------------------------------------------------
# GitHub Actions - Application Role
# ------------------------------------------------------------

# Role used by the application deployment workflow.
resource "aws_iam_role" "github_app" {
  name = "GitHubActions-MMIV-App"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }

        Action = "sts:AssumeRoleWithWebIdentity"

        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }

          StringLike = {
            "token.actions.githubusercontent.com:sub" = "repo:est19oox/Media-Migration-and-Integrity-Validation-:ref:refs/heads/main"
          }
        }
      }
    ]
  })

  tags = {
    Name      = "GitHub Actions MMIV App"
    Project   = "MMIV"
    ManagedBy = "Terraform"
  }
}


# Allows the application workflow to push Docker images to ECR.
resource "aws_iam_role_policy_attachment" "github_app_ecr" {
  role       = aws_iam_role.github_app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}


# Allows the application workflow to discover deployment resources
# and deploy the application to EC2 through Systems Manager.
resource "aws_iam_role_policy" "github_app_s3_deploy" {
  name = "MMIVAppS3DeployPolicy"
  role = aws_iam_role.github_app.name

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetBucketLocation",
          "s3:ListBucket"
        ]
        Resource = "arn:aws:s3:::media-migration-dev-landing"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject"
        ]
        Resource = "arn:aws:s3:::media-migration-dev-landing/*"
      }
    ]
  })
}


resource "aws_iam_role_policy" "github_app_ssm_deploy" {
  name = "MMIVAppSSMDeployPolicy"
  role = aws_iam_role.github_app.name

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

        Action = "ssm:SendCommand"

        Resource = [
          "arn:aws:ssm:ap-southeast-1::document/AWS-RunShellScript",
          "arn:aws:ec2:ap-southeast-1:${data.aws_caller_identity.current.account_id}:instance/*"
        ]
      },

      {
        Sid    = "ReadDeploymentCommandResult"
        Effect = "Allow"

        Action = [
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations"
        ]

        Resource = "*"
      }
    ]
  })
}