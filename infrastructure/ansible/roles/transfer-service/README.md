# Transfer service role

Deploys the MMIV transfer-service container to the EC2 instance created by the main branch Terraform.

The current main-branch security group does not expose SSH, so use the `amazon.aws.aws_ssm` Ansible connection plugin and ensure the EC2 instance has SSM access.

Required environment variables for an ECR deployment:

- `MMIV_TRANSFER_IMAGE`, e.g. `123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/mmiv:transfer-service`
- `ECR_REGISTRY`, e.g. `123456789012.dkr.ecr.ap-southeast-1.amazonaws.com`
- `MMIV_BUCKET_NAME`, normally `media-migration-dev-landing`
- `AWS_REGION`, normally `ap-southeast-1`
