# MMIV Next ALB configuration snapshot (2026-11-14)
# Standalone import candidate; NOT part of the active infrastructure/terraform root.
# Do not apply until the existing resources have been imported and plan reviewed.
terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-southeast-1"
}

resource "aws_security_group" "alb" {
  name        = "mmiv-next-alb-sg"
  description = "Security group for MMIV Next ALB"
  vpc_id      = "vpc-04decb68655a459e8"

  ingress {
    description = "Public HTTP listener"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "HTTP to MMIV backend targets"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lb" "mmiv_next" {
  name               = "mmiv-next-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets = [
    "subnet-0340a34cc1c3db7b8",
    "subnet-0d68b2148991159a9"
  ]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lb_target_group" "failover" {
  name        = "mmiv-next-failover-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = "vpc-04decb68655a459e8"

  health_check {
    enabled             = true
    protocol            = "HTTP"
    port                = "traffic-port"
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 5
    unhealthy_threshold = 2
    matcher             = "200-399"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Existing but currently unassociated with the ALB listener.
# The AWS target-group default port is 80, even though a previous
# primary instance registration used port 8080.
resource "aws_lb_target_group" "primary" {
  name        = "mmiv-primary-tg"
  port        = 80
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = "vpc-04decb68655a459e8"

  health_check {
    enabled             = true
    protocol            = "HTTP"
    port                = "traffic-port"
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 5
    unhealthy_threshold = 2
    matcher             = "200"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.mmiv_next.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.failover.arn
  }

  lifecycle {
    prevent_destroy = true
  }
}

# Terraform import blocks reference the existing live resources.
# They do not import anything until terraform plan/apply is run in this root.
import {
  to = aws_security_group.alb
  id = "sg-00376f4471ed9f83a"
}
import {
  to = aws_lb.mmiv_next
  id = "arn:aws:elasticloadbalancing:ap-southeast-1:275069781906:loadbalancer/app/mmiv-next-alb/37671ca552c35d15"
}
import {
  to = aws_lb_target_group.failover
  id = "arn:aws:elasticloadbalancing:ap-southeast-1:275069781906:targetgroup/mmiv-next-failover-tg/8d197d04852a086b"
}
import {
  to = aws_lb_target_group.primary
  id = "arn:aws:elasticloadbalancing:ap-southeast-1:275069781906:targetgroup/mmiv-primary-tg/6919a42c754daa1f"
}
import {
  to = aws_lb_listener.http
  id = "arn:aws:elasticloadbalancing:ap-southeast-1:275069781906:listener/app/mmiv-next-alb/37671ca552c35d15/017f2978c2c6cab2"
}
