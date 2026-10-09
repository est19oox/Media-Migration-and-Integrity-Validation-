# Creates a private S3 bucket for media migration files.
resource "aws_s3_bucket" "migration" {
  bucket = "media-migration-dev-landing"

  #checkov:skip=CKV_AWS_18:Central S3 access logging is deferred until the shared audit-log bucket is provisioned.
  #checkov:skip=CKV_AWS_144:This development environment is intentionally single-region; production requires replication.
  #checkov:skip=CKV2_AWS_62:Event notifications will be added with the queue-backed migration workflow.

  tags = {
    Name        = "MMIV Migration Storage"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }

}

# Keeps the S3 bucket private by blocking public access.
resource "aws_s3_bucket_public_access_block" "migration" {
  bucket = aws_s3_bucket.migration.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}


# Encrypts files in the S3 bucket by default.
resource "aws_s3_bucket_server_side_encryption_configuration" "migration" {
  bucket = aws_s3_bucket.migration.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_versioning" "migration" {
  bucket = aws_s3_bucket.migration.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "migration" {
  bucket = aws_s3_bucket.migration.id

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

# Creates a private Amazon ECR repository for MMIV container images.
resource "aws_ecr_repository" "mmiv" {
  #checkov:skip=CKV_AWS_136:The cost-limited coursework ECR repository uses AWS-managed AES256 encryption; customer-managed KMS encryption is deferred to production.
  name                 = "mmiv"
  image_tag_mutability = "IMMUTABLE"

  lifecycle {
    prevent_destroy = true
  }

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

# Removes old ECR images and keeps only the 5 most recent tagged images.
resource "aws_ecr_lifecycle_policy" "mmiv" {
  repository = aws_ecr_repository.mmiv.name
  policy = jsonencode({
    rules = concat([
      { rulePriority = 1, description = "Expire untagged images after one day",
        selection    = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 1 },
      action = { type = "expire" } }
      ], [for index, service in ["transfer-service", "integrity-service", "user-access-service", "reporting-service", "discovery-service", "service-registry", "api-gateway"] : {
        rulePriority = index + 2
        description  = "Keep 20 images for ${service}"
        selection    = { tagStatus = "tagged", tagPrefixList = ["${service}-"], countType = "imageCountMoreThan", countNumber = 20 }
        action       = { type = "expire" }
    }])
  })
}

# Creates an IAM role that EC2 instances can use.
resource "aws_iam_role" "mmiv_ec2" {
  name = "MMIV-EC2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"

        Principal = {
          Service = "ec2.amazonaws.com"
        }

        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = {
    Name        = "MMIV EC2 Role"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

# Allows the EC2 role to pull images from Amazon ECR.
resource "aws_iam_role_policy_attachment" "mmiv_ec2_ecr" {
  role       = aws_iam_role.mmiv_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

# Connects the EC2 IAM role to an instance profile.
resource "aws_iam_instance_profile" "mmiv_ec2" {
  name = "MMIV-EC2"
  role = aws_iam_role.mmiv_ec2.name
}



# Allows the MMIV EC2 role to list the bucket and read its files.
resource "aws_iam_role_policy" "mmiv_ec2_s3" {
  name = "MMIV-S3-Access"
  role = aws_iam_role.mmiv_ec2.name

  policy = jsonencode({
    Version = "2012-10-17"

    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:ListBucket"
        ]
        Resource = aws_s3_bucket.migration.arn
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:DeleteObjectVersion"
        ]
        Resource = "${aws_s3_bucket.migration.arn}/*"
      }
    ]
  })
}

# Allows the EC2 role to be managed through AWS Systems Manager.
resource "aws_iam_role_policy_attachment" "mmiv_ec2_ssm" {
  role       = aws_iam_role.mmiv_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}


# Creates an isolated virtual network for MMIV resources.
resource "aws_vpc" "mmiv" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  #checkov:skip=CKV2_AWS_11:VPC flow logs are deferred for the cost-limited development environment; production requires them.

  tags = {
    Name        = "MMIV-VPC"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

resource "aws_default_security_group" "mmiv" {
  vpc_id = aws_vpc.mmiv.id

  ingress = []
  egress  = []

  tags = {
    Name        = "MMIV-Default-Deny"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

# Creates a public subnet for the MMIV EC2 instance.
resource "aws_subnet" "mmiv_public" {
  vpc_id                  = aws_vpc.mmiv.id
  cidr_block              = "10.0.1.0/24"
  map_public_ip_on_launch = true

  #checkov:skip=CKV_AWS_130:The cost-limited dev host needs direct outbound package and ECR access without a NAT gateway.

  tags = {
    Name        = "MMIV-Public-Subnet"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}


# Connects the MMIV VPC to the Internet.
resource "aws_internet_gateway" "mmiv" {
  vpc_id = aws_vpc.mmiv.id

  tags = {
    Name        = "MMIV-IGW"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}

# Creates a route table for the public subnet.
resource "aws_route_table" "mmiv_public" {
  vpc_id = aws_vpc.mmiv.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.mmiv.id
  }

  tags = {
    Name        = "MMIV-Public-RT"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}


# Associates the public subnet with the public route table.
resource "aws_route_table_association" "mmiv_public" {
  subnet_id      = aws_subnet.mmiv_public.id
  route_table_id = aws_route_table.mmiv_public.id
}


# Controls network traffic for the MMIV EC2 instance.
resource "aws_security_group" "mmiv_ec2" {
  name        = "MMIV-EC2-SG"
  description = "Security group for MMIV EC2"
  vpc_id      = aws_vpc.mmiv.id

  # Browser traffic enters through the API Gateway on TCP 8080.
  # Administration remains through SSM; backend ports have no inbound rules.
  ingress {
    description = "API Gateway HTTP"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow outbound HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "MMIV-EC2-SG"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}


# Finds the latest Amazon Linux 2023 AMI.
data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# Creates the MMIV application server.
resource "aws_instance" "mmiv" {
  # Reuse the managed host. A newer AMI must not replace a working EC2 instance.
  lifecycle {
    prevent_destroy = true
    ignore_changes  = [ami, user_data]
  }

  ami                         = data.aws_ami.amazon_linux.id
  instance_type               = "t3.medium"
  subnet_id                   = aws_subnet.mmiv_public.id
  associate_public_ip_address = true
  monitoring                  = true
  ebs_optimized               = true

  #checkov:skip=CKV_AWS_88:The dev host exposes only the API Gateway and needs direct outbound access without a NAT gateway.

  vpc_security_group_ids = [
    aws_security_group.mmiv_ec2.id
  ]

  iam_instance_profile = aws_iam_instance_profile.mmiv_ec2.name

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    encrypted = true
  }

  # setup-host.yml is the single installer for persistent host components.

  tags = {
    Name        = "MMIV-EC2"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}
