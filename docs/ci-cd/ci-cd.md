# MMIV CI/CD

> **Status: Draft — Team Discussion Required**

## 1. Current Setup

MMIV currently uses both:

- **GitHub Actions**
- **Jenkins**

GitHub Actions provides automated validation and AWS deployment workflows.

Jenkins provides a reproducible Docker-based CI environment and can run builds, tests, security checks, and local integration validation.

## 2. Decisions Required

The team needs to confirm:

1. Which system is the authoritative CI pipeline?
2. Which checks are required before merging a PR?
3. Should a local Jenkins instance be a required merge check?
4. Which system is responsible for AWS deployment?
5. Which CI stages are intentionally shared between GitHub Actions and Jenkins?
6. How should duplicate builds and checks be avoided?

## 3. Current Principle

Until the responsibilities are agreed, GitHub Actions and Jenkins should be treated as separate CI/CD mechanisms.

Their final ownership and merge/deployment responsibilities should not be assumed.

Detailed Jenkins setup instructions are available under:

`docs/jenkins/`
