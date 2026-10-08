resource "aws_s3_bucket" "terraform_state" {
  bucket = "mmiv-terraform-state-275069781906"

  #checkov:skip=CKV_AWS_18:Terraform state access is audited through AWS control-plane logging; a separate log bucket is out of bootstrap scope.
  #checkov:skip=CKV_AWS_144:The coursework environment is single-region and restores state through versioning rather than cross-region replication.
  #checkov:skip=CKV2_AWS_62:Terraform state changes must not trigger application event processing.

  tags = {
    Name        = "MMIV Terraform State"
    Project     = "MMIV"
    Environment = "shared"
    ManagedBy   = "Terraform"
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
