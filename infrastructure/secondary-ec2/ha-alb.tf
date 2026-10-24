# Academic endpoint failover only. Primary MMIV host is referenced, never modified.
# Both targets listen on 8080. Primary runs its existing API gateway;
# secondary serves a static fallback, NOT the full MMIV application.
data "aws_subnet" "primary_alb" {
  id = data.aws_instance.primary.subnet_id
}

resource "aws_security_group" "ha_alb" {
  name_prefix = "mmiv-ha-alb-"
  description = "MMIV academic HA demo ALB"
  vpc_id      = data.aws_vpc.mmiv.id

  ingress {
    description = "Demo HTTP from browsers"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    description = "HTTP to demo targets"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
  }
  tags = {
    Name    = "MMIV-HA-Demo-ALB-SG"
    Project = "MMIV"
  }
}

resource "aws_lb" "ha_demo" {
  name               = "mmiv-ha-demo"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.ha_alb.id]
  subnets            = [data.aws_subnet.primary_alb.id, aws_subnet.secondary.id]
  tags = {
    Project = "MMIV"
    Usage   = "Academic-HA-Demo"
  }
}

resource "aws_lb_target_group" "ha_demo" {
  name        = "mmiv-ha-demo-targets"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.mmiv.id
  target_type = "instance"

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}

resource "aws_lb_target_group_attachment" "primary" {
  target_group_arn = aws_lb_target_group.ha_demo.arn
  target_id        = data.aws_instance.primary.id
  port             = 8080
}

resource "aws_lb_target_group_attachment" "secondary" {
  target_group_arn = aws_lb_target_group.ha_demo.arn
  target_id        = aws_instance.secondary.id
  port             = 8080
}

resource "aws_lb_listener" "ha_demo" {
  load_balancer_arn = aws_lb.ha_demo.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.ha_demo.arn
  }
}

output "ha_demo_alb_dns" {
  value = aws_lb.ha_demo.dns_name
}
