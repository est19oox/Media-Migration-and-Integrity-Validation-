# Application Configuration

## Overview

This directory contains the initial configuration structure for the Media Migration and Integrity Validation application.

The configuration foundation is introduced during Sprint 0 to support consistent application setup across development environments.

## Example Configuration

`application.example.yml` provides example configuration for:

- Application environment
- Source file location
- AWS region
- Amazon S3 destination
- Supported media file types
- Concurrent uploads
- Retry settings
- Checksum validation
- Logging

## Environment Variables

The repository root contains an `.env.example` file showing the environment variables that may be required by the application.

The example file contains placeholder values only.

Real credentials and sensitive environment configuration must not be committed to source control.

## Configuration Security

AWS credentials should be provided securely through the runtime environment or an approved credential management mechanism.

Sensitive values should not be stored directly in application configuration files.

## Future Integration

The configuration structure can be updated when the Java application implementation and final configuration mechanism are available.
