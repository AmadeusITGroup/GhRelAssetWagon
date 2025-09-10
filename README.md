

# Overview

The GhRelAssetWagon project is designed to integrate GitHub Release Assets as a Maven repository. This allows Maven projects to utilize binary assets stored in GitHub releases as dependencies. The core functionality is encapsulated in the [`GhRelAssetWagon`]("src/main/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagon.java") class, which extends Maven's [`AbstractWagon`]("org.apache.maven.wagon/AbstractWagon.class") to support fetching and uploading artifacts directly from/to GitHub Releases. This approach leverages GitHub's infrastructure for versioning and distributing binary assets, making it a cost-effective and scalable solution for Maven repository hosting.

# High level design

```
+------------------+      +---------------------+      +-----------------------+
| Maven Project    | ---> | GhRelAssetWagon     | ---> | GitHub Release Assets |
| (pom.xml)        |      | (Maven Wagon API)   |      | (as Maven Repository) |
+------------------+      +---------------------+      +-----------------------+
```

The diagram above illustrates the integration of [`GhRelAssetWagon`]("src/main/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagon.java") within the Maven ecosystem. It acts as a bridge between a Maven project and GitHub Release Assets, treating the latter as a Maven repository. This setup enables Maven to resolve and download dependencies stored as assets in GitHub releases.

## Details

The [`GhRelAssetWagon`]("src/main/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagon.java") class implements several key functionalities to interact with GitHub Release Assets:

1. **Authentication and Authorization**: Utilizes GitHub API tokens for secure access to private repositories or to increase rate limits on public repositories.
2. **Release and Asset Identification**: Fetches specific releases and their assets by tags, facilitating version control and dependency management.
3. **Redirect Handling**: Manually handles HTTP redirects to follow asset download links, accommodating GitHub's redirect mechanism for asset downloads.
4. **Asset Downloading**: Downloads assets directly to the local Maven repository or a specified directory, supporting the use of these assets as dependencies in Maven projects.

This solution can be extended to any storage system capable of exposing archive files over HTTP/S, not limited to GitHub. The flexibility of the [`GhRelAssetWagon`]("src/main/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagon.java") design allows it to be adapted for use with other platforms that offer similar asset hosting capabilities.

## Extra resources

- [Apache Maven](https://maven.apache.org/)
- [Maven Wagon Project](https://maven.apache.org/wagon/)
- [GitHub Release REST API](https://docs.github.com/en/rest/reference/repos#releases)
- [Java Development Kit (JDK)](https://openjdk.org/)

These resources provide additional information on Maven, the Maven Wagon API, GitHub's REST API for releases, and Java development, offering a comprehensive background for developers working with or extending the GhRelAssetWagon project.


# GhRelAssetWagon

GhRelAssetWagon is a Maven Wagon provider extension that handles the "ghrelasset://" scheme.

## Project Structure

```
GhRelAssetWagon
├── src
|   ├── main
|   |   ├── java
|   |   |   └── io
|   |   |       └── github
|   |   |           └── amadeusitgroup
|   |   |               └── maven
|   |   |                   └── wagon
|   |   |                       └── GhRelAssetWagon.java
|   |   └── resources
|   |       └── META-INF
|   |           └── services
|   |               └── org.apache.maven.wagon.Wagon
|   └── test
|       └── java
|           └── io
|               └── github
|                   └── amadeusitgroup
|                       └── maven
|                           └── wagon
|                               └── GhRelAssetWagonTest.java
├── pom.xml
└── README.md
```

## Files

- `src/main/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagon.java`: This file contains the implementation of the GhRelAssetWagon class, which is a Maven Wagon provider extension that handles the "ghrelasset://" scheme.

- `src/main/resources/META-INF/services/org.apache.maven.wagon.Wagon`: This file specifies the fully qualified name of the GhRelAssetWagon class as a service provider for the org.apache.maven.wagon.Wagon interface.

- `src/test/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagonTest.java`: This file contains the unit tests for the GhRelAssetWagon class.

- `pom.xml`: This file is the Maven project configuration file. It defines the project dependencies, build settings, and other project-specific configurations.

- `README.md`: This file contains the documentation and information about the project.

## Usage

The GhRelAssetWagon project can be used to integrate GitHub Release Assets as a Maven repository. This section provides instructions on how to publish and consume artifacts from GitHub Release Assets using the GhRelAssetWagon extension.

Note: The following instructions assume that you have a GitHub account and a repository where you can publish and consume artifacts. These instructions are also based on the assumption that you have a basic understanding of Maven and how to configure a Maven project. It is also possible to configure it diffenrently, for example by storing some of the configuration in the `settings.xml` file instead of the `pom.xml` file.

### Creating tokens

You should create a GitHub [fine grained personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-fine-grained-personal-access-token) with the `repo` scope - option: **Only select repositories** and select actual repository to hold the artifacts.

For the sake of security, it is recommended to create two tokens - one for read-only access and one for read-write access. The read-write token should be used only when publishing artifacts.

For consuming the artifacts you will need a read-only access. To reflect that in your token select the `Contents -> Read Only` in the menu.

To be able to publish the artifacts, you will need a read-write access. Select the `Contents -> Read and write` accordingly.


These tokens will need to be exposed as environment variables in the system where the Maven build will be executed. The environment variable should be named `GH_RELEASE_ASSET_TOKEN`. The token should be stored in a secure location and should not be exposed in the build logs or in the source code. 

Recommended way is to store the token in a file with a proper permissions applied and set the environment variable to the path of the file. Nevertheless, the token value can be directly set as an environment variable as well.

### Publishing artifacts to GitHub Release Assets

1. Add the GhRelAssetWagon build extension to your Maven project(`pom.xml`):


```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  ...
  <!--
  This section defines the distribution management configuration for the Maven project. 
  It specifies the repository where the project artifacts will be deployed.

  The repository element contains the details of the repository, including its ID and URL. 
  In this case, the repository ID is "github" and the URL is "ghrelasset://myorg/myrepo/0.0.1/demo-ghrel-m2-repo.zip".
  -->
    <distributionManagement>
      <repository>
        <id>github</id>
        <url>ghrelasset://myorg/myrepo/0.0.1/demo-ghrel-m2-repo.zip</url>
      </repository>
    </distributionManagement>

  <build>
    <!--
    This section of the pom.xml file defines the extensions for the project. 
    It includes the configuration for the "ghrelasset-wagon" extension, which is used for handling GitHub release assets. 
    The extension is specified with the groupId "io.github.amadeusitgroup.maven.wagon", the artifactId "ghrelasset-wagon", and the version "1.0.0".
    -->
    <extensions>
      <extension>
        <groupId>io.github.amadeusitgroup.maven.wagon</groupId>
        <artifactId>ghrelasset-wagon</artifactId>
        <version>1.0.0</version>
      </extension>
    </extensions>
  ...
  </build>
</project>

```

2. Export the secret token as an environment variable:

```bash
# Example location of the token - ensure it is secure and not exposed
chmod 600 /home/.local/GhRelAssetWagon/.rw_releases_my_repo_token
export GH_RELEASE_ASSET_TOKEN=/home/.local/GhRelAssetWagon/.rw_releases_my_repo_token
```

3. Deploy the project artifacts:

```bash
mvn clean deploy
```

### Consuming the artifacts from GitHub Release Assets

1. Add the GhRelAssetWagon build extension to your Maven project(`pom.xml`):

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  ...
  <!--
    This section defines the repositories for the project.
    The repository with the ID "github" is configured to use the "ghrelasset" protocol.
    The URL points to a zip file containing the demo-ghrel-m2-repo.zip repository.
  -->
  <repositories>
    <repository>
      <id>github</id>
      <url>ghrelasset://myorg/myrepo/0.0.1/demo-ghrel-m2-repo.zip</url>
    </repository>
  </repositories>
  ...
   <build>
    <extensions>
      <extension>
          <groupId>io.github.amadeusitgroup.maven.wagon</groupId>
          <artifactId>ghrelasset-wagon</artifactId>
          <version>1.0.0</version>
      </extension>
    </extensions>
    </build>
  ...
</project>

```

2. Export the secret token as an environment variable:

```bash
# Example location of the token - ensure it is secure and not exposed
chmod 600 /home/.local/GhRelAssetWagon/.ro_releases_my_repo_token
export GH_RELEASE_ASSET_TOKEN=/home/.local/GhRelAssetWagon/.ro_releases_my_repo_token
```

3. Build the project:

```bash
mvn clean install
```

## Testing

The GhRelAssetWagon project includes unit tests to verify the functionality of the GhRelAssetWagon class. The tests are located in the `src/test/java/io/github/amadeusitgroup/maven/wagon/GhRelAssetWagonTest.java` file and can be executed using the following command:

```bash
mvn test
```

## Contributing

Before contributing, please review the [Contribution Guidelines](CONTRIBUTING.md) for more information on the contribution process.

