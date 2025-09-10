# Maven Central Deployment Guide

This document provides comprehensive instructions for deploying the GhRelAssetWagon to Maven Central using GitHub Actions.

## Overview

The deployment process uses:
- **Sonatype Central Publishing Portal** (new Maven Central publishing system)
- **GitHub Actions** for automated CI/CD
- **GPG signing** for artifact verification
- **Semantic versioning** via GitHub releases

## Prerequisites

### 1. Sonatype Central Account Setup

1. Create an account at [Central Portal](https://central.sonatype.com/)
2. Generate a user token:
   - Go to Account → Generate User Token
   - Save the username and password securely

### 2. GPG Key Setup

Generate a GPG key for signing artifacts:

```bash
# Generate a new GPG key (non-interactive)
gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 4096
Subkey-Type: RSA
Subkey-Length: 4096
Name-Real: Your Name
Name-Email: your.email@example.com
Expire-Date: 2y
Passphrase: your-secure-passphrase
%commit
EOF

# Or generate interactively
gpg --gen-key

# List keys to get the key ID
gpg --list-secret-keys --keyid-format LONG

# Export the private key (for GitHub secrets)
gpg --armor --export-secret-keys YOUR_KEY_ID

# Export the public key (for verification)
gpg --armor --export YOUR_KEY_ID

# Test signing (important for CI validation)
echo "test" | gpg --clearsign --batch --yes --pinentry-mode loopback
```

**Important for CI/CD**: The GPG key must work in non-interactive mode. The workflow configures GPG with:
- `--batch`: Non-interactive mode
- `--yes`: Assume "yes" for questions
- `--pinentry-mode loopback`: Use passphrase from environment
- `--no-tty`: No terminal interaction

### 3. GitHub Repository Secrets

Configure the following secrets in your GitHub repository (`Settings → Secrets and variables → Actions`):

| Secret Name | Description | Example |
|-------------|-------------|---------|
| `OSSRH_USERNAME` | Sonatype Central username token | `abc123def` |
| `OSSRH_TOKEN` | Sonatype Central password token | `xyz789uvw` |
| `GPG_PRIVATE_KEY` | GPG private key (armored) | `-----BEGIN PGP PRIVATE KEY BLOCK-----...` |
| `GPG_PASSPHRASE` | GPG key passphrase | `your-secure-passphrase` |

**Note**: The `GPG_KEYNAME` secret is no longer required as the workflow automatically detects and uses the imported GPG key ID.

## Deployment Workflow

### Automatic Deployment (Release Trigger)

1. **Create a GitHub Release**:
   ```bash
   # Tag and push a new version
   git tag v1.0.0
   git push origin v1.0.0
   
   # Or create via GitHub UI
   # Go to Releases → Create a new release
   ```

2. **Workflow Execution**:
   - Triggered automatically on release creation
   - Runs tests and verification
   - Signs artifacts with GPG
   - Deploys to Maven Central Portal
   - Artifacts are staged for manual review

### Manual Deployment (Workflow Dispatch)

1. **Via GitHub UI**:
   - Go to Actions → Deploy to Maven Central
   - Click "Run workflow"
   - Choose dry run option if needed

2. **Dry Run Mode**:
   - Validates artifacts without publishing
   - Useful for testing configuration
   - Set `dry_run: true` in workflow dispatch

## Workflow Features

### 🛡️ **Security & Resilience**
- GPG signing for all artifacts
- Secure secret management
- Artifact validation before deployment
- Rollback capability via staging

### 📋 **Clear Process**
- Automated testing before deployment
- Build artifact caching for faster runs
- Comprehensive logging and status reporting
- Upload of build artifacts for debugging

### 🔄 **Flexibility**
- Release-triggered automatic deployment
- Manual deployment via workflow dispatch
- Dry run mode for validation
- Support for different deployment scenarios

## Artifact Requirements

The POM is configured to meet Maven Central requirements:

### ✅ **Required Metadata**
- Project name, description, and URL
- License information (MIT License)
- Developer information
- SCM (Source Control Management) details

### ✅ **Required Artifacts**
- Main JAR file
- Sources JAR (`-sources.jar`)
- Javadoc JAR (`-javadocs.jar`)
- GPG signatures for all artifacts (`.asc` files)

### ✅ **Plugin Configuration**
- **maven-source-plugin**: Generates sources JAR
- **maven-javadoc-plugin**: Generates Javadoc JAR
- **maven-gpg-plugin**: Signs all artifacts
- **central-publishing-maven-plugin**: Handles deployment to Central Portal

## Deployment Process Flow

```mermaid
graph TD
    A[GitHub Release Created] --> B[Workflow Triggered]
    B --> C[Checkout Code]
    C --> D[Setup JDK 8]
    D --> E[Cache Dependencies]
    E --> F[Import GPG Key]
    F --> G[Run Tests]
    G --> H[Verify Build]
    H --> I[Sign Artifacts]
    I --> J[Deploy to Central Portal]
    J --> K[Artifacts Staged]
    K --> L[Manual Review & Publish]
```

## Troubleshooting

### Common Issues

1. **GPG Signing Failures**:
   ```bash
   # Verify GPG key is properly imported
   gpg --list-secret-keys
   
   # Test signing locally
   echo "test" | gpg --clearsign
   ```

2. **Authentication Errors**:
   - Verify OSSRH credentials are correct
   - Check token permissions in Central Portal
   - Ensure secrets are properly configured

3. **Build Failures**:
   - Check Java version compatibility
   - Verify all dependencies are available
   - Review test failures in workflow logs

### Validation Commands

Test the configuration locally:

```bash
# Validate POM configuration
mvn validate

# Test artifact generation
mvn clean package

# Test signing locally (skip GPG for local development)
mvn clean verify -Dgpg.skip=true

# Test signing with GPG (requires GPG setup and passphrase)
mvn clean verify -Dgpg.skip=false

# Dry run deployment (requires credentials)
mvn clean deploy -DskipTests -Dcentral.autoPublish=false -Dgpg.skip=false
```

### Local Development vs CI Pipeline

**Local Development** (GPG signing disabled by default):
```bash
# Build without signing (faster for development)
mvn clean verify -Dgpg.skip=true

# Or set in your local settings.xml
<profiles>
  <profile>
    <id>dev</id>
    <activation>
      <activeByDefault>true</activeByDefault>
    </activation>
    <properties>
      <gpg.skip>true</gpg.skip>
    </properties>
  </profile>
</profiles>
```

**CI Pipeline** (GPG signing enabled):
- The workflow explicitly sets `-Dgpg.skip=false`
- GPG is configured for non-interactive use
- All artifacts are signed before deployment
- Signatures are uploaded as `.asc` files

## Post-Deployment

### 1. Central Portal Review
- Login to [Central Portal](https://central.sonatype.com/)
- Go to Deployments section
- Review staged artifacts
- Click "Publish" to release to Maven Central

### 2. Verification
- Check artifact availability on [Maven Central](https://search.maven.org/)
- Verify artifact signatures and metadata
- Test dependency resolution in consumer projects

### 3. Documentation Updates
- Update README with new version information
- Create release notes
- Update CHANGELOG if applicable

## Best Practices

### 🔐 **Security**
- Rotate GPG keys periodically
- Use strong passphrases
- Limit access to repository secrets
- Review deployment logs for sensitive information

### 📦 **Versioning**
- Follow semantic versioning (SemVer)
- Use meaningful release notes
- Tag releases consistently
- Maintain CHANGELOG

### 🚀 **Deployment**
- Always run tests before deployment
- Use dry run mode for testing
- Monitor deployment status
- Keep deployment artifacts for debugging

## Support Resources

- [Central Portal Documentation](https://central.sonatype.org/publish/publish-portal-maven/)
- [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Sonatype Central Publishing Plugin](https://github.com/sonatype/central-publishing-maven-plugin)
