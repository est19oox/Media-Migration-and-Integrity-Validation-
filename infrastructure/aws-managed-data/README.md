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
state contains generated database and broker credentials, the JWT private key,
and the DocumentDB connection URI. Protect state as a secret and enable state bucket
versioning. The managed services also incur ongoing AWS charges. Terraform
does not create an AWS account, VPC or subnets. It creates and exports the ECS
Task security group, so a placeholder ECS apply is unnecessary.

1. Supply the VPC ID and at least two existing subnet IDs in different AZs.
   Database security groups only accept traffic from the Task security group
   created in this root.
2. Set the repository variables listed in the ECS README. For the full first
   deployment, run **MMIV Microservices CI/CD** with `deploy_demo=true`; it
   creates these resources only after the quality gates and all eight image
   builds pass. For a separate plan review or recovery, run **Prepare MMIV
   managed data** with `apply=false`, then `apply=true` if appropriate. The
   workflow performs `terraform init`, `plan` and `apply` with the dedicated
   state key. Alternatively run the same Terraform commands locally. Keep its state key
   distinct from the EC2 and ECS roots. Check the selected Oracle and broker
   instance types and RabbitMQ engine version are available in your region.
3. The full CI/CD run continues with **Release MMIV to ECS** using
   `prepare_base=true`, then **Initialize MMIV Oracle schemas**. For manual
   recovery, run those workflows in that order. The bootstrap Task starts
   inside the VPC, waits for a successful exit, and then starts all ECS
   services using the current commit's seven images. The script is idempotent. It creates
   USER_ACCESS, DISCOVERY and INTEGRITY_SERVICE and the Integrity validation
   table; service processes create their remaining tables on first boot.
   The older direct `bootstrap-oracle.py` command remains available as a
   recovery option from a VPC-connected runner.
4. Give the ECS root the output `task_security_group_id`, `oracle_jdbc_url`, `rabbit_host`, `redis_host`
   and the password/URI ARNs under `secret_arns`, including `redis_password`.
   Set `data_kms_key_arn` to the output KMS ARN. The fresh JWT RSA key pair and
   its two Secrets Manager ARNs are generated here as well. The workflows
   transfer these outputs automatically. Set RabbitMQ to port 5671 with TLS and
   Valkey to port 6379 with TLS. Services start only after Oracle bootstrap succeeds.
5. Reporting's Dockerfile imports the current AWS RDS CA bundle into its JVM
   truststore at image build time. Rebuild that image and verify its MongoDB
   driver works against the chosen DocumentDB engine version. DocumentDB is
   not full MongoDB; exercise
   report writes, reads, TTL/cleanup and index creation during staging.

No resources were created in AWS by adding this code. RDS and DocumentDB have
deletion protection and final snapshots to guard fresh data once deployed.
The explicit **Teardown MMIV ECS demo** workflow disables those protections
and uses `teardown_mode=true` to skip final snapshots for disposable demo data.
The legacy EC2 Terraform and Compose deployment jobs are disabled in the main
workflow. Quality checks and image builds remain active; enable ECS automatic
releases only after this new stack is applied and verified.
