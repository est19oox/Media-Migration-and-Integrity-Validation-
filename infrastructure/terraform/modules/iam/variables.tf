variable "csv_file" {
  description = "Path to CSV containing IAM user metadata"
  type        = string
}

variable "bucket_arn" {
  description = "ARN of the Media Migration S3 bucket"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
  default     = "dev"
}

variable "ecr_repository_arn" {
  description = "ARN of the MMIV ECR repository"
  type        = string
}

variable "aws_region" {
  description = "AWS region used by MMIV"
  type        = string
  default     = "ap-southeast-1"
}

variable "aws_account_id" {
  description = "AWS account ID used to scope IAM resources"
  type        = string
}