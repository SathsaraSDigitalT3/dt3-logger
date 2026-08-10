# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability in DT3 Commons, please report it responsibly.

### How to Report

1. **Do NOT** open a public GitHub issue for security vulnerabilities
2. Email security concerns to: security@digitalt3.com
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### Response Timeline

- **Acknowledgment**: Within 48 hours
- **Assessment**: Within 1 week
- **Fix**: Depending on severity, within 1-4 weeks

### Scope

The following are in scope:

- Data masking bypass vulnerabilities
- Sensitive data leakage through logging
- Tenant context isolation failures
- Dependency vulnerabilities

### Out of Scope

- Vulnerabilities in example code (non-production)
- Issues in development-only configurations
- Feature requests

## Security Best Practices

When using DT3 Commons:

1. **Always enable masking** in production environments
2. **Review masking rules** to ensure all sensitive fields are covered
3. **Use STRICT validation** in CI/CD pipelines
4. **Do not log raw credentials** — the SDK masks known fields, but custom sensitive data must be configured
5. **Keep SDK versions updated** for security patches
