# Maven Central GPG Signing Requirements Analysis

## Research Summary

Based on official Sonatype documentation and Maven Central requirements, here are the key findings:

### 1. Required Artifacts for Maven Central

For every `<artifactId>-<version>.jar` file, Maven Central requires:
- Main JAR: `ghrelasset-wagon-0.0.1.jar`
- Sources JAR: `ghrelasset-wagon-0.0.1-sources.jar`
- Javadoc JAR: `ghrelasset-wagon-0.0.1-javadoc.jar`
- POM file: `ghrelasset-wagon-0.0.1.pom`

### 2. GPG Signature Requirements

**All files must be signed with GPG/PGP** and include `.asc` signature files:
- `ghrelasset-wagon-0.0.1.jar.asc`
- `ghrelasset-wagon-0.0.1-sources.jar.asc`
- `ghrelasset-wagon-0.0.1-javadoc.jar.asc`
- `ghrelasset-wagon-0.0.1.pom.asc`

**Important**: `.asc` files themselves do NOT need checksum files or additional signatures.

### 3. Checksum Requirements

All primary files (but NOT .asc files) need checksums:
- `.md5` and `.sha1` are **REQUIRED**
- `.sha256` and `.sha512` are supported but optional

### 4. Maven GPG Plugin Configuration Issues

#### Problem Identified:
The current configuration has a critical flaw - it's trying to sign `.asc` files themselves, which is incorrect and causes the build failure we observed:

```
gpg: can't open '/home/runner/work/GhRelAssetWagon/GhRelAssetWagon/vulnerability-report.json': No such file or directory
```

#### Root Cause:
The `<files>` configuration in the GPG plugin is listing `.asc` files as files to be signed, which creates a circular dependency and attempts to sign non-existent files.

### 5. Correct GPG Plugin Configuration

The Maven GPG plugin should:
1. **NOT** explicitly list files to sign
2. Let Maven automatically determine which artifacts to sign
3. Use proper GPG arguments for CI/CD environments
4. Sign only the primary artifacts (JAR, sources, javadoc, POM)

### 6. Recommended Configuration

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-gpg-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <id>sign-artifacts</id>
            <phase>verify</phase>
            <goals>
                <goal>sign</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <keyname>${gpg.keyname}</keyname>
        <passphrase>${gpg.passphrase}</passphrase>
        <skip>${gpg.skip}</skip>
        <gpgArguments>
            <arg>--batch</arg>
            <arg>--yes</arg>
            <arg>--pinentry-mode</arg>
            <arg>loopback</arg>
            <arg>--no-tty</arg>
        </gpgArguments>
    </configuration>
</plugin>
```

### 7. Key Changes Needed

1. **Remove** the `<files>` configuration section entirely
2. **Keep** the `gpgArguments` for CI/CD compatibility
3. **Let Maven automatically determine** which artifacts to sign
4. **Ensure** the workflow generates the required artifacts before signing

### 8. Workflow Compatibility

The GitHub Actions workflow is correctly configured with:
- GPG key import and configuration
- Proper environment variables
- Non-interactive GPG setup

The issue is purely in the POM configuration, not the workflow.

## Conclusion

The GPG plugin configuration needs to be simplified to remove the explicit file listing and let Maven handle artifact signing automatically. This will resolve the build failure and ensure compliance with Maven Central requirements.
