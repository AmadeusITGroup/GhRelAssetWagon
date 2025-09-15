# Maven Version Computation Strategies for GitHub Actions

## Research Summary

Based on comprehensive research of Maven versioning approaches in CI/CD environments, here are the key strategies and recommendations for automated version computation.

## 1. Version Computation Approaches

### 1.1 GitHub Release Tag-Based Versioning
**Strategy**: Use GitHub release tags to drive Maven version computation.

**Implementation**:
```yaml
- name: Update Maven version from release tag
  if: github.event_name == 'release'
  run: |
    mvn -B versions:set -DnewVersion=${{ github.event.release.tag_name }} -DgenerateBackupPoms=false
```

**Pros**:
- Simple and straightforward
- Manual control over version numbers
- Works well with GitHub's release workflow

**Cons**:
- Requires manual release creation
- No automatic semantic versioning

### 1.2 Semantic Versioning with Commit Messages
**Strategy**: Automatically compute versions based on conventional commit messages.

**Implementation**:
```yaml
- name: Generate Semantic Version
  id: version
  uses: paulhatch/semantic-version@v5.4.0
  with:
    tag_prefix: "v"
    major_pattern: "MAJOR:"
    minor_pattern: "MINOR:"
    patch_pattern: "PATCH:"
    version_format: "v${major}.${minor}.${patch}"
    bump_each_commit: false
    search_commit_body: true
```

**Pros**:
- Fully automated
- Follows semantic versioning principles
- Supports pre-release versions

**Cons**:
- Requires disciplined commit message conventions
- More complex setup

### 1.3 Git-Based Dynamic Versioning (jgitver)
**Strategy**: Compute versions dynamically from Git history without modifying POM files.

**Implementation**:
```xml
<plugin>
  <groupId>fr.brouillard.oss</groupId>
  <artifactId>jgitver-maven-plugin</artifactId>
  <version>1.9.0</version>
  <extensions>true</extensions>
</plugin>
```

**Pros**:
- No POM modifications needed
- Clean Git history
- Automatic version computation

**Cons**:
- Learning curve for team members
- Requires plugin configuration

## 2. Recommended Strategy for GhRelAssetWagon

Based on the project's current setup and requirements, the **GitHub Release Tag-Based Versioning** approach is recommended:

### 2.1 Rationale
1. **Simplicity**: Minimal changes to existing workflow
2. **Control**: Manual oversight of version numbers
3. **Compatibility**: Works seamlessly with existing Maven Central deployment
4. **Transparency**: Clear relationship between GitHub releases and Maven versions

### 2.2 Implementation Plan

#### Step 1: Version Extraction
Extract version from GitHub release tag or workflow input:

```yaml
- name: Determine version
  id: version
  run: |
    if [ "${{ github.event_name }}" = "release" ]; then
      VERSION="${{ github.event.release.tag_name }}"
    elif [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
      VERSION="${{ github.event.inputs.tag }}"
    else
      VERSION="0.0.1-SNAPSHOT"
    fi
    
    # Clean version (remove 'v' prefix if present)
    VERSION=$(echo "$VERSION" | sed 's/^v//')
    
    echo "version=$VERSION" >> $GITHUB_OUTPUT
    echo "Using version: $VERSION"
```

#### Step 2: Maven Version Update
Update POM version before build:

```yaml
- name: Update Maven version
  run: |
    echo "Updating Maven version to: ${{ steps.version.outputs.version }}"
    mvn -B versions:set -DnewVersion=${{ steps.version.outputs.version }} -DgenerateBackupPoms=false
    
    # Verify version was set correctly
    mvn help:evaluate -Dexpression=project.version -q -DforceStdout
```

#### Step 3: Artifact Naming Consistency
Ensure all artifacts use the computed version:

```yaml
- name: Verify artifact versions
  run: |
    VERSION="${{ steps.version.outputs.version }}"
    echo "Checking for artifacts with version: $VERSION"
    
    # List generated artifacts
    ls -la target/ghrelasset-wagon-${VERSION}*.jar || {
      echo "❌ Artifacts not found with expected version"
      exit 1
    }
    
    echo "✅ All artifacts have correct version"
```

## 3. Advanced Considerations

### 3.1 SNAPSHOT Handling
For development builds, use SNAPSHOT versions:

```yaml
- name: Handle SNAPSHOT versions
  if: github.event_name != 'release'
  run: |
    if [[ "${{ steps.version.outputs.version }}" != *"-SNAPSHOT" ]]; then
      VERSION="${{ steps.version.outputs.version }}-SNAPSHOT"
      echo "version=$VERSION" >> $GITHUB_OUTPUT
    fi
```

### 3.2 Version Validation
Validate version format before proceeding:

```yaml
- name: Validate version format
  run: |
    VERSION="${{ steps.version.outputs.version }}"
    
    # Check semantic version format (X.Y.Z or X.Y.Z-qualifier)
    if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
      echo "❌ Invalid version format: $VERSION"
      echo "Expected format: X.Y.Z or X.Y.Z-qualifier"
      exit 1
    fi
    
    echo "✅ Version format is valid: $VERSION"
```

### 3.3 Multi-Module Projects
For multi-module Maven projects, ensure all modules get the same version:

```yaml
- name: Update all module versions
  run: |
    mvn -B versions:set -DnewVersion=${{ steps.version.outputs.version }} -DgenerateBackupPoms=false -DprocessAllModules=true
```

## 4. Integration with Maven Central

### 4.1 Version Requirements
Maven Central requires:
- No SNAPSHOT versions in releases
- Consistent version across all artifacts
- Proper semantic versioning

### 4.2 Deployment Considerations
```yaml
- name: Validate for Maven Central
  run: |
    VERSION="${{ steps.version.outputs.version }}"
    
    if [[ "$VERSION" == *"-SNAPSHOT" ]]; then
      echo "❌ Cannot deploy SNAPSHOT version to Maven Central"
      exit 1
    fi
    
    echo "✅ Version is suitable for Maven Central: $VERSION"
```

## 5. Implementation Checklist

- [ ] Add version determination step to workflow
- [ ] Update Maven version before build
- [ ] Validate version format
- [ ] Ensure artifact naming consistency
- [ ] Handle SNAPSHOT versions appropriately
- [ ] Validate Maven Central compatibility
- [ ] Test with different version scenarios

## 6. Example Workflow Integration

```yaml
steps:
  - name: Checkout code
    uses: actions/checkout@v4
    
  - name: Determine version
    id: version
    run: |
      # Version computation logic here
      
  - name: Update Maven version
    run: |
      mvn -B versions:set -DnewVersion=${{ steps.version.outputs.version }} -DgenerateBackupPoms=false
      
  - name: Build and test
    run: |
      mvn clean verify -Dgpg.skip=false
      
  - name: Deploy to Maven Central
    run: |
      mvn clean deploy -DskipTests -Dgpg.skip=false
```

This approach provides a robust, maintainable solution for version computation that integrates seamlessly with the existing GitHub Actions workflow and Maven Central deployment process.
