# Docker Setup

## Overview

This directory contains the initial Docker configuration for the Media Migration and Integrity Validation application.

The Docker setup is created as part of the Sprint 0 infrastructure foundation.

## Java Version

The application is planned to use Java 25.

The Docker configuration uses Eclipse Temurin JDK 25 as the base image.

## Files

- `Dockerfile` - Defines the Java 25 container environment.
- `.dockerignore` - Excludes unnecessary and sensitive files from the Docker build context.

## Current Setup

The current Docker configuration provides a basic Java 25 container environment.

At this stage, the application build configuration has not yet been finalised. The Docker configuration can be updated when the Java application structure and build tool are available.

## Future Improvements

The Docker setup can later be extended to support:

- Maven or Gradle application builds
- JAR packaging
- Multi-stage Docker builds
- Application runtime configuration
- AWS and S3 environment configuration
- Container vulnerability scanning
