data "aws_vpc" "mmiv" { id = var.vpc_id }
data "aws_subnet" "primary" { id = var.primary_public_subnet_id }
data "aws_subnet" "secondary" { id = var.secondary_public_subnet_id }
data "aws_caller_identity" "current" {}
resource "random_password" "origin_token" {
  count   = var.use_cloudfront_default_domain ? 1 : 0
  length  = 40
  special = false
}

# Fargate tasks have public egress addresses for ECR, Secrets Manager and S3, but
# security groups deny direct inbound Internet traffic. Only the ALB is public.
resource "aws_security_group" "alb" {
  name_prefix = "mmiv-ecs-alb-"
  description = "CloudFront origin or public HTTPS entry to MMIV Gateway"
  vpc_id      = var.vpc_id
  egress {
    description     = "Forward to Gateway Tasks"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.tasks.id]
  }
  tags = { Project = "MMIV" }
}
resource "aws_vpc_security_group_ingress_rule" "alb_cloudfront" {
  count             = var.use_cloudfront_default_domain ? 1 : 0
  description       = "Private CloudFront VPC Origin traffic within MMIV VPC"
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = data.aws_vpc.mmiv.cidr_block
}
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  count             = var.use_cloudfront_default_domain ? 0 : 1
  description       = "HTTPS entry for custom-domain mode"
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "tasks" {
  #checkov:skip=CKV_AWS_382:Tasks require outbound ECR, Secrets Manager, S3 and privately supplied dependencies; restrict destinations at VPC boundary before production.
  name_prefix = "mmiv-ecs-tasks-"
  description = "MMIV application Tasks, no direct public ingress"
  vpc_id      = var.vpc_id
  egress {
    description = "External AWS endpoints and private dependencies"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Project = "MMIV" }
}
resource "aws_vpc_security_group_ingress_rule" "gateway" {
  description                  = "ALB to Gateway"
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
resource "aws_vpc_security_group_ingress_rule" "peers" {
  description                  = "MMIV Tasks to service port ${each.value}"
  for_each                     = toset([for name, port in local.ports : tostring(port) if name != "api-gateway"])
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
}

resource "aws_security_group" "efs" {
  name_prefix = "mmiv-ecs-efs-"
  description = "NFS from MMIV Tasks only"
  vpc_id      = var.vpc_id
  ingress {
    description     = "NFS from Tasks"
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.tasks.id]
  }
  tags = { Project = "MMIV" }
}
resource "aws_efs_file_system" "outbox" {
  #checkov:skip=CKV_AWS_184:AWS-managed EFS encryption is used in this opt-in environment; CMK ownership requires a separate key policy and rotation plan.
  encrypted = true
  lifecycle_policy { transition_to_ia = "AFTER_30_DAYS" }
  tags = { Name = "MMIV-ECS-Outbox", Project = "MMIV" }
}
resource "aws_efs_mount_target" "outbox" {
  for_each        = { primary = var.primary_public_subnet_id, secondary = data.aws_subnet.secondary.id }
  file_system_id  = aws_efs_file_system.outbox.id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs.id]
}
resource "aws_efs_access_point" "outbox" {
  for_each       = local.ports
  file_system_id = aws_efs_file_system.outbox.id
  posix_user {
    uid = 1000
    gid = 1000
  }
  root_directory {
    path = "/${each.key}"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0700"
    }
  }
  tags = { Name = "MMIV-${each.key}-Outbox" }
}
resource "aws_efs_access_point" "media" {
  file_system_id = aws_efs_file_system.outbox.id
  posix_user {
    uid = 1000
    gid = 1000
  }
  root_directory {
    path = "/media"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0700"
    }
  }
  tags = { Name = "MMIV-Shared-Media" }
}

resource "aws_service_discovery_private_dns_namespace" "mmiv" {
  name = "mmiv.local"
  vpc  = var.vpc_id
}
resource "aws_service_discovery_service" "registry" {
  name = "service-registry"
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.mmiv.id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
  health_check_custom_config {}
}
resource "aws_service_discovery_service" "registry_peer" {
  name = "service-registry-peer"
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.mmiv.id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
  health_check_custom_config {}
}

resource "aws_ecs_cluster" "mmiv" {
  name = "mmiv-fargate"
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
resource "aws_cloudwatch_log_group" "service" {
  #checkov:skip=CKV_AWS_338:Fourteen-day retention bounds cost for the dev migration; central retention is a later operational decision.
  #checkov:skip=CKV_AWS_158:CloudWatch default encryption is used until a log CMK policy is provisioned.
  for_each          = local.ports
  name              = "/ecs/mmiv/${each.key}"
  retention_in_days = 14
}
resource "aws_iam_role" "execution" {
  name = "MMIV-ECS-Execution"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole"
  }] })
}
resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
resource "aws_iam_role_policy" "secrets" {
  role = aws_iam_role.execution.name
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [for arn in values(var.secret_arns) : arn if arn != null]
  }] })
}
resource "aws_iam_role_policy" "data_secret_decrypt" {
  count = var.data_kms_key_arn == null ? 0 : 1
  role  = aws_iam_role.execution.name
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Action = ["kms:Decrypt"], Resource = [var.data_kms_key_arn]
  }] })
}
resource "aws_iam_role" "task" {
  for_each           = local.ports
  name               = "MMIV-ECS-${each.key}"
  assume_role_policy = aws_iam_role.execution.assume_role_policy
}
resource "aws_iam_role_policy" "s3" {
  for_each = toset(["transfer-service", "integrity-service"])
  role     = aws_iam_role.task[each.key].name
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["s3:ListBucket", "s3:ListBucketVersions"], Resource = [var.bucket_arn] },
    { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = ["${var.bucket_arn}/*"] }
  ] })
}

resource "aws_dynamodb_table" "transfer_coordination" {
  #checkov:skip=CKV_AWS_119:AWS-managed DynamoDB encryption is used for coordination keys; no secret values are stored.
  name         = "mmiv-transfer-coordination"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  attribute {
    name = "pk"
    type = "S"
  }
  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
  tags = { Name = "MMIV-Transfer-Coordination", Project = "MMIV" }
  lifecycle { prevent_destroy = true }
}

resource "aws_iam_role_policy" "transfer_coordination" {
  role = aws_iam_role.task["transfer-service"].name
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect   = "Allow", Action = ["dynamodb:DescribeTable", "dynamodb:GetItem", "dynamodb:UpdateItem"],
    Resource = aws_dynamodb_table.transfer_coordination.arn
  }] })
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.ports
  family                   = "mmiv-${each.key}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  container_definitions = jsonencode([{
    name         = each.key
    image        = "${var.image_repository_url}:${each.key == "service-registry-peer" ? "service-registry" : each.key}-${var.image_revision}"
    essential    = true
    stopTimeout  = 120
    portMappings = [{ containerPort = each.value, protocol = "tcp" }]
    environment  = [for name, value in local.service_environment[each.key] : { name = name, value = value }]
    secrets      = [for name, arn in local.service_secrets[each.key] : { name = name, valueFrom = arn }]
    mountPoints = concat(
      [{ sourceVolume = "outbox", containerPath = "/var/lib/mmiv/event-outbox", readOnly = false }],
      contains(["transfer-service", "discovery-service"], each.key) ?
      [{ sourceVolume = "media", containerPath = "/media", readOnly = false }] : []
    )
    logConfiguration = {
      logDriver = "awslogs"
      options = { awslogs-group = aws_cloudwatch_log_group.service[each.key].name,
      awslogs-region = var.region, awslogs-stream-prefix = "service" }
    }
  }])
  volume {
    name = "outbox"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.outbox.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.outbox[each.key].id
      }
    }
  }
  dynamic "volume" {
    for_each = contains(["transfer-service", "discovery-service"], each.key) ? [1] : []
    content {
      name = "media"
      efs_volume_configuration {
        file_system_id     = aws_efs_file_system.outbox.id
        transit_encryption = "ENABLED"
        authorization_config {
          access_point_id = aws_efs_access_point.media.id
        }
      }
    }
  }
}

resource "aws_lb" "gateway" {
  #checkov:skip=CKV_AWS_91:ALB access logging requires a dedicated logging bucket and policy before public cutover; CloudWatch application logs exist for initial validation.
  #checkov:skip=CKV2_AWS_28:WAF is a separate costed control; attach a web ACL before public production cutover.
  #checkov:skip=CKV2_AWS_20:In no-domain mode CloudFront terminates HTTPS and reaches an internal ALB through VPC Origin; custom-domain mode uses HTTPS listener.
  name                       = "mmiv-fargate-gateway"
  internal                   = var.use_cloudfront_default_domain
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = [var.primary_public_subnet_id, data.aws_subnet.secondary.id]
  enable_deletion_protection = true
  drop_invalid_header_fields = true
  lifecycle {
    precondition {
      condition = (
        data.aws_subnet.primary.vpc_id == var.vpc_id &&
        data.aws_subnet.secondary.vpc_id == var.vpc_id &&
        data.aws_subnet.primary.availability_zone != data.aws_subnet.secondary.availability_zone
      )
      error_message = "Primary and secondary subnets must belong to the selected VPC and be in different availability zones."
    }
  }
}
resource "aws_lb_target_group" "gateway" {
  name                 = "mmiv-fargate-gateway"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 300
  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}
resource "aws_lb_listener" "https" {
  count             = var.use_cloudfront_default_domain ? 0 : 1
  load_balancer_arn = aws_lb.gateway.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
  lifecycle {
    precondition {
      condition     = var.certificate_arn != null
      error_message = "Custom-domain HTTPS requires an ACM certificate ARN."
    }
  }
}
resource "aws_lb_listener" "cloudfront_origin" {
  #checkov:skip=CKV_AWS_2:CloudFront terminates browser HTTPS; the ALB origin is reachable only from CloudFront and requires a private header token.
  #checkov:skip=CKV_AWS_103:The CloudFront-to-ALB hop uses HTTP within this temporary demo topology; browser traffic is HTTPS.
  count             = var.use_cloudfront_default_domain ? 1 : 0
  load_balancer_arn = aws_lb.gateway.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}
resource "aws_lb_listener_rule" "cloudfront_origin" {
  count        = var.use_cloudfront_default_domain ? 1 : 0
  listener_arn = aws_lb_listener.cloudfront_origin[0].arn
  priority     = 1
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
  condition {
    http_header {
      http_header_name = "X-MMIV-Origin-Token"
      values           = [random_password.origin_token[0].result]
    }
  }
}
resource "aws_cloudfront_vpc_origin" "gateway" {
  count = var.use_cloudfront_default_domain ? 1 : 0
  vpc_origin_endpoint_config {
    arn                    = aws_lb.gateway.arn
    http_port              = 80
    https_port             = 443
    name                   = "mmiv-demo-origin"
    origin_protocol_policy = "http-only"
    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
  depends_on = [aws_lb_listener_rule.cloudfront_origin, aws_vpc_security_group_ingress_rule.alb_cloudfront]
}

resource "aws_cloudfront_cache_policy" "no_cache" {
  count       = var.use_cloudfront_default_domain ? 1 : 0
  name        = "mmiv-demo-no-cache"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 0
  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config { cookie_behavior = "none" }
    headers_config { header_behavior = "none" }
    query_strings_config { query_string_behavior = "none" }
  }
}
resource "aws_cloudfront_origin_request_policy" "forward_viewer" {
  count = var.use_cloudfront_default_domain ? 1 : 0
  name  = "mmiv-demo-forward-viewer"
  cookies_config { cookie_behavior = "all" }
  headers_config { header_behavior = "allViewer" }
  query_strings_config { query_string_behavior = "all" }
}
resource "aws_cloudfront_response_headers_policy" "security" {
  count = var.use_cloudfront_default_domain ? 1 : 0
  name  = "mmiv-demo-security-headers"
  security_headers_config {
    content_type_options { override = true }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
    xss_protection {
      mode_block = true
      protection = true
      override   = true
    }
  }
}
resource "aws_cloudfront_distribution" "gateway" {
  #checkov:skip=CKV_AWS_68:WAF is omitted for a short-lived cost-limited demo; application rate limits remain enabled.
  #checkov:skip=CKV2_AWS_47:WAF Log4j managed rules are omitted with WAF for this short-lived demo.
  #checkov:skip=CKV_AWS_174:The default CloudFront certificate supplies the managed HTTPS endpoint without a custom domain.
  #checkov:skip=CKV2_AWS_42:A custom certificate requires a domain; this demo intentionally uses the default CloudFront certificate.
  #checkov:skip=CKV_AWS_310:A second origin would require a second app stack; this is a single-AZ demo.
  #checkov:skip=CKV_AWS_86:CloudFront access logging is omitted for the short-lived demo; application and ALB metrics remain available.
  #checkov:skip=CKV_AWS_305:The application gateway serves the root route; rewriting it to a default object would change application routing.
  #checkov:skip=CKV_AWS_374:Geo restrictions are omitted because a demo viewer may connect outside Singapore.
  #checkov:skip=CKV2_AWS_32:The response_headers_policy_id below attaches the security policy; the graph check does not resolve counted resources.
  count               = var.use_cloudfront_default_domain ? 1 : 0
  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_200"
  wait_for_deployment = true
  origin {
    domain_name = aws_lb.gateway.dns_name
    origin_id   = "mmiv-alb"
    custom_header {
      name  = "X-MMIV-Origin-Token"
      value = random_password.origin_token[0].result
    }
    vpc_origin_config {
      vpc_origin_id       = aws_cloudfront_vpc_origin.gateway[0].id
      origin_read_timeout = 120
    }
  }
  default_cache_behavior {
    target_origin_id           = "mmiv-alb"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.no_cache[0].id
    origin_request_policy_id   = aws_cloudfront_origin_request_policy.forward_viewer[0].id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security[0].id
    compress                   = false
  }
  restrictions {
    geo_restriction { restriction_type = "none" }
  }
  viewer_certificate { cloudfront_default_certificate = true }
  depends_on = [aws_cloudfront_vpc_origin.gateway]
}
resource "aws_ecs_service" "service" {
  #checkov:skip=CKV_AWS_333:Tasks have public egress only; SG accepts inbound solely from ALB or MMIV peers. Replace with NAT/endpoints for private subnets.
  for_each                           = var.start_services ? local.ports : {}
  name                               = each.key
  cluster                            = aws_ecs_cluster.mmiv.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = contains(local.scalable, each.key) ? (var.demo_single_az ? 1 : 2) : 1
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  health_check_grace_period_seconds  = each.key == "api-gateway" ? 120 : null
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = var.demo_single_az ? [var.primary_public_subnet_id] : [var.primary_public_subnet_id, data.aws_subnet.secondary.id]
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  dynamic "service_registries" {
    for_each = contains(["service-registry", "service-registry-peer"], each.key) ? [1] : []
    content { registry_arn = each.key == "service-registry" ? aws_service_discovery_service.registry.arn : aws_service_discovery_service.registry_peer.arn }
  }
  dynamic "load_balancer" {
    for_each = each.key == "api-gateway" ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.gateway.arn
      container_name   = "api-gateway"
      container_port   = 8080
    }
  }
  depends_on = [aws_efs_mount_target.outbox, aws_vpc_security_group_ingress_rule.peers, aws_lb_listener.https, aws_lb_listener.cloudfront_origin, aws_iam_role_policy.transfer_coordination]
  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_appautoscaling_target" "service" {
  for_each           = var.start_services ? local.scalable : toset([])
  max_capacity       = each.key == "integrity-service" ? 6 : 4
  min_capacity       = var.demo_single_az ? 1 : 2
  resource_id        = "service/${aws_ecs_cluster.mmiv.name}/${aws_ecs_service.service[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}
resource "aws_appautoscaling_policy" "cpu" {
  for_each           = var.start_services ? local.scalable : toset([])
  name               = "mmiv-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.service[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.service[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.service[each.key].service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}

resource "aws_appautoscaling_policy" "transfer_memory" {
  for_each           = var.start_services ? toset(["transfer-service"]) : toset([])
  name               = "mmiv-transfer-service-memory"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.service[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.service[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.service[each.key].service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = 70
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageMemoryUtilization" }
  }
}

resource "aws_cloudwatch_dashboard" "ecs" {
  dashboard_name = "MMIV-ECS-Services"
  dashboard_body = jsonencode({ widgets = [
    for index, name in sort(concat(tolist(local.scalable), ["service-registry", "service-registry-peer"])) : {
      type  = "metric", x = (index % 2) * 12, y = floor(index / 2) * 6,
      width = 12, height = 6,
      properties = {
        title = "${name} CPU, memory and Tasks", region = var.region, period = 60,
        metrics = [
          ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.mmiv.name, "ServiceName", name],
          ["AWS/ECS", "MemoryUtilization", "ClusterName", aws_ecs_cluster.mmiv.name, "ServiceName", name],
          ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", aws_ecs_cluster.mmiv.name, "ServiceName", name, { yAxis = "right" }]
        ]
      }
    }
  ] })
}

output "gateway_dns_name" { value = aws_lb.gateway.dns_name }
output "browser_url" { value = var.use_cloudfront_default_domain ? "https://${aws_cloudfront_distribution.gateway[0].domain_name}" : "Use your ACM certificate hostname pointing to ${aws_lb.gateway.dns_name}" }
output "cluster_name" { value = aws_ecs_cluster.mmiv.name }
output "task_security_group_id" { value = aws_security_group.tasks.id }
output "secondary_subnet_id" { value = data.aws_subnet.secondary.id }
