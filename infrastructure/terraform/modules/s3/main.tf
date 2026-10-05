# ============================================================
# S3 LANDING BUCKET
# ============================================================

resource "aws_s3_bucket" "landing" {
  bucket = var.bucket_name

  #checkov:skip=CKV_AWS_18:Central S3 access logging is deferred until the shared audit-log bucket is provisioned.
  #checkov:skip=CKV_AWS_144:This development module is intentionally single-region; production requires replication.
  #checkov:skip=CKV2_AWS_62:Event notifications will be added with the queue-backed migration workflow.

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

resource "aws_s3_bucket_lifecycle_configuration" "landing" {
  bucket = aws_s3_bucket.landing.id

  rule {
    id     = "expire-old-media-versions"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}


# ============================================================
# SERVER-SIDE ENCRYPTION
# ============================================================

resource "aws_s3_bucket_server_side_encryption_configuration" "landing" {
  bucket = aws_s3_bucket.landing.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
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
