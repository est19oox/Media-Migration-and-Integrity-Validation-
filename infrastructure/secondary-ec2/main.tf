terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  backend "s3" {
    bucket       = "mmiv-terraform-state-275069781906"
    key          = "secondary-ec2/terraform.tfstate"
    region       = "ap-southeast-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-southeast-1"
}

variable "primary_instance_id" {
  description = "Existing MMIV primary instance ID, used to identify its VPC and AZ."
  type        = string

  validation {
    condition     = can(regex("^i-[0-9a-f]{8,17}$", var.primary_instance_id))
    error_message = "Provide a valid EC2 instance ID, for example i-0123456789abcdef0."
  }
}

data "aws_instance" "primary" {
  instance_id = var.primary_instance_id
}

data "aws_vpc" "mmiv" {
  id = data.aws_instance.primary.vpc_id
}

data "aws_internet_gateway" "mmiv" {
  filter {
    name   = "attachment.vpc-id"
    values = [data.aws_vpc.mmiv.id]
  }
}

data "aws_iam_instance_profile" "mmiv" {
  name = "MMIV-EC2"
}

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

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  secondary_az = [for az in data.aws_availability_zones.available.names : az if az != data.aws_instance.primary.availability_zone][0]
}

resource "aws_subnet" "secondary" {
  vpc_id                  = data.aws_vpc.mmiv.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = local.secondary_az
  map_public_ip_on_launch = true

  tags = {
    Name        = "MMIV-Secondary-Public-Subnet"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform-Secondary"
  }
}

resource "aws_route_table" "secondary" {
  vpc_id = data.aws_vpc.mmiv.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = data.aws_internet_gateway.mmiv.id
  }

  tags = {
    Name      = "MMIV-Secondary-Public-RT"
    Project   = "MMIV"
    ManagedBy = "Terraform-Secondary"
  }
}

resource "aws_route_table_association" "secondary" {
  subnet_id      = aws_subnet.secondary.id
  route_table_id = aws_route_table.secondary.id
}

resource "aws_security_group" "secondary" {
  name_prefix = "mmiv-secondary-"
  description = "Secondary MMIV EC2 with no inbound access"
  vpc_id      = data.aws_vpc.mmiv.id

  egress {
    description = "Outbound HTTPS only"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "MMIV-Secondary-SG"
    Project   = "MMIV"
    ManagedBy = "Terraform-Secondary"
  }
}

resource "aws_instance" "secondary" {
  ami                         = data.aws_ami.amazon_linux.id
  instance_type               = "t3.medium"
  subnet_id                   = aws_subnet.secondary.id
  associate_public_ip_address = true
  monitoring                  = true
  ebs_optimized               = true
  iam_instance_profile        = data.aws_iam_instance_profile.mmiv.name
  vpc_security_group_ids      = [aws_security_group.secondary.id]

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    encrypted   = true
    volume_size = 30
    volume_type = "gp3"
  }

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [ami]
  }

  tags = {
    Name        = "MMIV-EC2-Secondary"
    Project     = "MMIV"
    Environment = "dev"
    ManagedBy   = "Terraform-Secondary"
    Usage       = "OnDemand"
  }
}

output "secondary_instance_id" {
  value = aws_instance.secondary.id
}

output "secondary_private_ip" {
  value = aws_instance.secondary.private_ip
}
