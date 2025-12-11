package io.jenkins.plugins.artifactory_artifacts;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.jfrog.artifactory.client.*;
import org.jfrog.artifactory.client.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ArtifactoryClient implements AutoCloseable {

    public static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryClient.class);

    private final ArtifactoryConfig config;
    private final Artifactory artifactory;

    public ArtifactoryClient(
            @NonNull String serverUrl, @NonNull String repository, @NonNull UsernamePasswordCredentials credentials) {
        this.config = new ArtifactoryConfig(serverUrl, repository, credentials);
        this.artifactory = buildArtifactory();
    }

    public ArtifactoryClient(@NonNull ArtifactoryConfig config) {
        this(config.getServerUrl(), config.getRepository(), config.getCredentials());
    }

    /**
     * Upload an artifact to the repository
     * @param file the file to upload
     * @param targetPath the path to upload the file to
     * @throws IOException if the file cannot be uploaded
     */
    public void uploadArtifact(Path file, String targetPath) throws IOException {
        UploadableArtifact artifact =
                artifactory.repository(this.config.repository).upload(Utils.urlEncodeParts(targetPath), file.toFile());
        artifact.withSize(Files.size(file));
        artifact.withListener(
                (bytesRead, totalBytes) -> LOGGER.trace(String.format("Uploaded %d/%d", bytesRead, totalBytes)));
        artifact.doUpload();
        LOGGER.trace(String.format("Uploaded %s to %s", file, targetPath));
    }

    /**
     * Delete an artifact or path from the repository
     * @param targetPath the path of the artifact to delete
     */
    public void deleteArtifact(String targetPath) {
        artifactory.repository(this.config.repository).delete(Utils.urlEncodeParts(targetPath));
    }

    /**
     * Move an artifact from one path to another. Require Artifactory PRO
     * @param sourcePath the source path
     * @param targetPath the target path
     */
    public void move(String sourcePath, String targetPath) {
        ItemHandle sourceItem = artifactory.repository(this.config.repository).folder(Utils.urlEncodeParts(sourcePath));
        sourceItem.move(this.config.repository, Utils.urlEncodeParts(targetPath));
    }

    /**
     * Copy an artifact from one path to another. Require Artifactory PRO
     * @param sourcePath the source path
     * @param targetPath the target path
     */
    public void copy(String sourcePath, String targetPath) {
        ItemHandle sourceItem = artifactory.repository(this.config.repository).folder(Utils.urlEncodeParts(sourcePath));
        sourceItem.copy(this.config.repository, Utils.urlEncodeParts(targetPath));
    }

    /**
     * Download an artifact from the repository
     * @param targetPath the path of the artifact to download
     * @return a pair of the closable client and the input stream of the artifact
     * @throws IOException if the artifact cannot be downloaded
     */
    public InputStream downloadArtifact(String targetPath) throws IOException {
        DownloadableArtifact artifact =
                artifactory.repository(this.config.repository).download(Utils.urlEncodeParts(targetPath));
        return artifact.doDownload();
    }

    /**
     * Check if a path is a folder
     * @param targetPath the path to check
     * @return true if the path is a folder, false otherwise
     * @throws IOException if the path cannot be checked
     */
    public boolean isFolder(String targetPath) throws IOException {
        try {
            return artifactory.repository(this.config.repository).isFolder(Utils.urlEncodeParts(targetPath));
        } catch (Exception e) {
            LOGGER.debug(String.format("Failed to check if %s is a folder", targetPath));
            return false;
        }
    }

    /**
     * List the files in a folder
     * Uses Artifactory Storage API
     * @param targetPath the path to list
     * @return the list of files in the folder
     * @throws IOException if the files cannot be listed
     */
    public List<FileInfo> list(String targetPath) throws IOException {
        if (!isFolder(targetPath)) {
            LOGGER.debug(String.format("Target path %s is not a folder. Cannot list files", targetPath));
            return List.of();
        }

        try {
            ItemHandle folderHandle =
                    artifactory.repository(this.config.repository).folder(Utils.urlEncodeParts(targetPath));
            Folder folder = folderHandle.info();
            List<Item> children = folder.getChildren();
            return children.stream()
                    .map(item -> {
                        try {
                            String childPath = targetPath.endsWith("/")
                                    ? targetPath + item.getUri().substring(1)
                                    : targetPath + item.getUri();

                            long size = 0;
                            try {
                                size = size(childPath);
                            } catch (Exception e) {
                                LOGGER.warn(String.format("Failed to get size of %s", childPath), e);
                            }

                            long lastModified = 0;
                            try {
                                lastModified = lastUpdated(childPath);
                            } catch (Exception e) {
                                LOGGER.warn(String.format("Failed to get last updated time of %s", childPath), e);
                            }

                            return new FileInfo(
                                    childPath,
                                    lastModified,
                                    size,
                                    item.isFolder() ? AqlItemType.FOLDER : AqlItemType.FILE);
                        } catch (Exception e) {
                            LOGGER.error(String.format("Error processing item in %s", targetPath), e);
                            return null;
                        }
                    })
                    .filter(fileInfo -> fileInfo != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to list folder contents for %s", targetPath), e);
            return List.of();
        }
    }

    /**
     * Check if a path is a file
     * @param targetPath the path to check
     * @return true if the path is a file, false otherwise
     * @throws IOException if the path cannot be checked
     */
    public boolean isFile(String targetPath) throws IOException {
        if (isFolder(targetPath)) {
            return false;
        }
        try {
            File file = artifactory
                    .repository(this.config.repository)
                    .file(Utils.urlEncodeParts(targetPath))
                    .info();
            return !file.isFolder();
        } catch (Exception e) {
            LOGGER.debug(String.format("Failed to check if %s is a file", targetPath));
            return false;
        }
    }

    /**
     * Get the last updated time of a path
     * @param targetPath the path to check
     * @return the last updated time of the path
     * @throws IOException if the last updated time cannot be checked
     */
    public long lastUpdated(String targetPath) throws IOException {
        LOGGER.trace(String.format("Getting last updated time for %s", targetPath));
        return artifactory
                .repository(this.config.repository)
                .file(Utils.urlEncodeParts(targetPath))
                .info()
                .getLastModified()
                .getTime();
    }

    /**
     * Get the size of a path
     * @param targetPath the path to check
     * @return the size of the path
     * @throws IOException if the size cannot be checked
     */
    public long size(String targetPath) throws IOException {
        if (isFolder(targetPath)) {
            return 0;
        }
        LOGGER.trace(String.format("Getting size for %s", targetPath));
        File file = artifactory
                .repository(this.config.repository)
                .file(Utils.urlEncodeParts(targetPath))
                .info();
        return file.getSize();
    }

    /**
     * Return a new ArtifactoryConfig object for this client
     * @return the ArtifactoryConfig object
     */
    public ArtifactoryConfig buildArtifactoryConfig() {
        return new ArtifactoryConfig(this.config.serverUrl, this.config.repository, this.config.credentials);
    }

    /**
     * Build the Artifactory client
     * @return the Artifactory client
     */
    private Artifactory buildArtifactory() {
        return ArtifactoryClientBuilder.create()
                .setUrl(config.serverUrl)
                .setUsername(config.credentials.getUsername())
                .setPassword(config.credentials.getPassword().getPlainText())
                .addInterceptorLast((request, httpContext) -> {
                    LOGGER.debug(String.format("Sending Artifactory request to %s", request.getRequestLine()));
                })
                .build();
    }

    @Override
    public void close() throws Exception {
        artifactory.close();
    }

    public static final class FileInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String path;
        private final long lastUpdated;
        private final long size;
        private final AqlItemType type;

        public FileInfo(String path, long lastUpdated, long size, AqlItemType type) {
            this.path = path;
            this.lastUpdated = lastUpdated;
            this.size = size;
            this.type = type;
        }

        public String getPath() {
            return path;
        }

        public long getLastUpdated() {
            return lastUpdated;
        }

        public long getSize() {
            return size;
        }

        public boolean isDirectory() {
            return type.equals(AqlItemType.FOLDER);
        }

        public boolean isFile() {
            return type.equals(AqlItemType.FILE);
        }
    }

    public static final class ArtifactoryConfig implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String serverUrl;
        private final String repository;
        private final UsernamePasswordCredentials credentials;
        private final int maxUploadRetries;
        private final int retryDelaySeconds;

        public ArtifactoryConfig(String serverUrl, String repository, UsernamePasswordCredentials credentials) {
            this(
                    serverUrl,
                    repository,
                    credentials,
                    ArtifactoryGenericArtifactConfig.DEFAULT_MAX_UPLOAD_RETRIES,
                    ArtifactoryGenericArtifactConfig.DEFAULT_RETRY_DELAY_SECONDS);
        }

        public ArtifactoryConfig(
                String serverUrl,
                String repository,
                UsernamePasswordCredentials credentials,
                int maxUploadRetries,
                int retryDelaySeconds) {
            this.serverUrl = serverUrl;
            this.repository = repository;
            this.credentials = CredentialsProvider.snapshot(UsernamePasswordCredentials.class, credentials);
            this.maxUploadRetries = maxUploadRetries;
            this.retryDelaySeconds = retryDelaySeconds;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public String getRepository() {
            return repository;
        }

        public UsernamePasswordCredentials getCredentials() {
            return credentials;
        }

        public int getMaxUploadRetries() {
            return maxUploadRetries;
        }

        public int getRetryDelaySeconds() {
            return retryDelaySeconds;
        }
    }
}
