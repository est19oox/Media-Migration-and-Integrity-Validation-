resource "aws_iam_user" "developers" {
  for_each = var.developers

  name = each.value

  tags = {
    Project     = "Media-Migration"
    Environment = "dev"
    Role        = "Developer"
  }
}

resource "aws_iam_group" "developers" {
  name = "media-migration-developers"
}

resource "aws_iam_user_group_membership" "developers" {
  for_each = aws_iam_user.developers

  user = each.value.name

  groups = [
    aws_iam_group.developers.name
  ]
}

resource "aws_iam_policy" "s3_access" {
  name        = "media-migration-dev-s3-access"
  description = "Allows developers to access the Media Migration dev S3 bucket"

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Action = [
          "s3:ListBucket"
        ]

        Resource = var.bucket_arn
      },
      {
        Effect = "Allow"

        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject"
        ]

        Resource = "${var.bucket_arn}/*"
      }
    ]
  })
}

resource "aws_iam_group_policy_attachment" "s3_access" {
  group      = aws_iam_group.developers.name
  policy_arn = aws_iam_policy.s3_access.arn
}