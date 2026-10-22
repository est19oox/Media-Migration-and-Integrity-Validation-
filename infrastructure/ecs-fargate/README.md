# MMIV ECS Fargate demo and multi-AZ options

This is a separate, opt-in Terraform root. It does not modify the existing EC2
state or switch production traffic. It runs all seven Java images on ECS Fargate.
Gateway, Transfer, Integrity, User Access, Reporting and Discovery have
independent CPU target-tracking policies (demo minimum one Task, usually maximum
four; Integrity maximum six). Transfer also scales on memory utilization and
uses DynamoDB for shared folder and verification-run coordination. Two fixed
Eureka peers replicate registrations; they are intentionally not governed by a
CPU target-tracking policy because new Eureka peers need a stable peer identity
and replication topology. In demo mode, application Tasks run in one AZ. The
two subnets remain in different AZs because the ALB requires them. By default,
CloudFront provides an AWS-managed HTTPS `cloudfront.net` address without a
custom domain or ACM certificate. Its VPC Origin connects to an internal ALB,
whose HTTP listener accepts only VPC addresses and forwards requests only when
they carry the private origin header. Gateway Tasks remain in the primary AZ.
Its ECS-only rate-limit setting uses the CloudFront-appended viewer address
just before the ALB-appended hop; Task port 8080 accepts traffic
only from the ALB. Tasks have public egress addresses for ECR,
Secrets Manager and S3 but security groups permit inbound only from the ALB or
other MMIV Tasks. This is a deliberate no-NAT configuration; a private-subnet
deployment would need NAT or the necessary VPC endpoints.
CloudFront disables caching for every route and forwards cookies, headers,
query strings and upload methods. Its origin read timeout is 120 seconds;
uploads that take longer to return a response need a separate direct-to-S3
upload flow or an increased origin timeout quota. The origin token is stored in
Terraform state, so the state bucket must remain private.

## Required before planning or applying

1. Build and push all seven `service-name-<commit>` images to ECR. Set
   `image_revision` to that exact commit. The existing image workflow does this.
2. No domain or ACM certificate is required for the default
   `use_cloudfront_default_domain=true` demo. Open the `browser_url` Terraform
   output after apply. CloudFront takes time to distribute changes and adds
   request and data-transfer charges. Its VPC Origin reaches the internal ALB
   privately over HTTP; browsers connect to CloudFront over HTTPS. For a custom-domain ALB deployment,
   set `use_cloudfront_default_domain=false`, provide a valid ACM certificate
   ARN and configure DNS separately.
3. Apply `infrastructure/aws-managed-data` for fresh RDS Oracle, Amazon
   DocumentDB, Amazon MQ RabbitMQ and ElastiCache Valkey. This ECS root does
   **not** use the former EC2 data host. Initialize the three Oracle schemas
   using that root's bootstrap script before starting Tasks. Check DocumentDB
   API compatibility and TLS trust before cutover. Configure connection limits
   for the maximum Task counts, not just the initial counts.
4. The managed-data root creates RabbitMQ password, three Oracle schema
   passwords and a DocumentDB URI in Secrets Manager. Create a matching
   private/public JWT PEM key pair in Secrets Manager separately. Supply the
   output ARNs in `secret_arns`; the ECS root state contains ARNs, not secret
   values. Each entry must contain the raw value expected by its environment
   variable, rather than a JSON object. Supply `redis_password` from the
   managed-data output and set `data_kms_key_arn` to that root's KMS output,
   allowing ECS to decrypt its Secrets Manager values.
5. For a fresh deployment, accounts, metadata, history, EFS outboxes and
   `/media` start empty. Use a new S3 bucket if its objects must also start
   empty. If existing users, files or sessions must be kept,
   stop here and use a separately reviewed data and JWT-key migration.
6. Supply the IDs of the existing `MMIV-Public-Subnet` and
   `MMIV-Secondary-Public-Subnet`. Both must belong to `vpc_id` and be in
   different AZs. Terraform reuses them and does not create or change their
   route tables. Verify both have a route to an Internet Gateway before using
   public-IP Fargate Tasks; the ALB also requires both subnets.
   Existing stateful services must accept private traffic from the Terraform
   output `task_security_group_id`. HTTPS egress and the required database ports
   must be allowed by their network policy.

Use a **new S3 state key** for this root. Example commands, with your reviewed
`terraform.tfvars` kept outside Git:

Set `start_services=false` for the first ECS apply. This creates the ECS Task
security group without starting application Tasks against uninitialized data.
Apply the managed-data root using that security group ID, run its Oracle
bootstrap, set its output addresses and secret ARNs here, then set
`start_services=true` and apply again. Set `rabbit_tls=true`, `rabbit_port=5671`
and `redis_tls=true` for the managed endpoints.
Keep `demo_single_az=true` in both Terraform roots for the lower-cost demo.
The ECS root then starts one Task per scalable business service and can grow
it independently. Set it to `false` in both roots only for a multi-AZ release;
the managed-data change alters database replica counts and can incur downtime.
The two Eureka peers remain fixed at one Task each in both modes.

```sh
cd infrastructure/ecs-fargate
terraform init -backend-config=bucket=YOUR_STATE_BUCKET \
  -backend-config=key=ecs-fargate/terraform.tfstate \
  -backend-config=region=ap-southeast-1 -backend-config=encrypt=true \
  -backend-config=use_lockfile=true
terraform fmt -check
terraform validate
terraform plan -var-file=/secure/path/mmiv-ecs.tfvars -out=ecs.tfplan
terraform show ecs.tfplan
# Apply the reviewed plan only when the shared dependencies, secrets and images exist.
terraform apply ecs.tfplan
```

The variable file needs `vpc_id`, `primary_public_subnet_id`, `secondary_public_subnet_id`,
`image_repository_url`, `image_revision`, `bucket_arn`,
`bucket_name`, `oracle_jdbc_url`, `rabbit_host`, `redis_host`, `jwt_issuer`,
`jwt_key_id` and `secret_arns`. `certificate_arn` is needed only in the
custom-domain mode. RabbitMQ and Redis TLS can be enabled with
`rabbit_tls` / `redis_tls` and their corresponding ports. Keep the JWT issuer,
key ID and PEM bytes identical to the old deployment only if existing sessions
must remain valid. Set `gateway_cookie_secure=true` behind HTTPS. The ALB listens on port
443 only in custom-domain mode. In default mode, use the HTTPS `browser_url`
output; never browse to the ALB origin address.

## Release and cutover

Keep the old EC2 deployment serving users while ECS starts, if it is still in
use. Check ECS
service events and task logs, ALB target health, both Eureka peers'
`/eureka/apps`, and the expected UP registrations for each backend. Exercise signup/login,
upload, Integrity verification, download, permissions, delete and Reporting
history through the ALB. Run the load comparison and force-stop a Task. Verify
that the replacement registers and the EFS outbox drains. Switch
DNS only after these checks; keep the old stack available for a controlled
rollback if it contains user data.

To release another commit, build the seven immutable images, update
`image_revision` in the variable file, plan, review and apply. The existing
EC2 CI/CD workflow remains active and does not deploy ECS. Disable it or remove
its push trigger before declaring ECS the production deployment target.

## Operational limits

EFS access points separate each service's durable outbox. The publisher uses an
atomic `.sending` claim so replicas sharing one directory do not normally send
the same event simultaneously; an abandoned claim is eligible for retry after
two minutes. Broker ACK followed by Task death before unlink can still produce
an at-least-once duplicate, so consumers must deduplicate by `eventId`.
Eureka has two fixed peer Tasks, which should both be checked for registration
convergence. They do not have automatic scale-out. Transfer's DynamoDB lease is renewed while a Task holds
it, but an operation interrupted by a lost lease may need an application-level
retry. Throughput grows across folders; operations within one folder are
serialized to protect deletion and naming.
CPU scaling is a baseline for Reporting;
queue-depth scaling needs a CloudWatch metric for RabbitMQ backlog. Prometheus
currently has static scrape targets and will not automatically discover ECS
Tasks; the new `MMIV-ECS-Services` CloudWatch dashboard uses ECS/Container Insights
for CPU and running Task counts. Configure ECS-aware
Prometheus discovery before relying on the old Grafana dashboards.
