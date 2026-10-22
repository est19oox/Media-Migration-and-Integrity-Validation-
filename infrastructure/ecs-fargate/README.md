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

1. Build and push all seven `service-name-<commit>` images and the
   `oracle-bootstrap-<commit>` image to ECR. Set `image_revision` to that exact
   commit. CI builds and scans all eight images.
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
   using the one-off ECS bootstrap Task before starting application services. Check DocumentDB
   API compatibility and TLS trust before cutover. Configure connection limits
   for the maximum Task counts, not just the initial counts.
4. The managed-data root creates RabbitMQ password, three Oracle schema
   passwords, a DocumentDB URI and a fresh JWT RSA key pair in Secrets Manager. Supply the
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
   Managed data services accept private traffic from the Task security group
   exported by the managed-data root. HTTPS egress and the required database ports
   must be allowed by their network policy.

Use a **new S3 state key** for this root. Example commands, with your reviewed
`terraform.tfvars` kept outside Git:

Apply `aws-managed-data` first; it creates and exports the Task security group.
Use the **Release MMIV to ECS** workflow with `prepare_base=true` to create
the ECS base and bootstrap Task Definition with `start_services=false`. Then
run **Initialize MMIV Oracle schemas**; it starts an idempotent Fargate Task
inside the VPC and calls the same release workflow to start services only after
the Task exits successfully. No placeholder apply or local Oracle client is
needed. These applies create chargeable infrastructure.
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
`task_security_group_id`, `oracle_master_secret_arn`,
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

After the first release, `.github/workflows/ecs-release.yml` can render ECS
inputs from managed-data state and the current Git commit SHA, verify all seven
ECR tags, plan, reject destructive changes, apply and wait for ECS stability.
Use its manual plan-only dispatch first. Automatic releases from `main` are
disabled until repository variable `MMIV_ECS_AUTO_DEPLOY=true` is set. The old
EC2 deployment jobs remain disabled.

For a first deployment, run **MMIV Microservices CI/CD** manually on the release
branch with `deploy_demo=true`. Its quality gates, seven service image builds,
and Oracle bootstrap image build must all succeed before it creates any managed
data. It then verifies the eight ECR tags for the exact commit, plans and
applies managed data, prepares the ECS base without running services, executes
the Oracle bootstrap Task, and starts the services. A failure stops dependent
jobs; after managed data is created, charges can continue even if a later job
fails, so use **Teardown MMIV ECS demo** when finished or abandoning the attempt.
Keep `MMIV_ECS_AUTO_DEPLOY=false` during the first deployment. The older
**Prepare MMIV managed data**, **Release MMIV to ECS**, and **Initialize MMIV
Oracle schemas** workflows remain available for plan review and recovery.
Each workflow uses the Terraform OIDC role and the same protected state
bucket. The bootstrap Task uses an IAM role limited to the
Oracle master and schema password secrets plus their KMS key. No full database
passwords are passed in Action inputs or ECS environment variables.

For the release workflow, set these GitHub repository variables on the
repository that runs Actions: `MMIV_STATE_BUCKET` (the existing protected
Terraform state bucket), `MMIV_VPC_ID`, `MMIV_PRIMARY_PUBLIC_SUBNET_ID`,
`MMIV_SECONDARY_PUBLIC_SUBNET_ID`, and optionally `MMIV_BUCKET_NAME`,
`MMIV_DEMO_SINGLE_AZ`, `USER_ACCESS_JWT_ISSUER` and
`USER_ACCESS_JWT_KEY_ID`. The workflow reads database addresses, Task
security group and all Secret ARNs from the managed-data state; it never
accepts raw passwords in repository variables. The seven ECR tags must exist
for the workflow commit. The Terraform OIDC role must have access to both
state keys, ECR read and the ECS/ALB/CloudFront/EFS/DynamoDB/IAM resources in
this root. Successful OIDC authentication by itself does not grant those
permissions. The workflow refuses an uninitialized ECS state and any plan
containing deletion or replacement. Run the plan-only dispatch after the
first deployment; set `MMIV_ECS_AUTO_DEPLOY=true` only after its output has
been reviewed. Automatic releases currently run on `main` after CI and all
seven image jobs pass. A manual dispatch can select an allowed branch.

## Stop the demo and its recurring infrastructure charges

The same shutdown applies whether tests pass or fail. First set repository
variable `MMIV_ECS_AUTO_DEPLOY=false` and cancel any running deployment. Set
`MMIV_BUILD_IMAGES=false` if further pushes should not build and store more
ECR images; disable the CI workflow as well if GitHub Actions minutes are no
longer needed. Run Actions > **Teardown MMIV ECS demo** once with `action=plan`
and inspect both plan artifacts. Then run it with `action=destroy` and enter
`DESTROY-MMIV-DEMO`. The workflow disables ALB/RDS/DocumentDB deletion
protection, destroys ECS first, then managed data with no final database
snapshots, and verifies both new Terraform states are empty. This permanently
removes the new databases and their contents. If teardown fails partway,
rerun it after investigating the error; any remaining state resources may
still incur charges. Do not delete the state objects before both states are
empty.

The workflow deliberately leaves pre-existing VPC/subnets, the shared
`media-migration-dev-landing` S3 bucket, the shared ECR repository, the
Terraform state bucket and any old EC2 stack alone. After teardown, inspect
AWS Billing/Cost Explorer and the RDS/DocumentDB snapshot lists, ECR images,
S3 objects/versions, CloudWatch logs and any old EC2 infrastructure. Delete
only items you own and no longer need; those shared or residual items can
continue to cost money. AWS billing data lags resource deletion.

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
