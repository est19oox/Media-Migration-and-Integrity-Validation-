# Creates a private S3 bucket for media migration files.
resource "aws_s3_bucket" "migration" {
  bucket_prefix = "mmiv-dev-"


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
      sse_algorithm = "AES256"
    }
  }
}

# Creates a private Amazon ECR repository for MMIV container images.
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

# Removes old ECR images and keeps only the 5 most recent tagged images.
resource "aws_ecr_lifecycle_policy" "mmiv" {
  repository = aws_ecr_repository.mmiv.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Remove untagged images after 1 day"

        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }

        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "Keep only the 5 most recent tagged images"

        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 5
        }

        action = {
          type = "expire"
        }
      }
    ]
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
          "s3:DeleteObject"
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

  tags = {
    Name        = "MMIV-VPC"
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

  # No inbound rules.
  # The EC2 instance will be managed through SSM.

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
  ami                         = data.aws_ami.amazon_linux.id
  instance_type               = "t3.micro"
  subnet_id                   = aws_subnet.mmiv_public.id
  associate_public_ip_address = true

  vpc_security_group_ids = [
    aws_security_group.mmiv_ec2.id
  ]

  iam_instance_profile = aws_iam_instance_profile.mmiv_ec2.name

  user_data = <<-EOF
    #!/bin/bash
    dnf install -y docker
    systemctl enable --now docker
  EOF

  tags = {
    Name        = "MMIV-EC2"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform"
  }
}