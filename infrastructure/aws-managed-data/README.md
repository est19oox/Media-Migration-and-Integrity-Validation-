# Fresh AWS-managed data services

This separate Terraform root creates new private RDS Oracle, Amazon DocumentDB,
Amazon MQ RabbitMQ and ElastiCache Valkey services. It imports **no** records
from the old EC2 databases. `demo_single_az=true` is the default: Oracle runs
without a standby, DocumentDB has one instance and Valkey has one node. The
subnet groups still require subnets in at least two AZs. Setting
`demo_single_az=false` enables Oracle Multi-AZ, a second DocumentDB instance
and Valkey automatic failover. Amazon MQ is single-instance unless you provide
three subnet IDs in distinct AZs via `rabbit_cluster_subnet_ids`. A cluster
costs substantially more and needs a cluster-capable `rabbit_instance_type`;
the single-instance demo defaults to `mq.t3.micro`. Single-AZ mode does not
provide AZ failure recovery.

Use a **new encrypted, access-restricted Terraform S3 state key**. Terraform
state contains generated database and broker credentials, including the
DocumentDB connection URI. Protect state as a secret and enable state bucket
versioning. The managed services also incur ongoing AWS charges. Terraform
does not create an AWS account, VPC, subnets or ECS task security group.

1. Apply `infrastructure/ecs-fargate` with `start_services=false` and temporary
   endpoint/ARN placeholders to obtain `task_security_group_id`. Supply the VPC
   ID and at least two existing subnet IDs in different AZs here. Database
   security groups only accept traffic from that ECS security group.
2. Run `terraform init -backend-config=...`, `terraform plan -out=plan`, inspect
   the plan, then `terraform apply plan` in this directory. Keep its state key
   distinct from the EC2 and ECS roots. Check the selected Oracle and broker
   instance types and RabbitMQ engine version are available in your region.
3. On a runner with VPC access to RDS, IAM read access to the output secret
   ARNs, Python `boto3` and `oracledb`, run `bootstrap-oracle.py` with the RDS
   host and four Oracle secret ARNs shown by `terraform output`. This creates
   USER_ACCESS, DISCOVERY and INTEGRITY_SERVICE and the Integrity validation
   table. The service processes create their remaining tables on first boot.
4. Give the ECS root the output `oracle_jdbc_url`, `rabbit_host`, `redis_host`
   and the password/URI ARNs under `secret_arns`, including `redis_password`.
   Set `data_kms_key_arn` to the output KMS ARN. Create JWT PEM secrets
   separately. Set RabbitMQ to port 5671 with TLS and Valkey to port 6379 with
   TLS. Set `start_services=true` only after the Oracle bootstrap succeeds.
5. Reporting's Dockerfile imports the current AWS RDS CA bundle into its JVM
   truststore at image build time. Rebuild that image and verify its MongoDB
   driver works against the chosen DocumentDB engine version. DocumentDB is
   not full MongoDB; exercise
   report writes, reads, TTL/cleanup and index creation during staging.

No resources were created in AWS by adding this code. RDS and DocumentDB have
deletion protection and final snapshots to guard fresh data once deployed.
The legacy EC2 Terraform and Compose deployment jobs are disabled in the main
workflow. Quality checks and image builds remain active; ECS release automation
must be enabled after this new stack is applied and verified.
