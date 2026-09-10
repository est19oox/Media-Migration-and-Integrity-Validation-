# DevSecOps Security Scanning

## Overview

This directory contains the initial security scanning configuration for the Media Migration and Integrity Validation project.

The configuration is introduced as part of the Sprint 0 DevSecOps foundation.

## Container Security Scanning

Trivy is used as the initial container security scanning tool.

The configuration focuses on:

- HIGH severity vulnerabilities
- CRITICAL severity vulnerabilities
- Container misconfigurations
- Secret detection

The container scanning script is located at:

`security/scripts/scan-container.sh`

## Infrastructure as Code Security Scanning

Checkov is used to perform security checks against Terraform Infrastructure as Code configurations.

The IaC scanning script is located at:

`security/scripts/scan-iac.sh`

## Current Status

This Sprint 0 implementation establishes the initial security scanning structure and configuration.

The scanning tools are not required to be installed locally at this stage.

## Future Integration

The security scanning configuration can later be integrated into the CI/CD pipeline to perform automated security checks during application builds and infrastructure changes.
