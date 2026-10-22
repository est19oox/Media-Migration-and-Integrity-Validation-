terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
    tls    = { source = "hashicorp/tls", version = "~> 4.0" }
  }
  backend "s3" {}
}

provider "aws" { region = var.region }

variable "region" {
  type    = string
  default = "ap-southeast-1"
}
variable "vpc_id" {
  type = string
}
variable "subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "Provide subnets in at least two availability zones."
  }
}
variable "demo_single_az" {
  type        = bool
  default     = true
  description = "Use one Oracle AZ, one DocumentDB instance and one Valkey node for a short-lived demo."
}
variable "teardown_mode" {
  type        = bool
  default     = false
  description = "Skip final RDS and DocumentDB snapshots only during an explicit disposable-demo teardown."
}
variable "rabbit_cluster_subnet_ids" {
  type        = list(string)
  default     = []
  description = "Three subnet IDs in distinct AZs enable a multi-AZ RabbitMQ cluster. Empty uses a single-instance broker in subnet_ids[0]."
  validation {
    condition     = length(var.rabbit_cluster_subnet_ids) == 0 || length(var.rabbit_cluster_subnet_ids) == 3
    error_message = "Specify exactly three RabbitMQ cluster subnet IDs or none."
  }
}
variable "oracle_instance_class" {
  type    = string
  default = "db.m5.large"
}
variable "docdb_instance_class" {
  type    = string
  default = "db.t3.medium"
}
variable "redis_node_type" {
  type    = string
  default = "cache.t3.small"
}
variable "rabbit_instance_type" {
  type    = string
  default = "mq.m5.large"
  validation {
    condition     = var.rabbit_instance_type != "mq.t3.micro"
    error_message = "mq.t3.micro is an ActiveMQ broker type and cannot be used for RabbitMQ. Use a RabbitMQ-supported type such as mq.m5.large."
  }
}
variable "rabbit_engine_version" {
  type    = string
  default = "3.13"
}

data "aws_subnet" "selected" {
  for_each = toset(concat(var.subnet_ids, var.rabbit_cluster_subnet_ids))
  id       = each.value
}
data "aws_caller_identity" "current" {}

locals {
  app_users      = toset(["USER_ACCESS", "DISCOVERY", "INTEGRITY_SERVICE"])
  rabbit_cluster = length(var.rabbit_cluster_subnet_ids) == 3
}

resource "aws_kms_key" "data" {
  description             = "MMIV managed data encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Sid       = "EnableAccountIAM", Effect = "Allow",
    Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" },
    Action    = "kms:*", Resource = "*"
  }] })
  tags = { Project = "MMIV" }
}
resource "aws_kms_alias" "data" {
  name          = "alias/mmiv-managed-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_security_group" "tasks" {
  #checkov:skip=CKV_AWS_382:Tasks need ECR, Secrets Manager, S3 and private dependency egress; ingress is managed by the ECS root.
  #checkov:skip=CKV2_AWS_5:The ECS root attaches this exported group to Fargate Tasks; cross-root references are not visible to this scan.
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

resource "aws_security_group" "oracle" {
  name_prefix = "mmiv-rds-oracle-"
  description = "Oracle from MMIV application Tasks"
  vpc_id      = var.vpc_id
  tags        = { Project = "MMIV" }
}
resource "aws_security_group" "docdb" {
  name_prefix = "mmiv-docdb-"
  description = "DocumentDB from MMIV application Tasks"
  vpc_id      = var.vpc_id
  tags        = { Project = "MMIV" }
}
resource "aws_security_group" "rabbit" {
  name_prefix = "mmiv-mq-"
  description = "Amazon MQ from MMIV application Tasks"
  vpc_id      = var.vpc_id
  tags        = { Project = "MMIV" }
}
resource "aws_security_group" "redis" {
  name_prefix = "mmiv-redis-"
  description = "ElastiCache from MMIV application Tasks"
  vpc_id      = var.vpc_id
  tags        = { Project = "MMIV" }
}
resource "aws_vpc_security_group_ingress_rule" "app" {
  for_each = {
    oracle = { group = aws_security_group.oracle.id, port = 1521 }
    docdb  = { group = aws_security_group.docdb.id, port = 27017 }
    rabbit = { group = aws_security_group.rabbit.id, port = 5671 }
    redis  = { group = aws_security_group.redis.id, port = 6379 }
  }
  description                  = "MMIV Tasks only"
  security_group_id            = each.value.group
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

resource "random_password" "schema" {
  for_each = local.app_users
  length   = 32
  special  = false
}
resource "random_password" "reporting" {
  length  = 32
  special = false
}
resource "random_password" "rabbit" {
  length  = 32
  special = false
}
resource "random_password" "redis" {
  length  = 32
  special = false
}

resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 3072
}

resource "aws_secretsmanager_secret" "jwt_private" {
  #checkov:skip=CKV2_AWS_57:Rotating signing keys requires coordinated JWKS publication and Task rollout; automatic uncoordinated rotation would invalidate sessions.
  name_prefix             = "mmiv/jwt-private/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "jwt_private" {
  secret_id     = aws_secretsmanager_secret.jwt_private.id
  secret_string = tls_private_key.jwt.private_key_pem_pkcs8
}
resource "aws_secretsmanager_secret" "jwt_public" {
  #checkov:skip=CKV2_AWS_57:Rotate the public key in lockstep with its private signing key and JWKS rollout.
  name_prefix             = "mmiv/jwt-public/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "jwt_public" {
  secret_id     = aws_secretsmanager_secret.jwt_public.id
  secret_string = tls_private_key.jwt.public_key_pem
}

resource "aws_secretsmanager_secret" "schema" {
  #checkov:skip=CKV2_AWS_57:Oracle password rotation must also update the matching database accounts and Tasks.
  for_each                = local.app_users
  name_prefix             = "mmiv/${lower(each.key)}/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "schema" {
  for_each      = local.app_users
  secret_id     = aws_secretsmanager_secret.schema[each.key].id
  secret_string = random_password.schema[each.key].result
}
resource "aws_secretsmanager_secret" "rabbit" {
  #checkov:skip=CKV2_AWS_57:Broker password rotation must also update Amazon MQ and Tasks.
  name_prefix             = "mmiv/rabbit/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "rabbit" {
  secret_id     = aws_secretsmanager_secret.rabbit.id
  secret_string = random_password.rabbit.result
}
resource "aws_secretsmanager_secret" "redis" {
  #checkov:skip=CKV2_AWS_57:Valkey token rotation must also update the replication group and Tasks.
  name_prefix             = "mmiv/redis/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = random_password.redis.result
}

resource "aws_db_subnet_group" "oracle" {
  name_prefix = "mmiv-oracle-"
  subnet_ids  = var.subnet_ids
  tags        = { Project = "MMIV" }
}
resource "aws_iam_role" "rds_monitoring" {
  name_prefix = "mmiv-rds-monitoring-"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Principal = { Service = "monitoring.rds.amazonaws.com" },
    Action = "sts:AssumeRole"
  }] })
}
resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
resource "aws_db_instance" "oracle" {
  #checkov:skip=CKV_AWS_157:Single-AZ is an explicit short-lived demo option; set demo_single_az=false for a resilient deployment.
  identifier                      = "mmiv-oracle"
  engine                          = "oracle-se2"
  license_model                   = "license-included"
  instance_class                  = var.oracle_instance_class
  allocated_storage               = 100
  max_allocated_storage           = 200
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  multi_az                        = !var.demo_single_az
  db_name                         = "ORCL"
  username                        = "mmivmaster"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  db_subnet_group_name            = aws_db_subnet_group.oracle.name
  vpc_security_group_ids          = [aws_security_group.oracle.id]
  publicly_accessible             = false
  backup_retention_period         = 7
  enabled_cloudwatch_logs_exports = ["alert", "audit", "listener", "trace"]
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.data.arn
  monitoring_interval             = 60
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn
  deletion_protection             = true
  skip_final_snapshot             = var.teardown_mode
  final_snapshot_identifier       = var.teardown_mode ? null : "mmiv-oracle-final"
  auto_minor_version_upgrade      = true
  copy_tags_to_snapshot           = true
  tags                            = { Project = "MMIV" }
}

resource "aws_docdb_subnet_group" "reporting" {
  name       = "mmiv-reporting"
  subnet_ids = var.subnet_ids
  tags       = { Project = "MMIV" }
}
resource "aws_docdb_cluster" "reporting" {
  cluster_identifier              = "mmiv-reporting"
  engine                          = "docdb"
  master_username                 = "mmivreporting"
  master_password                 = random_password.reporting.result
  db_subnet_group_name            = aws_docdb_subnet_group.reporting.name
  vpc_security_group_ids          = [aws_security_group.docdb.id]
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  enabled_cloudwatch_logs_exports = ["audit", "profiler"]
  backup_retention_period         = 7
  deletion_protection             = true
  skip_final_snapshot             = var.teardown_mode
  final_snapshot_identifier       = var.teardown_mode ? null : "mmiv-reporting-final"
  tags                            = { Project = "MMIV" }
}
resource "aws_docdb_cluster_instance" "reporting" {
  count              = var.demo_single_az ? 1 : 2
  identifier         = "mmiv-reporting-${count.index + 1}"
  cluster_identifier = aws_docdb_cluster.reporting.id
  instance_class     = var.docdb_instance_class
  availability_zone  = data.aws_subnet.selected[var.subnet_ids[count.index]].availability_zone
  tags               = { Project = "MMIV" }
}

resource "aws_secretsmanager_secret" "mongo_uri" {
  #checkov:skip=CKV2_AWS_57:URI rotation must also update the DocumentDB account and Tasks.
  name_prefix             = "mmiv/reporting-uri/"
  recovery_window_in_days = 7
  kms_key_id              = aws_kms_key.data.arn
  tags                    = { Project = "MMIV" }
}
resource "aws_secretsmanager_secret_version" "mongo_uri" {
  secret_id     = aws_secretsmanager_secret.mongo_uri.id
  secret_string = "mongodb://mmivreporting:${random_password.reporting.result}@${aws_docdb_cluster.reporting.endpoint}:27017/mmiv_reporting?tls=true&retryWrites=false&replicaSet=rs0&readPreference=primary&authSource=admin"
}

resource "aws_mq_broker" "rabbit" {
  broker_name                = "mmiv-rabbitmq"
  engine_type                = "RabbitMQ"
  engine_version             = var.rabbit_engine_version
  host_instance_type         = var.rabbit_instance_type
  deployment_mode            = local.rabbit_cluster ? "CLUSTER_MULTI_AZ" : "SINGLE_INSTANCE"
  subnet_ids                 = local.rabbit_cluster ? var.rabbit_cluster_subnet_ids : [var.subnet_ids[0]]
  security_groups            = [aws_security_group.rabbit.id]
  publicly_accessible        = false
  auto_minor_version_upgrade = true
  encryption_options {
    use_aws_owned_key = false
    kms_key_id        = aws_kms_key.data.arn
  }
  logs { general = true }
  user {
    username = "mmiv"
    password = random_password.rabbit.result
  }
  tags = { Project = "MMIV" }
}

resource "aws_elasticache_subnet_group" "redis" {
  name       = "mmiv-redis"
  subnet_ids = var.subnet_ids
}
resource "aws_elasticache_replication_group" "redis" {
  #checkov:skip=CKV2_AWS_50:Single-node Valkey is an explicit short-lived demo option; set demo_single_az=false for failover.
  replication_group_id       = "mmiv-redis"
  description                = "MMIV gateway rate-limit state"
  engine                     = "valkey"
  node_type                  = var.redis_node_type
  num_cache_clusters         = var.demo_single_az ? 1 : 2
  automatic_failover_enabled = !var.demo_single_az
  multi_az_enabled           = !var.demo_single_az
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  kms_key_id                 = aws_kms_key.data.arn
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.redis.id]
  snapshot_retention_limit   = 1
  tags                       = { Project = "MMIV" }
}

output "oracle_jdbc_url" {
  value = "jdbc:oracle:thin:@//${aws_db_instance.oracle.address}:1521/ORCL"
}
output "oracle_master_secret_arn" {
  value = aws_db_instance.oracle.master_user_secret[0].secret_arn
}
output "user_access_db_password_arn" {
  value = aws_secretsmanager_secret.schema["USER_ACCESS"].arn
}
output "discovery_db_password_arn" {
  value = aws_secretsmanager_secret.schema["DISCOVERY"].arn
}
output "integrity_db_password_arn" {
  value = aws_secretsmanager_secret.schema["INTEGRITY_SERVICE"].arn
}
output "mongo_uri_arn" {
  value = aws_secretsmanager_secret.mongo_uri.arn
}
output "rabbit_password_arn" {
  value = aws_secretsmanager_secret.rabbit.arn
}
output "redis_password_arn" {
  value = aws_secretsmanager_secret.redis.arn
}
output "kms_key_arn" {
  value = aws_kms_key.data.arn
}
output "rabbit_host" {
  value = split(":", replace(aws_mq_broker.rabbit.instances[0].endpoints[0], "amqps://", ""))[0]
}
output "redis_host" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}
output "rabbit_port" {
  value = 5671
}
output "redis_port" {
  value = 6379
}
output "task_security_group_id" {
  value = aws_security_group.tasks.id
}
output "jwt_private_key_secret_arn" {
  value = aws_secretsmanager_secret.jwt_private.arn
}
output "jwt_public_key_secret_arn" {
  value = aws_secretsmanager_secret.jwt_public.arn
}
