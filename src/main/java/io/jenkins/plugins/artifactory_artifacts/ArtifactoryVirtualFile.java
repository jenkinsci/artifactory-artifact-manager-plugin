package io.jenkins.plugins.artifactory_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.util.VirtualFile;
import org.jfrog.artifactory.client.model.AqlItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactoryVirtualFile extends ArtifactoryAbstractVirtualFile {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryVirtualFile.class);

    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String key;

    private final transient Run<?, ?> build;
    private final ArtifactoryClient.FileInfo fileInfo;

    public ArtifactoryVirtualFile(String key, Run<?, ?> build) {
        this.key = key;
        this.build = build;
        this.fileInfo = null;
    }

    public ArtifactoryVirtualFile(ArtifactoryClient.FileInfo fileInfo, Run<?, ?> build) {
        this.key = fileInfo.getPath();
        this.build = build;
        this.fileInfo = fileInfo;
    }

    public String getKey() {
        return key;
    }

    @NonNull
    @Override
    public String getName() {
        String localKey = Utils.stripTrailingSlash(key);

        localKey = localKey.replaceFirst(".*/artifacts/", "");

        // Return just the filename/foldername, not the full path
        int lastSlash = localKey.lastIndexOf('/');
        String result = lastSlash >= 0 ? localKey.substring(lastSlash + 1) : localKey;

        return result;
    }

    @NonNull
    @Override
    public URI toURI() {
        try {
            return new URI(Utils.getUrl(this.key));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        return new URL(Utils.getUrl(this.key));
    }

    @Override
    public VirtualFile getParent() {
        return new ArtifactoryVirtualFile(this.key.replaceFirst("/[^/]+$", ""), this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.isDirectory();
        }
        String keyWithNoSlash = Utils.stripTrailingSlash(this.key);
        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFolder(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a directory", this.key), e);
            return false;
        }
    }

    @Override
    public boolean isFile() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.isFile();
        }
        String keyS = this.key + "/";
        if (keyS.endsWith("/*view*/")) {
            return false;
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFile(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a file", this.key), e);
            return false;
        }
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() throws IOException {
        String prefix = Utils.stripTrailingSlash(this.key) + "/";
        List<VirtualFile> files = listFilesFromPrefix(prefix);
        if (files.isEmpty()) {
            return new VirtualFile[0];
        }
        return files.toArray(new VirtualFile[0]);
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String joinedKey = Utils.stripTrailingSlash(this.key) + "/" + name;
        return new ArtifactoryVirtualFile(joinedKey, build);
    }

    @Override
    public long length() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.getSize();
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.size(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get size of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public long lastModified() throws IOException {
        if (this.fileInfo != null) {
            return this.fileInfo.getLastUpdated();
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.lastUpdated(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get last updated time of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.debug(String.format("Opening %s...", this.key));
        if (isDirectory()) {
            throw new FileNotFoundException("Cannot open it because it is a directory.");
        }
        if (!isFile()) {
            throw new FileNotFoundException("Cannot open it because it is not a file.");
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.downloadArtifact(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to open %s", this.key), e);
            throw new IOException(e);
        }
    }

    private ArtifactoryClient buildArtifactoryClient() {
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        return new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
    }

    private String normalizePath(String path) {
        // Remove double slashes
        String normalized = path.replaceAll("//+", "/");

        // Remove leading slash
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    /**
     * List the files from a prefix
     * @param prefix the prefix
     * @return the list of files from the prefix
     */
    private List<VirtualFile> listFilesFromPrefix(String prefix) {
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            List<ArtifactoryClient.FileInfo> files = client.list(prefix);

            Set<String> seen = new HashSet<>();
            String normalizedPrefix = normalizePath(prefix);

            return files.stream()
                    .map(fileInfo -> {
                        String normalizedPath = normalizePath(fileInfo.getPath());

                        // Calculate relative path correctly
                        String relativePath = normalizedPath.startsWith(normalizedPrefix)
                                ? normalizedPath.substring(normalizedPrefix.length())
                                : normalizedPath;

                        // Get just the first filename/foldername, not the full path
                        int slashIndex = relativePath.indexOf('/');
                        String immediateName =
                                (slashIndex == -1) ? relativePath : relativePath.substring(0, slashIndex);

                        // Check uniqueness
                        if (!seen.add(immediateName)) {
                            return null;
                        }

                        boolean isFolder = (slashIndex != -1);

                        if (isFolder) {
                            String folderPath = normalizedPrefix + immediateName;
                            ArtifactoryClient.FileInfo folderInfo = new ArtifactoryClient.FileInfo(
                                    folderPath,
                                    fileInfo.getLastUpdated(),
                                    0, // folder size 0
                                    AqlItemType.FOLDER);
                            return new ArtifactoryVirtualFile(folderInfo, this.build);
                        } else {
                            return new ArtifactoryVirtualFile(fileInfo, this.build);
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to list files from prefix %s", prefix), e);
            return Collections.emptyList();
        }
    }
}
