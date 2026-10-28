# Contributing to Conduit

Thanks for your interest in contributing to Conduit! Contributions of bug fixes, features, documentation, and improvements are welcome.

## Before You Start

- Check existing issues and pull requests to avoid duplicating work.
- For substantial changes, open an issue first to discuss the proposed approach.
- If you want to work on an existing issue, comment on it before starting so maintainers can coordinate contributions.

## Development Prerequisites

- Java 21
- Git
- Internet access to download Maven dependencies

Conduit includes the Maven Wrapper, so a separate Maven installation is not required.

## Getting Started

1. Fork the repository on GitHub.
2. Clone your fork:

   ```bash
   git clone https://github.com/YOUR_USERNAME/conduit.git
   cd conduit
   ```

3. Build the project:

   **Linux or macOS**

   ```bash
   ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package
   ```

   **Windows PowerShell**

   ```powershell
   .\mvnw.cmd --batch-mode --no-transfer-progress -Dmaven.test.skip=true package
   ```

The build command compiles and packages the application without compiling or running test sources. Automated test validation is not currently part of the required CI check.

## Branching

Create a branch for each change. Suggested prefixes:

- `feat/` for features
- `fix/` for bug fixes
- `docs/` for documentation
- `refactor/` for refactoring
- `ci/` for CI changes

For example:

```bash
git switch -c fix/describe-your-change
```

Avoid combining unrelated changes in one pull request.

## Making Changes

- Keep changes focused and consistent with the existing codebase.
- Prefer clear names and readable code.
- Update relevant documentation when behavior or configuration changes.
- Add or update tests for behavior changes when practical.
- Never commit credentials, API tokens, passwords, or private configuration.
- Do not include unrelated formatting or generated-file changes.

## Commit Messages

Use concise messages that explain the change. Suggested prefixes:

- `feat:` — feature
- `fix:` — bug fix
- `docs:` — documentation
- `refactor:` — refactoring
- `test:` — tests
- `ci:` — continuous integration

Examples:

```text
feat: support workflow timezone configuration
fix: prevent duplicate scheduled executions
docs: clarify workflow scheduling behavior
```

## Pull Request Process

1. Push your branch to your fork.
2. Open a pull request targeting the `master` branch of the upstream repository.
3. Explain the problem, the approach, and any relevant trade-offs.
4. Link the related issue, if one exists.
5. Include testing or build results and clearly disclose any checks you could not run.
6. Respond to review feedback and keep the pull request focused.

Before submitting, verify your changes:

```bash
git diff --check
```

Run the applicable build command from the Getting Started section.

## Reporting Bugs and Requesting Features

Use the repository's GitHub issue templates for bug reports and feature requests. Include reproduction steps, relevant environment details, and sanitized logs when applicable.

Please remove secrets and sensitive data from logs before posting.

## Questions

If you are unsure about the scope of a change, open an issue to discuss it before investing significant time. Thanks for helping improve Conduit!