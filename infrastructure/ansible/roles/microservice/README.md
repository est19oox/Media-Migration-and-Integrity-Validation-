# Generic microservice deployment role

This is the common Ansible deployment contract for MMIV microservices.

It installs Docker/AWS CLI, authenticates to ECR, captures the previous image,
deploys the requested image, performs a health check, and restores the previous
image if deployment fails.
