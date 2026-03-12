# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability in archflow, please report it responsibly:

1. **Do NOT** open a public GitHub issue
2. Email security concerns to: **security@archflow.org**
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will acknowledge receipt within 48 hours and provide a detailed response within 7 days.

## Security Best Practices

### Credentials Management

- **Never** use default credentials in production
- Use environment variables for all secrets:
  ```bash
  SPRING_DATASOURCE_PASSWORD=<strong-password>
  ARCHFLOW_JWT_SECRET=<random-256-bit-key>
  OPENAI_API_KEY=<your-key>
  ```
- Rotate API keys regularly
- Use short-lived JWT tokens (default: 1 hour)

### Docker Deployment

The included `docker-compose.yml` is for **development only**. For production:

- Change all default passwords
- Enable Redis authentication
- Use TLS for database connections
- Run containers as non-root (already configured in Dockerfile)
- Use Docker secrets or external secret managers

### RBAC Configuration

archflow ships with 4 built-in roles:

| Role | Description |
|------|-------------|
| ADMIN | Full system access |
| DESIGNER | Create and edit workflows |
| EXECUTOR | Execute workflows |
| VIEWER | Read-only access |

Always follow the principle of least privilege when assigning roles.

### API Key Security

- API keys are hashed with SHA-256 before storage
- Keys can have scoped permissions
- Set expiration dates on all API keys
- Revoke unused keys promptly

## Dependencies

We regularly scan dependencies for known vulnerabilities. To run a local scan:

```bash
mvn org.owasp:dependency-check-maven:check
```

## Disclosure Policy

- Vulnerabilities will be patched in the next release
- Critical vulnerabilities will receive an emergency patch
- CVEs will be published for significant issues
- Credit will be given to reporters (unless anonymity is requested)
