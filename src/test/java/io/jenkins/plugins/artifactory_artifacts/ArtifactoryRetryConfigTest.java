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

        assertThat("Default max upload retries should be 0", config.getMaxUploadRetries(), equalTo(0));
        assertThat("Default retry delay should be 5 seconds", config.getRetryDelaySeconds(), equalTo(5));
    }

    @Test
    public void shouldAllowCustomRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        config.setMaxUploadRetries(5);
        config.setRetryDelaySeconds(30);

        assertThat("Max upload retries should be configurable", config.getMaxUploadRetries(), equalTo(5));
        assertThat("Retry delay should be configurable", config.getRetryDelaySeconds(), equalTo(30));
    }

    @Test
    public void shouldAllowZeroRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        config.setMaxUploadRetries(0);
        config.setRetryDelaySeconds(0);

        // Should allow zero values
        assertThat("Zero retries should be allowed", config.getMaxUploadRetries(), equalTo(0));
        assertThat("Zero delay should be allowed", config.getRetryDelaySeconds(), equalTo(0));
    }

    @Test
    public void shouldPreventNegativeRetryValues() {
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();

        config.setMaxUploadRetries(-1);
        config.setRetryDelaySeconds(-1);

        // Should enforce minimum of 0
        assertThat("Negative retries should be set to 0", config.getMaxUploadRetries(), equalTo(0));
        assertThat("Negative delay should be set to 0", config.getRetryDelaySeconds(), equalTo(0));
    }

    @Test
    public void shouldCreateConfigWithCustomRetryValues() {
        ArtifactoryGenericArtifactConfig config =
                new ArtifactoryGenericArtifactConfig("cred-id", "http://localhost:8081", "repo", "prefix", 5, 30);

        assertThat("Config should have custom retry count", config.getMaxUploadRetries(), equalTo(5));
        assertThat("Config should have custom retry delay", config.getRetryDelaySeconds(), equalTo(30));
    }

    @Test
    public void shouldCreateConfigWithZeroRetryValues() {
        ArtifactoryGenericArtifactConfig config =
                new ArtifactoryGenericArtifactConfig("cred-id", "http://localhost:8081", "repo", "prefix", 0, 0);

        assertThat("Config should allow zero retry count", config.getMaxUploadRetries(), equalTo(0));
        assertThat("Config should allow zero retry delay", config.getRetryDelaySeconds(), equalTo(0));
    }
}
