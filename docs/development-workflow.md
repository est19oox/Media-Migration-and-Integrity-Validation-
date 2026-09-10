# Development Workflow

## Sprint 0 Development Workflow

The project uses a feature branch workflow to support collaborative development.

## Workflow

1. Synchronise the local repository with the remote repository.
2. Create or switch to the assigned feature branch.
3. Implement the assigned change.
4. Review the modified files.
5. Commit related changes with a descriptive commit message.
6. Push the feature branch to GitHub.
7. Create a pull request.
8. Perform team review.
9. Merge the approved changes into the target branch.

## Example

```text
Repository
    |
    +-- main
    |
    +-- feature/docker-setup
            |
            +-- Docker foundation
            +-- Security scanning foundation
            +-- Environment configuration
```

## DevSecOps Considerations

Security should be considered throughout the development workflow.

The project may incorporate:

- Static application security testing
- Dependency scanning
- Container vulnerability scanning
- Infrastructure as Code security scanning
- Dynamic application security testing

Security checks can be progressively integrated into the CI/CD pipeline as the application implementation becomes available.

## Credential Management

Credentials must not be stored directly in source-controlled files.

Development and deployment credentials should be supplied through appropriate environment or credential management mechanisms.
