resource "aws_s3_bucket" "migration" {
  bucket_prefix = "mmiv-dev-"


  tags = {
    Name        = "MMIV Migration Storage"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }

}

resource "aws_s3_bucket_public_access_block" "migration" {
  bucket = aws_s3_bucket.migration.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}


resource "aws_s3_bucket_server_side_encryption_configuration" "migration" {
  bucket = aws_s3_bucket.migration.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_ecr_repository" "mmiv" {
  name                 = "mmiv"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name        = "MMIV Container Repository"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}