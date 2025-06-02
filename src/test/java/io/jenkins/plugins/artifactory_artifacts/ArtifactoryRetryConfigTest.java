package io.jenkins.plugins.artifactory_artifacts;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for configurable retry mechanism in ArtifactoryGenericArtifactConfig.
 */
public class ArtifactoryRetryConfigTest {

    @Test
    public void shouldHaveDefaultRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        assertThat("Default max upload retries should be 3", config.getMaxUploadRetries(), equalTo(3));
        assertThat("Default retry delay should be 15 seconds", config.getRetryDelaySeconds(), equalTo(15L));
    }

    @Test
    public void shouldAllowCustomRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        config.setMaxUploadRetries(5);
        config.setRetryDelaySeconds(30);

        assertThat("Max upload retries should be configurable", config.getMaxUploadRetries(), equalTo(5));
        assertThat("Retry delay should be configurable", config.getRetryDelaySeconds(), equalTo(30L));
    }

    @Test
    public void shouldValidateMinimumRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        config.setMaxUploadRetries(0);
        config.setRetryDelaySeconds(0);

        // Should enforce minimum values
        assertThat("Min retries should be enforced", config.getMaxUploadRetries(), equalTo(1));
        assertThat("Min delay should be enforced", config.getRetryDelaySeconds(), equalTo(1L));
    }

    @Test
    public void shouldCreateConfigWithCustomRetryValues() {
        ArtifactoryGenericArtifactConfig config =
                new ArtifactoryGenericArtifactConfig("cred-id", "http://localhost:8081", "repo", "prefix", 5, 30);

        assertThat(
                "Config should have custom retry count",
                config.getMaxUploadRetries(),
                equalTo(5));
        assertThat(
                "Config should have custom retry delay",
                config.getRetryDelaySeconds(),
                equalTo(30L));
    }
}
