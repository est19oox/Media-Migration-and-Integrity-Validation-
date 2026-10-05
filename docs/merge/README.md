# Merge-ready MMIV transfer changes

This directory is a **change set only**. It is not the AWS setup branch and does not contain a merged copy of the repository.

## Files to add to the AWS setup branch

Copy these paths into the branch:

- `services/transfer-service/**`
- `infrastructure/ansible/**`

The transfer service is independent from the root Maven project. Build it from its own directory:

```bash
cd services/transfer-service
mvn -B clean package
```

## AWS compatibility

The service defaults to:

- region: `ap-southeast-1`
- bucket: `media-migration-dev-landing`

These match the AWS setup branch.

The service relies on the EC2 instance role for AWS credentials; no AWS access key is stored in the service or Ansible files.

## ECR

Build and push the image to the existing `mmiv` ECR repository, then set:

```text
MMIV_TRANSFER_IMAGE=<account>.dkr.ecr.ap-southeast-1.amazonaws.com/mmiv:transfer-service
ECR_REGISTRY=<account>.dkr.ecr.ap-southeast-1.amazonaws.com
```

## Ansible deployment

Install the collection:

```bash
ansible-galaxy collection install -r infrastructure/ansible/requirements.yml
```

Copy `inventory.ini.example` to an inventory file and replace `<EC2_ADDRESS>` with the EC2 address/identifier appropriate for the SSM inventory setup.

Run:

```bash
cd infrastructure/ansible
ansible-playbook -i inventory.ini playbook.yml
```

## Important merge dependency

The AWS setup branch currently provisions the S3 bucket and IAM users, while the original main branch provisions the EC2 instance and its instance profile. When the branches are eventually merged, the EC2 role must have both ECR pull permission and S3 write permission for the transfer service.
