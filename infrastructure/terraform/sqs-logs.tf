# Transfer publishes records; the separately owned log service will consume them.
resource "aws_sqs_queue" "file_logs_dead_letter" {
  name                      = "mmiv-file-logs-dead-letter"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Project   = "MMIV"
    ManagedBy = "Terraform"
  }
}

resource "aws_sqs_queue" "file_logs" {
  name                       = "mmiv-file-logs"
  message_retention_seconds  = 345600
  visibility_timeout_seconds = 60
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.file_logs_dead_letter.arn
    maxReceiveCount     = 5
  })

  tags = {
    Project   = "MMIV"
    ManagedBy = "Terraform"
  }
}

resource "aws_sqs_queue_redrive_allow_policy" "file_logs" {
  queue_url = aws_sqs_queue.file_logs_dead_letter.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.file_logs.arn]
  })
}

resource "aws_sqs_queue_policy" "file_logs_tls" {
  for_each = {
    source      = aws_sqs_queue.file_logs
    dead_letter = aws_sqs_queue.file_logs_dead_letter
  }
  queue_url = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = each.value.arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_iam_role_policy" "mmiv_ec2_file_logs" {
  name = "MMIV-File-Log-Publisher"
  role = aws_iam_role.mmiv_ec2.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage"]
      Resource = aws_sqs_queue.file_logs.arn
    }]
  })
}

output "file_log_queue_url" {
  value = aws_sqs_queue.file_logs.id
}

output "file_log_queue_arn" {
  value = aws_sqs_queue.file_logs.arn
}
