terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Run terraform init -backend-config=... with a NEW state key. Never reuse the EC2 state.
  backend "s3" {}
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "ap-southeast-1"
}
variable "vpc_id" {
  type = string
}
variable "primary_public_subnet_id" {
  type = string
}
variable "secondary_public_subnet_id" {
  type        = string
  description = "ID of the existing MMIV-Secondary-Public-Subnet in a different AZ from the primary subnet."
}
variable "certificate_arn" {
  type    = string
  default = null
}
variable "use_cloudfront_default_domain" {
  type        = bool
  default     = true
  description = "Use CloudFront's managed HTTPS domain with a private VPC Origin ALB. No custom domain or ACM certificate required."
}
variable "image_repository_url" {
  type = string
}
variable "image_revision" {
  type = string
}
variable "start_services" {
  type    = bool
  default = true
}
variable "demo_single_az" {
  type        = bool
  default     = true
  description = "Place application Tasks in one AZ and start one Task per scalable service; the public ALB still needs two AZ subnets."
}
variable "bucket_arn" {
  type = string
}
variable "bucket_name" {
  type = string
}
variable "oracle_jdbc_url" {
  type = string
}
variable "rabbit_host" {
  type = string
}
variable "redis_host" {
  type = string
}
variable "jwt_issuer" {
  type = string
}
variable "jwt_key_id" {
  type = string
}
variable "data_kms_key_arn" {
  type    = string
  default = null
}

# Values are existing Secrets Manager ARNs, not plaintext. Create/import them before apply.
variable "secret_arns" {
  type = object({
    rabbit_password         = string
    user_access_db_password = string
    discovery_db_password   = string
    integrity_db_password   = string
    mongo_uri               = string
    jwt_private_key_pem     = string
    jwt_public_key_pem      = string
    redis_password          = optional(string)
  })
}

variable "rabbit_username" {
  type    = string
  default = "mmiv"
}
variable "user_access_db_username" {
  type    = string
  default = "USER_ACCESS"
}
variable "discovery_db_username" {
  type    = string
  default = "DISCOVERY"
}
variable "integrity_db_username" {
  type    = string
  default = "INTEGRITY_SERVICE"
}
variable "redis_port" {
  type    = number
  default = 6379
}
variable "rabbit_port" {
  type    = number
  default = 5672
}
variable "rabbit_tls" {
  type    = bool
  default = false
}
variable "redis_tls" {
  type    = bool
  default = false
}
variable "gateway_cookie_secure" {
  type    = bool
  default = true
}

locals {
  ports = {
    service-registry      = 8761
    service-registry-peer = 8761
    api-gateway           = 8080
    transfer-service      = 8081
    integrity-service     = 8082
    user-access-service   = 8083
    reporting-service     = 8084
    discovery-service     = 8085
  }
  # Business services scale independently; the two Eureka peers form a fixed replication pair.
  scalable = toset(["api-gateway", "transfer-service", "integrity-service", "user-access-service", "reporting-service", "discovery-service"])
  dependencies = {
    SPRING_RABBITMQ_HOST                        = var.rabbit_host
    SPRING_RABBITMQ_PORT                        = tostring(var.rabbit_port)
    SPRING_RABBITMQ_USERNAME                    = var.rabbit_username
    SPRING_RABBITMQ_SSL_ENABLED                 = tostring(var.rabbit_tls)
    EUREKA_DEFAULT_ZONE                         = "http://service-registry.mmiv.local:8761/eureka/,http://service-registry-peer.mmiv.local:8761/eureka/"
    USER_ACCESS_URL                             = "http://user-access-service"
    METADATA_URL                                = "http://discovery-service"
    REPORTING_URL                               = "http://reporting-service"
    TRANSFER_URL                                = "http://transfer-service"
    USER_ACCESS_JWK_SET_URI                     = "http://user-access-service/.well-known/jwks.json"
    USER_ACCESS_JWT_ISSUER                      = var.jwt_issuer
    SPRING_DATA_REDIS_HOST                      = var.redis_host
    SPRING_DATA_REDIS_PORT                      = tostring(var.redis_port)
    SPRING_DATA_REDIS_SSL_ENABLED               = tostring(var.redis_tls)
    MMIV_BUCKET_NAME                            = var.bucket_name
    AWS_REGION                                  = var.region
    MMIV_EVENTS_ENABLED                         = "true"
    MMIV_EVENTS_OUTBOX_DIRECTORY                = "/var/lib/mmiv/event-outbox"
    MMIV_MEDIA_ROOT                             = "/media"
    SERVER_SHUTDOWN                             = "graceful"
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE = "110s"
    JAVA_TOOL_OPTIONS                           = "-Xms64m -Xmx384m -XX:MaxDirectMemorySize=96m"
  }
  service_environment = {
    for name, port in local.ports : name => merge(local.dependencies, { SERVER_PORT = tostring(port) },
      contains(["service-registry", "service-registry-peer"], name) ? {
        EUREKA_PEER_ENABLED = "true"
        EUREKA_DEFAULT_ZONE = name == "service-registry" ? "http://service-registry-peer.mmiv.local:8761/eureka/" : "http://service-registry.mmiv.local:8761/eureka/"
      } : {},
      name == "api-gateway" ? {
        JWT_COOKIE_SECURE                    = tostring(var.gateway_cookie_secure)
        MMIV_RATE_LIMIT_TRUST_ALB_XFF        = "true"
        MMIV_RATE_LIMIT_TRUST_CLOUDFRONT_XFF = tostring(var.use_cloudfront_default_domain)
      } : {},
      name == "user-access-service" ? {
        ORACLE_JDBC_URL = var.oracle_jdbc_url
        ORACLE_USER     = var.user_access_db_username
        JWT_ISSUER      = var.jwt_issuer
        JWT_KEY_ID      = var.jwt_key_id
      } : {},
      name == "discovery-service" ? {
        ORACLE_JDBC_URL = var.oracle_jdbc_url
        ORACLE_USER     = var.discovery_db_username
      } : {},
      name == "transfer-service" ? {
        MMIV_COORDINATION_TABLE    = aws_dynamodb_table.transfer_coordination.name
        MMIV_COORDINATION_REQUIRED = "true"
      } : {},
      name == "integrity-service" ? {
        SPRING_PROFILES_ACTIVE = "oracle"
        INTEGRITY_DB_URL       = var.oracle_jdbc_url
        INTEGRITY_DB_USERNAME  = var.integrity_db_username
    } : {})
  }
  service_secrets = {
    for name in keys(local.ports) : name => merge({
      SPRING_RABBITMQ_PASSWORD = var.secret_arns.rabbit_password
      }, name == "api-gateway" && var.secret_arns.redis_password != null ? {
      SPRING_DATA_REDIS_PASSWORD = var.secret_arns.redis_password
      } : {}, name == "user-access-service" ? {
      ORACLE_PASSWORD     = var.secret_arns.user_access_db_password
      JWT_PRIVATE_KEY_PEM = var.secret_arns.jwt_private_key_pem
      JWT_PUBLIC_KEY_PEM  = var.secret_arns.jwt_public_key_pem
      } : {}, name == "discovery-service" ? {
      ORACLE_PASSWORD = var.secret_arns.discovery_db_password
      } : {}, name == "integrity-service" ? {
      INTEGRITY_DB_PASSWORD = var.secret_arns.integrity_db_password
      } : {}, name == "reporting-service" ? {
      MONGODB_URI = var.secret_arns.mongo_uri
    } : {})
  }
}
