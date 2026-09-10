# Security Quality Gates

## Overview

This directory defines the initial security quality gate policy for the Media Migration and Integrity Validation project.

The quality gate configuration is introduced during Sprint 0 as part of the DevSecOps foundation.

## Purpose

Security quality gates provide a common policy for evaluating security scan results before later stages of a CI/CD process.

This configuration does not implement or replace the project's CI/CD pipeline. It is designed to provide security criteria that can be consumed by the CI/CD implementation in later integration work.

## Security Gates

The initial policy covers:

- Static Application Security Testing (SAST)
- Software Composition Analysis (SCA)
- Container vulnerability scanning
- Infrastructure as Code (IaC) security scanning

## Configuration

`quality-gates.yml` defines the initial security gate policy and severity thresholds.

## Validation

`ci/scripts/validate-security-gates.sh` validates that the required security gate definitions are present in the configuration.

## CI/CD Integration

The quality gate policy can later be integrated with the project's CI/CD pipeline.

The CI/CD implementation remains responsible for executing build, test, deployment, and pipeline orchestration activities.

## Sprint 0 Scope

The current implementation establishes the security quality gate policy and validation foundation.

Integration with the operational CI/CD pipeline can be completed when the application build, security scan outputs, and deployment workflow are finalised.
