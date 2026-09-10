# Contributing Guide

## Overview

This document defines the initial development workflow for the Media Migration and Integrity Validation project.

## Branching

Development work should be performed on separate branches rather than directly on the main branch.

Recommended branch naming conventions:

- `feature/<feature-name>`
- `fix/<issue-name>`
- `docs/<documentation-name>`

Example:

`feature/docker-setup`

## Commits

Commits should contain related changes and use clear commit messages.

Examples:

- `Add initial Docker setup for Java 25`
- `Add DevSecOps security scanning foundation`
- `Add application environment configuration foundation`

## Pull Requests

When development work is ready for review:

1. Push the feature branch to GitHub.
2. Create a pull request to the appropriate target branch.
3. Describe the changes made.
4. Complete the pull request checklist.
5. Request review from the team.
6. Address review comments where required.
7. Merge only after the changes have been reviewed.

## Security

Do not commit:

- AWS access keys
- AWS secret access keys
- Passwords
- Private keys
- Environment files containing real credentials
- Other sensitive configuration

Example and placeholder configuration may be committed when it does not contain sensitive information.

## Code and Configuration Review

Before creating a pull request:

- Review changed files
- Confirm that unrelated files are not included
- Check configuration for exposed credentials
- Ensure documentation reflects the change
- Perform relevant tests or security checks where available
