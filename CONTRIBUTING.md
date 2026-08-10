# Contributing to DT3 Commons

Thank you for your interest in contributing to the DT3 Commons Platform SDK!

## Development Setup

### Prerequisites

- Python 3.10+
- Node.js 18+
- Java 17+ (for API contract compilation)
- Make

### Getting Started

```bash
git clone https://github.com/digitalt3/digitalt3-commons.git
cd digitalt3-commons
make install
make test
```

## How to Contribute

### Reporting Issues

- Use GitHub Issues for bug reports and feature requests
- Include reproduction steps for bugs
- Include expected vs actual behavior

### Pull Requests

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes following the guidelines below
4. Add tests for new functionality
5. Ensure all tests pass: `make test`
6. Ensure schema compliance: `make validate-schemas`
7. Commit with conventional commits: `feat: add new exporter`
8. Push and create a Pull Request

## Guidelines

### Code Style

- **Python**: Follow PEP 8, use type hints
- **TypeScript**: Follow the project's ESLint configuration
- **Java**: Follow Google Java Style Guide

### Specifications

- Language-neutral specs go in `specs/`
- Specs are the source of truth — SDKs must conform
- Changes to specs require updating all affected SDK implementations

### Schema Changes

- JSON Schema files in `schemas/` must use JSON Schema Draft 2020-12
- All schema changes require backward compatibility analysis
- Update `specs/versioning.yaml` when changing schemas

### Adding a New Language SDK

1. Create `packages/<language>/` following the API/SDK separation pattern
2. Implement all contracts from `specs/`
3. Add tests covering the 15 standard test scenarios
4. Add CI workflow in `.github/workflows/`
5. Update `docs/cross-language-contract.md`
6. Update the root README.md language support table

### Documentation

- Architecture decisions go in `docs/adr/` as numbered ADR files
- Standards documentation goes in `docs/`
- Each spec in `specs/` should have a corresponding doc in `docs/`

## Commit Convention

Use [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` — new feature
- `fix:` — bug fix
- `docs:` — documentation
- `refactor:` — code refactoring
- `test:` — adding or updating tests
- `chore:` — maintenance tasks
- `spec:` — specification changes
- `schema:` — schema changes

## Code of Conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
