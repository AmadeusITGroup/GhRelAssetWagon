package io.github.amadeusitgroup.maven.wagon;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.wagon.repository.Repository;

public class GhRelAssetRepository extends Repository {

    private static final Pattern REPO_URL_PATTERN =
            Pattern.compile("^ghrelasset://(?<org>[^/]+)/(?<repo>[^/]+)/(?<tag>[^/]+)/(?<asset>[^/]+\\.zip)$");

    private final String repoOwner;
    private final String repoName;
    private final String tag;
    private final String assetName;

    public GhRelAssetRepository(Repository repository) {
        super(repository.getId(), repository.getUrl());

        Matcher m = REPO_URL_PATTERN.matcher(repository.getUrl());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid repository URL: " + repository.getUrl());
        }

        this.repoOwner = m.group("org");
        this.repoName = m.group("repo");
        this.tag = m.group("tag");
        this.assetName = m.group("asset");
    }

    public String getGitHubRepository() {
        return repoOwner + "/" + repoName;
    }

    public String getTag() {
        return tag;
    }

    public String getAssetName() {
        return assetName;
    }
}
