package io.jenkins.plugins.artifactory_artifacts;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
public class ConfigurationAsCodeTest extends BaseTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    public void shouldSupportConfigurationAsCode(JenkinsConfiguredWithCodeRule jenkinsRule) throws Exception {
        ArtifactoryGenericArtifactConfig.DescriptorImpl extension = jenkinsRule
                .getInstance()
                .getExtensionList(ArtifactoryGenericArtifactConfig.DescriptorImpl.class)
                .get(0);
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        assertThat(config.getStorageCredentialId(), is("the-credentials-id"));
        assertThat(config.getServerUrl(), is("http://localhost:7000"));
        assertThat(config.getRepository(), is("my-generic-repo"));
        assertThat(config.getPrefix(), is("jenkins/"));
        assertThat(config.getMaxUploadRetries(), is(3));
        assertThat(config.getRetryDelaySeconds(), is(10));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-zero-retries.yml")
    public void shouldSupportConfigurationAsCodeWithZeroRetries(JenkinsConfiguredWithCodeRule jenkinsRule)
            throws Exception {
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        assertThat(config.getStorageCredentialId(), is("the-credentials-id"));
        assertThat(config.getServerUrl(), is("http://localhost:7000"));
        assertThat(config.getRepository(), is("my-generic-repo"));
        assertThat(config.getPrefix(), is("jenkins/"));
        assertThat(config.getMaxUploadRetries(), is(0));
        assertThat(config.getRetryDelaySeconds(), is(0));
    }
}
