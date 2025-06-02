package io.jenkins.plugins.artifactory_artifacts;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jenkins.model.ArtifactManagerConfiguration;
import org.jvnet.hudson.test.JenkinsRule;

public class BaseTest {

    protected ArtifactoryGenericArtifactConfig configureConfig(JenkinsRule jenkinsRule, int port, String prefix)
            throws Exception {

        // Create generic config with optimized retry settings for faster tests
        ArtifactoryGenericArtifactConfig config = new ArtifactoryGenericArtifactConfig();
        config.setPrefix(prefix);
        config.setServerUrl("http://localhost:" + port);
        config.setRepository("my-generic-repo");
        config.setStorageCredentialId("the-credentials-id");
        config.setMaxUploadRetries(2);  // Use 2 retries for faster tests
        config.setRetryDelaySeconds(1); // Use 1 second delay for faster tests

        // Add credentials to the store
        UsernamePasswordCredentialsImpl credentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "the-credentials-id", "sample", "sample", "sample");
        CredentialsProvider.lookupStores(jenkinsRule.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), credentials);

        ArtifactoryArtifactManagerFactory artifactManagerFactory = new ArtifactoryArtifactManagerFactory(config);
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(artifactManagerFactory);
        return config;
    }

    /**
     * Setup WireMock stubs
     * @param wmRuntimeInfo the WireMock runtime info
     */
    protected void setupWireMockStubs(
            final String jobName, WireMock wireMock, int port, String prefix, String artifact, String stash) {

        // PUT to upload artifact
        wireMock.register(
                WireMock.put(WireMock.urlMatching("/my-generic-repo/.*")).willReturn(WireMock.okJson("{}")));

        // Define the base URL
        String artifactBasePath = "/api/storage/my-generic-repo/" + prefix + jobName + "/1/artifacts";
        String stashApiBasePath = "/api/storage/my-generic-repo/" + prefix + jobName + "/1/stashes";
        String stashFileBasePath = "/my-generic-repo/" + prefix + jobName + "/1/stashes";

        // JSON response for folder with children
        String artifactsResponse = "{"
                + "\"children\": [{\"folder\": false, \"uri\": \"/" + artifact + "\"}],"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + artifactBasePath + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + artifactBasePath
                + "\""
                + "}";
        String stashesResponse = "{"
                + "\"children\": [{\"folder\": false, \"uri\": \"/" + artifact + "\"}],"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + stashFileBasePath + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + stashFileBasePath
                + "\""
                + "}";

        String stashApiResponse = "{"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + stashApiBasePath + "/" + stash + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + stashApiBasePath + "/"
                + stash + "\""
                + "}";

        // AQL response
        String aqlResponse = "{\"results\": [{"
                + "\"name\": \"" + artifact
                + "\", \"type\": \"file\", \"modified\": \"2024-03-17T13:20:19.836Z\", \"size\": 1234,"
                + "\"repo\": \"my-generic-repo\", \"path\": \"" + prefix + "/" + jobName + "/1/artifacts\"}]}";

        // Register GET requests

        // Artifacts
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(artifactBasePath + "/")))
                .willReturn(WireMock.okJson(artifactsResponse)));

        // Stashes API
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashApiBasePath + "/" + stash)))
                .willReturn(WireMock.okJson(stashApiResponse)));

        // Stashes download
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashFileBasePath + "/")))
                .willReturn(WireMock.okJson(stashesResponse)));
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashFileBasePath + "/" + stash)))
                .willReturn(WireMock.ok().withBodyFile(stash).withHeader("Content-Type", "application/gzip")));

        // Register POST request
        wireMock.register(
                WireMock.post(WireMock.urlMatching("/api/search/aql")).willReturn(WireMock.okJson(aqlResponse)));
    }

    /**
     * Setup WireMock stubs excluding PUT requests (for retry tests)
     * @param wmRuntimeInfo the WireMock runtime info
     */
    protected void setupOtherWireMockStubs(
            final String jobName, WireMock wireMock, int port, String prefix, String artifact, String stash) {

        // Define the base URL
        String artifactBasePath = "/api/storage/my-generic-repo/" + prefix + jobName + "/1/artifacts";
        String stashApiBasePath = "/api/storage/my-generic-repo/" + prefix + jobName + "/1/stashes";
        String stashFileBasePath = "/my-generic-repo/" + prefix + jobName + "/1/stashes";

        // JSON response for folder with children
        String artifactsResponse = "{"
                + "\"children\": [{\"folder\": false, \"uri\": \"/" + artifact + "\"}],"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + artifactBasePath + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + artifactBasePath
                + "\""
                + "}";
        String stashesResponse = "{"
                + "\"children\": [{\"folder\": false, \"uri\": \"/" + artifact + "\"}],"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + stashFileBasePath + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + stashFileBasePath
                + "\""
                + "}";

        String stashApiResponse = "{"
                + "\"created\": \"2024-03-17T13:20:19.836Z\","
                + "\"createdBy\": \"admin\","
                + "\"lastModified\": \"2024-03-17T13:20:19.836Z\","
                + "\"lastUpdated\": \"2024-03-17T13:20:19.836Z\","
                + "\"modifiedBy\": \"admin\","
                + "\"path\": \"" + stashApiBasePath + "/" + stash + "\","
                + "\"repo\": \"my-generic-repo\","
                + "\"uri\": \"http://localhost:" + port + "/artifactory" + stashApiBasePath + "/"
                + stash + "\""
                + "}";

        // AQL response
        String aqlResponse = "{\"results\": [{"
                + "\"name\": \"" + artifact
                + "\", \"type\": \"file\", \"modified\": \"2024-03-17T13:20:19.836Z\", \"size\": 1234,"
                + "\"repo\": \"my-generic-repo\", \"path\": \"" + prefix + "/" + jobName + "/1/artifacts\"}]}";

        // Register GET requests only (no PUT)

        // Artifacts
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(artifactBasePath + "/")))
                .willReturn(WireMock.okJson(artifactsResponse)));

        // Stashes API
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashApiBasePath + "/" + stash)))
                .willReturn(WireMock.okJson(stashApiResponse)));

        // Stashes download
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashFileBasePath + "/")))
                .willReturn(WireMock.okJson(stashesResponse)));
        wireMock.register(WireMock.get(WireMock.urlEqualTo(urlEncodeParts(stashFileBasePath + "/" + stash)))
                .willReturn(WireMock.ok().withBodyFile(stash).withHeader("Content-Type", "application/gzip")));

        // Register POST request
        wireMock.register(
                WireMock.post(WireMock.urlMatching("/api/search/aql")).willReturn(WireMock.okJson(aqlResponse)));
    }

    private String urlEncodeParts(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replaceAll("%2F", "/")
                .replace("+", "%20");
    }
}
