#!/usr/bin/env python3
"""Recover existing bootstrap resources into the configured Terraform backend."""
import subprocess
import sys

import boto3
from botocore.exceptions import ClientError

ACCOUNT = '275069781906'
BUCKET = 'mmiv-terraform-state-275069781906'
TF_ROLE = 'GitHubActions-MMIV-Terraform'
APP_ROLE = 'GitHubActions-MMIV-App'


def run(*args):
    return subprocess.run(args, text=True, capture_output=True)


def optional(call, missing_codes, **kwargs):
    try:
        call(**kwargs)
        return True
    except ClientError as exc:
        if exc.response['Error']['Code'] in missing_codes:
            return False
        raise


def main():
    session = boto3.Session(region_name='ap-southeast-1')
    actual = session.client('sts').get_caller_identity()['Account']

    if actual != ACCOUNT:
        raise RuntimeError(
            f'Wrong AWS account: {actual}; expected {ACCOUNT}'
        )

    s3 = session.client('s3')
    iam = session.client('iam')

    # Verify baseline resources before writing anything to state.
    s3.head_bucket(Bucket=BUCKET, ExpectedBucketOwner=ACCOUNT)
    iam.get_role(RoleName=TF_ROLE)
    iam.get_role(RoleName=APP_ROLE)

    oidc = (
        f'arn:aws:iam::{ACCOUNT}:'
        'oidc-provider/token.actions.githubusercontent.com'
    )
    iam.get_open_id_connect_provider(OpenIDConnectProviderArn=oidc)

    imports = [
        ('aws_s3_bucket.terraform_state', BUCKET),
        ('aws_iam_openid_connect_provider.github', oidc),
        ('aws_iam_role.github_terraform', TF_ROLE),
        ('aws_iam_role.github_app', APP_ROLE),
    ]

    if optional(
        s3.get_public_access_block,
        {'NoSuchPublicAccessBlockConfiguration'},
        Bucket=BUCKET,
    ):
        imports.append((
            'aws_s3_bucket_public_access_block.terraform_state',
            BUCKET,
        ))

    if optional(
        s3.get_bucket_encryption,
        {'ServerSideEncryptionConfigurationNotFoundError'},
        Bucket=BUCKET,
    ):
        imports.append((
            'aws_s3_bucket_server_side_encryption_configuration.terraform_state',
            BUCKET,
        ))

    s3.get_bucket_versioning(Bucket=BUCKET)
    imports.append(('aws_s3_bucket_versioning.terraform_state', BUCKET))

    attachments = {
        TF_ROLE: [
            ('github_terraform_ecr', 'AmazonEC2ContainerRegistryFullAccess'),
            ('github_terraform_ec2', 'AmazonEC2FullAccess'),
            ('github_terraform_iam', 'IAMFullAccess'),
            ('github_terraform_vpc', 'AmazonVPCFullAccess'),
            ('github_terraform_s3', 'AmazonS3FullAccess'),
        ],
        APP_ROLE: [
            ('github_app_ecr', 'AmazonEC2ContainerRegistryPowerUser'),
        ],
    }

    for role, policies in attachments.items():
        existing = {
            policy['PolicyArn']
            for page in iam.get_paginator(
                'list_attached_role_policies'
            ).paginate(RoleName=role)
            for policy in page['AttachedPolicies']
        }

        for address, policy in policies:
            arn = f'arn:aws:iam::aws:policy/{policy}'
            if arn in existing:
                imports.append((
                    f'aws_iam_role_policy_attachment.{address}',
                    f'{role}/{arn}',
                ))

    for address, name in [
        ('github_app_s3_deploy', 'MMIVAppS3DeployPolicy'),
        ('github_app_ssm_deploy', 'MMIVAppSSMDeployPolicy'),
    ]:
        if optional(
            iam.get_role_policy,
            {'NoSuchEntity'},
            RoleName=APP_ROLE,
            PolicyName=name,
        ):
            imports.append((
                f'aws_iam_role_policy.{address}',
                f'{APP_ROLE}:{name}',
            ))
        else:
            print(
                f'{name} does not exist; '
                'the deployment workflow will create it.'
            )

    result = run('terraform', 'state', 'list')

    if (
        result.returncode
        and 'No state file was found' not in result.stderr + result.stdout
    ):
        raise RuntimeError(result.stderr or result.stdout)

    tracked = (
        set(result.stdout.splitlines())
        if result.returncode == 0 else set()
    )

    allowed = {address for address, _ in imports}
    allowed.update({
        'aws_s3_bucket_public_access_block.terraform_state',
        'aws_s3_bucket_server_side_encryption_configuration.terraform_state',
        'aws_iam_role_policy.github_app_s3_deploy',
        'aws_iam_role_policy.github_app_ssm_deploy',
        'data.aws_caller_identity.current',
    })

    if tracked - allowed:
        raise RuntimeError(
            'Unexpected resources in target state; '
            'verify the bootstrap backend before recovery.'
        )

    for address, resource_id in imports:
        if address in tracked:
            print(f'Already tracked: {address}')
            continue

        subprocess.run([
            'terraform',
            'import',
            '-input=false',
            '-lock-timeout=60s',
            address,
            resource_id,
        ], check=True)

    print(
        'Existing bootstrap resources imported. '
        'Review the following plan before running deployment.'
    )


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'Recovery failed: {exc}', file=sys.stderr)
        sys.exit(1)