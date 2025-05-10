package io.jenkins.plugins.artifactory_artifacts;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.security.ACL;
import hudson.util.DescribableList;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

public final class Utils {

    private Utils() {}

    /**
     * Return the artifactory config or null if not configured
     * @return the artifactory config or null if not configured
     */
    public static @Nullable ArtifactoryGenericArtifactConfig getArtifactConfig() {
        ArtifactManagerConfiguration artifactManagerConfiguration = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> artifactManagerFactories =
                artifactManagerConfiguration.getArtifactManagerFactories();
        ArtifactoryArtifactManagerFactory artifactoryArtifactManagerFactory =
                artifactManagerFactories.get(ArtifactoryArtifactManagerFactory.class);
        if (artifactoryArtifactManagerFactory == null) {
            return null;
        }
        return artifactoryArtifactManagerFactory.getConfig();
    }

    /**
     * Get the credentials or null if not configured
     * @return the credentials or null if not configured
     */
    public static @Nullable StandardUsernamePasswordCredentials getCredentials() {
        ArtifactoryGenericArtifactConfig config = getArtifactConfig();
        if (config == null) {
            return null;
        }
        return getCredentials(config);
    }

    public static StandardUsernamePasswordCredentials getCredentials(String credentialsId) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)));
    }

    public static StandardUsernamePasswordCredentials getCredentials(ArtifactoryGenericArtifactConfig config) {
        return getCredentials(config.getStorageCredentialId());
    }

    static String urlEncodeParts(String s) {
        return URLEncoder.encode(s.replaceAll(" /", "/").replaceAll("/ ", "/"), StandardCharsets.UTF_8)
                .replace("%2F", "/")
                .replace("+", "%20");
    }

    /**
     * Get the URL of the artifact
     * @param name the name of the artifact
     * @return the URL of the artifact
     */
    public static String getUrl(String name) {
        return String.format(
                "%s/%s/%s",
                getArtifactConfig().getServerUrl(), getArtifactConfig().getRepository(), urlEncodeParts(name));
    }

    /**
     * Strip the trailing slash
     * @param key the key
     * @return the key without the trailing slash
     */
    public static String stripTrailingSlash(String key) {
        String localKey = key;
        if (key.endsWith("/")) {
            localKey = localKey.substring(0, localKey.length() - 1);
        }
        return localKey;
    }

    /**
     * Get the path with the prefix
     * @param key the key
     * @param path the path
     * @return the path with the prefix
     */
    public static String getFilePath(String key, String path) {
        return String.format("%s%s/%s", getArtifactConfig().getPrefix(), key, path);
    }

    /**
     * Get the path with the prefix
     * @param prefix the prefix. Can be null or empty. Must end with a slash if not empty.
     * @param filePath the file path
     * @return the path with the prefix
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static @NonNull String getPath(String prefix, @NonNull Path filePath) {
        String defaultPrefix =
                StringUtils.isBlank(prefix) ? "" : prefix.endsWith("/") ? prefix : String.format("%s/", prefix);
        return String.format("%s%s", defaultPrefix, filePath.getFileName().toString());
    }
}
