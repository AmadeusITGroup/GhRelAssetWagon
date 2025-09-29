package io.github.amadeusitgroup.maven.wagon;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GhRelAssetRepositoryTest {

    @ParameterizedTest
    @CsvSource({
            "ghrelasset://my-org/my-repo/v1.0.0/my-asset.zip, my-org/my-repo, v1.0.0, my-asset.zip",
            "ghrelasset://another-org/another-repo/latest/another-asset.zip, another-org/another-repo, latest, another-asset.zip",
            "ghrelasset://a/b/c/d.zip, a/b, c, d.zip"
    })
    void testValidRepositoryUrl(String url, String expectedRepo, String expectedTag, String expectedAsset) {
        Repository repository = new Repository("someId", url);
        GhRelAssetRepository ghRelAssetRepository = new GhRelAssetRepository(repository);

        assertEquals(expectedRepo, ghRelAssetRepository.getGitHubRepository());
        assertEquals(expectedTag, ghRelAssetRepository.getTag());
        assertEquals(expectedAsset, ghRelAssetRepository.getAssetName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ghrelasset://invalid-url",
            "ghrelasset://org/repo/tag",
            "ghrelasset://org/repo/tag/asset-without-zip-extension",
            "ghrelasset://org/repo/tag/asset-ending-zip",
            "http://github.com/org/repo",
            ""
    })
    void testInvalidRepositoryUrl(String url) {
        Repository repository = new Repository("someId", url);
        assertThrows(IllegalArgumentException.class, () -> new GhRelAssetRepository(repository));
    }
}