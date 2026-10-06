# The root infrastructure/terraform configuration owns the landing bucket.
# This module only reads that bucket and manages the initial folder objects.
data "aws_s3_bucket" "landing" {
  bucket = var.bucket_name
}

# Forget legacy ownership in the dev state without deleting AWS resources.
# These blocks are harmless when the resources were never in this state.
removed {
  from = aws_s3_bucket.landing

  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_versioning.landing

  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_lifecycle_configuration.landing

  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_server_side_encryption_configuration.landing

  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_public_access_block.landing

  lifecycle {
    destroy = false
  }
}

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

  bucket  = data.aws_s3_bucket.landing.id
  key     = each.value
  content = ""
}
