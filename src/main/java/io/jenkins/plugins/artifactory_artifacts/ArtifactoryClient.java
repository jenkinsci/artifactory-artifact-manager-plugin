package io.jenkins.plugins.artifactory_artifacts;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.jfrog.artifactory.client.*;
import org.jfrog.artifactory.client.model.File;
import org.jfrog.filespecs.FileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ArtifactoryClient implements Serializable {

    public static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryClient.class);

    private final String serverUrl;
    private final String repository;
    private final UsernamePasswordCredentials credentials;

    public ArtifactoryClient(String serverUrl, String repository, UsernamePasswordCredentials credentials) {
        this.serverUrl = serverUrl;
        this.repository = repository;
        this.credentials = credentials;
    }

    /**
     * Upload an artifact to the repository
     * @param file the file to upload
     * @param targetPath the path to upload the file to
     * @throws IOException if the file cannot be uploaded
     */
    public void uploadArtifact(Path file, String targetPath) throws IOException {
        try (Artifactory artifactory = buildArtifactory()) {
            UploadableArtifact artifact =
                    artifactory.repository(this.repository).upload(targetPath, file.toFile());
            artifact.withSize(Files.size(file));
            artifact.withListener(
                    (bytesRead, totalBytes) -> LOGGER.trace(String.format("Uploaded %d/%d", bytesRead, totalBytes)));
            artifact.doUpload();
            LOGGER.trace(String.format("Uploaded %s to %s", file, targetPath));
        }
    }

    /**
     * Delete an artifact or path from the repository
     * @param targetPath the path of the artifact to delete
     */
    public void deleteArtifact(String targetPath) {
        try (Artifactory artifactory = buildArtifactory()) {
            artifactory.repository(this.repository).delete(targetPath);
        }
    }

    /**
     * Move an artifact from one path to another. Require Artifactory PRO
     * @param sourcePath the source path
     * @param targetPath the target path
     */
    public void move(String sourcePath, String targetPath) {
        try (Artifactory artifactory = buildArtifactory()) {
            ItemHandle sourceItem = artifactory.repository(this.repository).folder(sourcePath);
            sourceItem.move(this.repository, targetPath);
        }
    }

    /**
     * Copy an artifact from one path to another. Require Artifactory PRO
     * @param sourcePath the source path
     * @param targetPath the target path
     */
    public void copy(String sourcePath, String targetPath) {
        try (Artifactory artifactory = buildArtifactory()) {
            ItemHandle sourceItem = artifactory.repository(this.repository).folder(sourcePath);
            sourceItem.copy(this.repository, targetPath);
        }
    }

    /**
     * Download an artifact from the repository
     * @param targetPath the path of the artifact to download
     * @return the input stream of the artifact
     * @throws IOException if the artifact cannot be downloaded
     */
    public InputStream downloadArtifact(String targetPath) throws IOException {
        try (Artifactory artifactory = buildArtifactory()) {
            DownloadableArtifact artifact =
                    artifactory.repository(this.repository).download(targetPath);
            return artifact.doDownload();
        }
    }

    /**
     * Check if a path is a folder
     * @param targetPath the path to check
     * @return true if the path is a folder, false otherwise
     * @throws IOException if the path cannot be checked
     */
    public boolean isFolder(String targetPath) throws IOException {
        LOGGER.trace(String.format("Checking if %s is a folder", targetPath));
        try (Artifactory artifactory = buildArtifactory()) {
            try {
                return artifactory.repository(this.repository).isFolder(targetPath);
            } catch (Exception e) {
                LOGGER.debug(String.format("Failed to check if %s is a folder", targetPath));
                return false;
            }
        }
    }

    /**
     * List the files in a folder
     * @param targetPath the path to list
     * @return the list of files in the folder
     * @throws IOException if the files cannot be listed
     */
    public List<String> list(String targetPath) throws IOException {
        if (!isFolder(targetPath)) {
            LOGGER.debug(String.format("Target path %s is not a folder. Cannot list files", targetPath));
            return List.of();
        }
        try (Artifactory artifactory = buildArtifactory()) {
            FileSpec fileSpec = FileSpec.fromString(
                    String.format("{\"files\": [{\"pattern\": \"%s/%s*\"}]}", this.repository, targetPath));
            return artifactory.searches().artifactsByFileSpec(fileSpec).stream()
                    .map((item -> String.format("%s/%s", item.getPath(), item.getName())))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Check if a path is a file
     * @param targetPath the path to check
     * @return true if the path is a file, false otherwise
     * @throws IOException if the path cannot be checked
     */
    public boolean isFile(String targetPath) throws IOException {
        return !isFolder(targetPath);
    }

    /**
     * Get the last updated time of a path
     * @param targetPath the path to check
     * @return the last updated time of the path
     * @throws IOException if the last updated time cannot be checked
     */
    public long lastUpdated(String targetPath) throws IOException {
        LOGGER.trace(String.format("Getting last updated time for %s", targetPath));
        try (Artifactory artifactory = buildArtifactory()) {
            return artifactory
                    .repository(this.repository)
                    .file(targetPath)
                    .info()
                    .getLastModified()
                    .getTime();
        }
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
        try (Artifactory artifactory = buildArtifactory()) {
            File file = artifactory.repository(this.repository).file(targetPath).info();
            return file.getSize();
        }
    }

    /**
     * Build the Artifactory client
     * @return the Artifactory client
     */
    private Artifactory buildArtifactory() {
        return ArtifactoryClientBuilder.create()
                .setUrl(this.serverUrl)
                .setUsername(credentials.getUsername())
                .setPassword(credentials.getPassword().getPlainText())
                .addInterceptorLast((request, httpContext) -> {
                    LOGGER.debug(String.format("Sending Artifactory request to %s", request.getRequestLine()));
                })
                .build();
    }
}
