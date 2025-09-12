# Security Attestations and Provenance

This document describes the comprehensive security attestations and provenance artifacts generated during the GhRelAssetWagon release process.

## Overview

GhRelAssetWagon implements a robust security attestation framework that provides:

- **SLSA Level 3 Provenance**: Cryptographically signed build provenance
- **Software Bill of Materials (SBOM)**: Complete dependency inventory in multiple formats
- **Vulnerability Scanning**: Comprehensive security vulnerability reports
- **Code Signing**: GPG-signed artifacts with verification
- **Build Metadata**: Detailed build environment information
- **GitHub Attestations**: Native GitHub attestation framework integration

## Attestation Artifacts

### 1. SLSA Provenance (`*.intoto.jsonl`)

**Purpose**: Provides cryptographic proof of the build process integrity.

**Format**: in-toto attestation format (SLSA v1.0)

**Contents**:
- Build environment details
- Source code commit hash
- Build steps and parameters
- Artifact hashes and signatures
- Builder identity verification

**Verification**:
```bash
# Install slsa-verifier
go install github.com/slsa-framework/slsa-verifier/v2/cli/slsa-verifier@latest

# Verify artifact provenance
slsa-verifier verify-artifact ghrelasset-wagon-*.jar \
  --provenance-path *.intoto.jsonl \
  --source-uri github.com/wherka-ama/GhRelAssetWagon
```

### 2. Software Bill of Materials (SBOM)

#### SPDX Format (`sbom.spdx.json`)

**Purpose**: Industry-standard SBOM format for dependency tracking.

**Contents**:
- All direct and transitive dependencies
- License information
- Package relationships
- Security vulnerability references
- Component hashes and versions

#### CycloneDX Format (`sbom.cyclonedx.json`)

**Purpose**: Security-focused SBOM format with enhanced vulnerability data.

**Contents**:
- Component inventory with CPE identifiers
- Vulnerability references (CVE, CWE)
- License compliance information
- Component provenance data
- Risk assessment metadata

**Usage**:
```bash
# Analyze SBOM with dependency-track
docker run -d -p 8080:8080 dependencytrack/bundled
# Upload sbom.cyclonedx.json via web interface

# Scan SBOM for vulnerabilities
grype sbom:sbom.spdx.json
```

### 3. Vulnerability Reports

#### SARIF Format (`trivy-results.sarif`)

**Purpose**: Static Analysis Results Interchange Format for security findings.

**Contents**:
- Vulnerability details with severity levels
- Affected components and versions
- Remediation recommendations
- CVSS scores and vectors
- CWE classifications

#### JSON Format (`vulnerability-report.json`)

**Purpose**: Machine-readable vulnerability data for automation.

**Contents**:
- Structured vulnerability information
- Dependency tree with vulnerable paths
- Fix recommendations
- Risk assessment data

### 4. Code Signing (GPG)

#### Signature Files (`*.asc`)

**Purpose**: Cryptographic signatures for artifact integrity verification.

**Algorithm**: RSA-4096 with SHA-256

**Verification**:
```bash
# Import public key
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEY_ID>

# Verify signatures
gpg --verify ghrelasset-wagon-*.jar.asc ghrelasset-wagon-*.jar
gpg --verify ghrelasset-wagon-*.pom.asc ghrelasset-wagon-*.pom
```

### 5. Artifact Hashes

#### Individual Hash Files (`*.sha256`)

**Purpose**: File integrity verification.

**Format**: SHA-256 checksums

**Verification**:
```bash
# Verify individual files
sha256sum -c ghrelasset-wagon-0.0.1.jar.sha256

# Verify all artifacts
sha256sum -c artifacts.sha256
```

### 6. Build Metadata (`build-metadata.json`)

**Purpose**: Comprehensive build environment information.

**Contents**:
```json
{
  "build_timestamp": "2024-01-15T10:30:00Z",
  "git_commit": "abc123...",
  "git_ref": "refs/tags/v0.0.1",
  "workflow_run_id": "12345",
  "java_version": "17",
  "maven_version": "3.9.0",
  "build_environment": {
    "github_actor": "release-bot",
    "github_repository": "wherka-ama/GhRelAssetWagon",
    "runner_os": "Linux"
  }
}
```

## GitHub Attestations

### Build Provenance Attestation

Generated using `actions/attest-build-provenance@v1`:

- Links artifacts to their source code and build process
- Provides cryptographic proof of build integrity
- Enables supply chain verification
- Integrates with GitHub's dependency graph

### SBOM Attestation

Generated using `actions/attest-sbom@v1`:

- Associates SBOM with specific artifacts
- Enables automated dependency tracking
- Supports vulnerability monitoring
- Provides license compliance verification

## Maven Central Integration

### Attestation Publishing

Attestation artifacts are published to Maven Central alongside the main artifacts:

```xml
<dependency>
    <groupId>io.github.amadeusitgroup.maven.wagon</groupId>
    <artifactId>ghrelasset-wagon</artifactId>
    <version>0.0.1</version>
    <classifier>sbom-spdx</classifier>
    <type>json</type>
</dependency>
```

Available classifiers:
- `sbom-spdx`: SPDX format SBOM
- `sbom-cyclonedx`: CycloneDX format SBOM
- `vulnerability-report`: Security vulnerability report
- `build-metadata`: Build environment metadata

### Verification Workflow

1. **Download Artifacts**:
   ```bash
   mvn dependency:copy -Dartifact=io.github.amadeusitgroup.maven.wagon:ghrelasset-wagon:0.0.1
   mvn dependency:copy -Dartifact=io.github.amadeusitgroup.maven.wagon:ghrelasset-wagon:0.0.1:json:sbom-spdx
   ```

2. **Verify Signatures**:
   ```bash
   gpg --verify ghrelasset-wagon-0.0.1.jar.asc ghrelasset-wagon-0.0.1.jar
   ```

3. **Check SBOM**:
   ```bash
   grype sbom:ghrelasset-wagon-0.0.1-sbom-spdx.json
   ```

4. **Verify Provenance**:
   ```bash
   slsa-verifier verify-artifact ghrelasset-wagon-0.0.1.jar \
     --provenance-path provenance.intoto.jsonl \
     --source-uri github.com/wherka-ama/GhRelAssetWagon
   ```

## Security Benefits

### Supply Chain Security

- **Tamper Detection**: Cryptographic signatures prevent artifact modification
- **Source Verification**: SLSA provenance links artifacts to source code
- **Dependency Tracking**: SBOM enables vulnerability monitoring
- **Build Reproducibility**: Detailed build metadata supports reproducible builds

### Compliance Support

- **NIST SSDF**: Supports Secure Software Development Framework requirements
- **Executive Order 14028**: Meets federal software supply chain security requirements
- **SLSA Framework**: Implements Supply-chain Levels for Software Artifacts
- **SPDX/CycloneDX**: Industry-standard SBOM formats for compliance

### Risk Management

- **Vulnerability Monitoring**: Automated scanning and reporting
- **License Compliance**: Complete license inventory and analysis
- **Dependency Analysis**: Transitive dependency risk assessment
- **Incident Response**: Rapid identification of affected components

## Integration Examples

### CI/CD Pipeline Integration

```yaml
- name: Verify GhRelAssetWagon
  run: |
    # Download and verify artifacts
    mvn dependency:copy -Dartifact=io.github.amadeusitgroup.maven.wagon:ghrelasset-wagon:0.0.1
    gpg --verify ghrelasset-wagon-0.0.1.jar.asc ghrelasset-wagon-0.0.1.jar
    
    # Check for vulnerabilities
    mvn dependency:copy -Dartifact=io.github.amadeusitgroup.maven.wagon:ghrelasset-wagon:0.0.1:json:sbom-spdx
    grype sbom:ghrelasset-wagon-0.0.1-sbom-spdx.json --fail-on medium
```

### Security Scanning Integration

```bash
#!/bin/bash
# security-check.sh - Automated security verification

ARTIFACT="ghrelasset-wagon-0.0.1.jar"
SBOM="ghrelasset-wagon-0.0.1-sbom-spdx.json"

# Verify GPG signature
gpg --verify "${ARTIFACT}.asc" "$ARTIFACT" || exit 1

# Check vulnerabilities
grype "sbom:$SBOM" --fail-on high || exit 1

# Verify SLSA provenance
slsa-verifier verify-artifact "$ARTIFACT" \
  --provenance-path provenance.intoto.jsonl \
  --source-uri github.com/wherka-ama/GhRelAssetWagon || exit 1

echo "✅ All security checks passed"
```

## Troubleshooting

### Common Issues

1. **GPG Verification Fails**:
   - Ensure public key is imported: `gpg --recv-keys <KEY_ID>`
   - Check key trust level: `gpg --list-keys --with-colons`

2. **SLSA Verification Fails**:
   - Verify source URI matches exactly
   - Check provenance file format and integrity

3. **SBOM Analysis Issues**:
   - Ensure SBOM format is supported by analysis tool
   - Check for tool-specific configuration requirements

### Support Resources

- **GitHub Issues**: Report problems at https://github.com/wherka-ama/GhRelAssetWagon/issues
- **Security Advisories**: Monitor https://github.com/wherka-ama/GhRelAssetWagon/security/advisories
- **SLSA Documentation**: https://slsa.dev/
- **SPDX Specification**: https://spdx.github.io/spdx-spec/
- **CycloneDX Specification**: https://cyclonedx.org/specification/overview/

## Future Enhancements

### Planned Improvements

- **Sigstore Integration**: Keyless signing with Fulcio and Rekor
- **Container Attestations**: Support for container image attestations
- **Policy Enforcement**: Automated policy compliance checking
- **Enhanced Metadata**: Additional build environment and security metadata
- **Multi-Format Support**: Additional SBOM and attestation formats
