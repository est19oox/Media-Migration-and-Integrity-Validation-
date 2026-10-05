# ============================================================
# S3 LANDING BUCKET
# ============================================================

resource "aws_s3_bucket" "landing" {
  bucket = var.bucket_name

  tags = {
    Name        = var.bucket_name
    Project     = "Media-Migration"
    Environment = var.environment
  }
}


# ============================================================
# VERSIONING
# ============================================================

resource "aws_s3_bucket_versioning" "landing" {
  bucket = aws_s3_bucket.landing.id

  versioning_configuration {
    status = "Enabled"
  }
}


# ============================================================
# SERVER-SIDE ENCRYPTION
# ============================================================

resource "aws_s3_bucket_server_side_encryption_configuration" "landing" {
  bucket = aws_s3_bucket.landing.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}


# ============================================================
# BLOCK PUBLIC ACCESS
# ============================================================

resource "aws_s3_bucket_public_access_block" "landing" {
  bucket = aws_s3_bucket.landing.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}


# ============================================================
# GENERATE S3 PREFIX STRUCTURE
# ============================================================

locals {
  folders = flatten([
    for semester in var.semesters : [
      for course in var.courses : [
        for folder in var.content_folders :
        "${var.academic_year}/${semester}/${course}/${folder}/"
      ]
    ]
  ])

  folder_map = {
    for folder in local.folders :
    folder => folder
  }
}


# ============================================================
# CREATE S3 PREFIXES
# ============================================================

resource "aws_s3_object" "folders" {
  for_each = var.create_folder_structure ? local.folder_map : {}

  bucket  = aws_s3_bucket.landing.id
  key     = each.value
  content = ""
}