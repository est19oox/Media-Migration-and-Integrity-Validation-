variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}

variable "vpc_id" {
  description = "Existing VPC ID. Use the VPC that contains the Ansible controller and web servers."
  type        = string
}

variable "subnet_id" {
  description = "Subnet ID for the web servers"
  type        = string
}

variable "ami_id" {
  description = "Amazon Linux 2023 AMI ID for the selected region"
  type        = string
}

variable "instance_type" {
  type    = string
  default = "t3.micro"
}

variable "key_name" {
  description = "Existing EC2 key pair name"
  type        = string
}

variable "web_server_count" {
  type    = number
  default = 2
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to SSH to web servers. Prefer the Ansible controller private IP/CIDR."
  type        = string
}
