package io.jenkins.plugins.artifactory_artifacts;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Restricted(NoExternalUse.class)
public class ArtifactoryArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryArtifactManager.class);
    private static final int UPLOAD_THREADS = 4;
    private transient Run<?, ?> build;
    private final ArtifactoryGenericArtifactConfig config;
    private transient String defaultKey;

    public ArtifactoryArtifactManager(Run<?, ?> build, ArtifactoryGenericArtifactConfig config) {
        this.config = config;
        this.build = build;
        onLoad(build);
    }

    @Override
    public void onLoad(@NonNull Run<?, ?> build) {
        this.build = build;
        this.defaultKey = String.format("%s/%s", build.getParent().getFullName(), build.getNumber())
                .replace("%2F", "/");
    }

    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            return;
        }
        List<UploadFile> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : artifacts.entrySet()) {
            String path = "artifacts/" + entry.getKey();
            String filePath = getFilePath(path);
            files.add(new UploadFile(entry.getValue(), filePath));
        }

        workspace.act(new UploadToArtifactoryStorage(buildArtifactoryConfig(), files));
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String virtualPath = getFilePath("");
        LOGGER.trace(String.format("Deleting %s...", virtualPath));
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            if (client.isFile(virtualPath) || client.isFolder(virtualPath)) {
                client.deleteArtifact(virtualPath);
            } else {
                LOGGER.debug(String.format("No file or folder found at %s", virtualPath));
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Failed to delete %s", virtualPath), e);
            return false;
        }
        LOGGER.trace(String.format("Deleted %s", virtualPath));
        return true;
    }

    @Override
    public VirtualFile root() {
        return new ArtifactoryVirtualFile(getFilePath("artifacts"), build);
    }

    @Override
    public void stash(
            @NonNull String name,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            String includes,
            String excludes,
            boolean useDefaultExcludes,
            boolean allowEmpty)
            throws IOException, InterruptedException {
        String path = getFilePath("stashes/" + name + ".tgz");
        FilePath tempDir = WorkspaceList.tempDir(workspace);
        if (tempDir == null) {
            throw new AbortException("Could not make temporary directory in " + workspace);
        }
        workspace.act(new Stash(
                buildArtifactoryConfig(),
                path,
                includes,
                excludes,
                useDefaultExcludes,
                allowEmpty,
                tempDir.getRemote(),
                listener));
    }

    @Override
    public void unstash(
            @NonNull String name,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        String path = getFilePath("stashes/" + name + ".tgz");
        FilePath tempDir = WorkspaceList.tempDir(workspace);
        if (tempDir == null) {
            throw new AbortException("Could not make temporary directory in " + workspace);
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            if (!client.isFile(path)) {
                throw new AbortException(String.format("No such saved stash ‘%s’ found at %s", name, path));
            }
        } catch (Exception e) {
            throw new AbortException(String.format("Failed to stash %s from %s", name, path));
        }
        workspace.act(new Unstash(buildArtifactoryConfig(), path, listener));
    }

    @Override
    public void clearAllStashes(@NonNull TaskListener listener) throws IOException, InterruptedException {
        String virtualPath = getFilePath("stashes");
        LOGGER.trace(String.format("Deleting %s...", virtualPath));
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            if (client.isFolder(virtualPath)) {
                client.deleteArtifact(virtualPath);
                listener.getLogger().println("Deleted all stashes on Artifactory Storage");
                LOGGER.debug(String.format("Deleted stash %s", virtualPath));
            }
        } catch (Exception e) {
            listener.getLogger()
                    .printf("Failed to delete stashes on Artifactory Storage. Details %s%n", e.getMessage());
            LOGGER.error(String.format("Failed to delete stash on Artifactory at %s", virtualPath), e);
        }
    }

    @Override
    public void copyAllArtifactsAndStashes(@NonNull Run<?, ?> to, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        LOGGER.debug(String.format("Copy all artifacts and stash to %s...", to));
        ArtifactManager artifactManager = to.pickArtifactManager();
        if (!(artifactManager instanceof ArtifactoryArtifactManager)) {
            throw new AbortException(
                    String.format("Cannot copy artifacts and stashes to %s using %s", to, artifactManager.getClass()));
        }
        ArtifactoryArtifactManager artifactoryArtifactManager = (ArtifactoryArtifactManager) artifactManager;
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            String stashedPath = getFilePath("stashes");
            String artifactPath = getFilePath("artifacts");
            String toStashedPath = artifactoryArtifactManager.getFilePath("stashes");
            String toArtifactPath = artifactoryArtifactManager.getFilePath("artifacts");
            if (client.isFolder(artifactPath)) {
                LOGGER.debug(String.format("Copying artifacts from %s to %s", artifactPath, toArtifactPath));
                listener.getLogger()
                        .println(String.format("Copying artifacts from %s to %s", artifactPath, toArtifactPath));
                client.copy(artifactPath, toArtifactPath);
            }
            if (client.isFolder(stashedPath)) {
                listener.getLogger()
                        .println(String.format("Copying stashes from %s to %s", stashedPath, toStashedPath));
                LOGGER.debug(String.format("Copying stashes from %s to %s", stashedPath, toStashedPath));
                client.copy(stashedPath, toStashedPath);
            }
        } catch (Exception e) {
            listener.getLogger()
                    .printf("Failed to copy artifact and stashes on Artifactory Storage. Details %s%n", e.getMessage());
            throw new IOException(e);
        }
    }

    private String getFilePath(String path) {
        return Utils.getFilePath(defaultKey, path);
    }

    private ArtifactoryClient buildArtifactoryClient() {
        return new ArtifactoryClient(this.config.getServerUrl(), this.config.getRepository(), Utils.getCredentials());
    }

    private ArtifactoryClient.ArtifactoryConfig buildArtifactoryConfig() {
        return new ArtifactoryClient.ArtifactoryConfig(
                this.config.getServerUrl(),
                this.config.getRepository(),
                Utils.getCredentials(),
                this.config.getMaxUploadRetries(),
                this.config.getRetryDelaySeconds());
    }

    private static class UploadFile implements Serializable {
        private final String name;
        private final String url;

        public UploadFile(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }

    /**
     * Master to slave callable that stashes files to Artifactory storage.
     */
    private static final class Stash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final ArtifactoryClient.ArtifactoryConfig config;
        private final String path, includes, excludes;
        private final boolean useDefaultExcludes;
        private final boolean allowEmpty;
        private final String tempDir;
        private final TaskListener listener;

        public Stash(
                ArtifactoryClient.ArtifactoryConfig config,
                String path,
                String includes,
                String excludes,
                boolean useDefaultExcludes,
                boolean allowEmpty,
                String tempDir,
                TaskListener listener)
                throws IOException {
            this.config = config;
            this.path = path;
            this.includes = includes;
            this.excludes = excludes;
            this.useDefaultExcludes = useDefaultExcludes;
            this.allowEmpty = allowEmpty;
            this.tempDir = tempDir;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            Path tempDirP = Paths.get(tempDir);
            Files.createDirectories(tempDirP);
            Path tmp = Files.createTempFile(tempDirP, "stash", ".tgz");
            try {
                int count;
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    count = new FilePath(f)
                            .archive(
                                    ArchiverFactory.TARGZ,
                                    os,
                                    new DirScanner.Glob(
                                            Util.fixEmpty(includes) == null ? "**" : includes,
                                            excludes,
                                            useDefaultExcludes));
                } catch (InvalidPathException e) {
                    throw new IOException(e);
                }
                if (count == 0 && !allowEmpty) {
                    throw new AbortException("No files included in stash");
                }

                // Upload with retry logic
                try {
                    executeWithRetry(
                            () -> {
                                try (ArtifactoryClient client = new ArtifactoryClient(this.config)) {
                                    client.uploadArtifact(tmp, path);
                                }
                            },
                            "Uploading stash to " + path,
                            this.config.getMaxUploadRetries(),
                            this.config.getRetryDelaySeconds() * 1000L, // Convert seconds to milliseconds
                            "Unable to stash files to Artifactory");
                    listener.getLogger().printf("Stashed %d file(s) to %s%n", count, path);
                } catch (RuntimeException e) {
                    String message = this.config.getMaxUploadRetries() == 0
                        ? "Unable to stash files to Artifactory on first attempt (no retries configured)"
                        : "Unable to stash files to Artifactory after " + this.config.getMaxUploadRetries() + " attempts";
                    throw new AbortException(message + ". Details: " + e.getMessage());
                }
            } finally {
                listener.getLogger().flush();
                Files.delete(tmp);
            }
            return null;
        }
    }

    /**
     * Master to slave callable that unstashes files from Artifactory storage.
     */
    private static final class Unstash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private final ArtifactoryClient.ArtifactoryConfig config;
        private final String path;
        private final TaskListener listener;

        public Unstash(ArtifactoryClient.ArtifactoryConfig config, String path, TaskListener listener)
                throws IOException {
            this.config = config;
            this.path = path;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            try (ArtifactoryClient client = new ArtifactoryClient(this.config)) {
                try (InputStream is = client.downloadArtifact(path)) {
                    new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
                } finally {
                    listener.getLogger().flush();
                }
            } catch (Exception e) {
                LOGGER.error("Unable to unstash files from Artifactory", e);
                throw new AbortException("Unable to unstash files from Artifactory. Details: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Master to slave callable that uploads files to Artifactory storage.
     */
    private static class UploadToArtifactoryStorage extends MasterToSlaveFileCallable<Void> {

        private final List<UploadFile> files;
        private final ArtifactoryClient.ArtifactoryConfig config;

        public UploadToArtifactoryStorage(ArtifactoryClient.ArtifactoryConfig config, List<UploadFile> files) {
            this.config = config;
            this.files = files;
        }

        @Override
        public Void invoke(File folder, VirtualChannel channel) throws IOException, InterruptedException {
            try (ArtifactoryClient client = new ArtifactoryClient(this.config)) {
                ExecutorService executor = Executors.newFixedThreadPool(UPLOAD_THREADS);
                try {
                    CompletableFuture<Void> allUploads = CompletableFuture.allOf(files.stream()
                            .map(file -> CompletableFuture.runAsync(
                                    () -> upload(client, folder, file, this.config), executor))
                            .toArray(CompletableFuture[]::new));
                    allUploads.get();
                } finally {
                    executor.shutdown();
                }
            } catch (Exception e) {
                LOGGER.error("Unable to upload files to Artifactory", e);
                throw new AbortException("Unable to upload files to Artifactory. Details: " + e.getMessage());
            }
            return null;
        }

        private void upload(
                ArtifactoryClient client,
                File folder,
                UploadFile uploadFile,
                ArtifactoryClient.ArtifactoryConfig config) {
            File sourceFile = new File(folder, uploadFile.getName());
            String filePath = sourceFile.toPath().toString();
            String targetUrl = uploadFile.getUrl();

            try {
                executeWithRetry(
                        () -> client.uploadArtifact(sourceFile.toPath(), targetUrl),
                        "Uploading " + filePath + " to " + targetUrl,
                        config.getMaxUploadRetries(),
                        config.getRetryDelaySeconds() * 1000L, // Convert seconds to milliseconds
                        "Failed to upload " + filePath);
                LOGGER.debug(String.format("Successfully uploaded %s to %s", filePath, targetUrl));
            } catch (RuntimeException e) {
                // Re-throw as RuntimeException to be handled by the CompletableFuture
                throw e;
            }
        }
    }

    /**
     * Item listener that listens to item deletion and location change events and updates the storage accordingly
     */
    @Extension
    public static final class ArtifactoryItemListener extends ItemListener {

        @Override
        public void onDeleted(Item item) {
            ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();

            // Not configured
            if (config == null) {
                return;
            }

            String path = Utils.stripTrailingSlash(Utils.getFilePath(item.getFullName(), ""));
            LOGGER.debug(String.format("Checking if %s must be deleted on Artifactory Storage", path));
            try (ArtifactoryClient client =
                    new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials())) {
                if (client.isFolder(path)) {
                    LOGGER.debug(String.format("Deleting %s...", path));
                    client.deleteArtifact(path);
                    LOGGER.debug(String.format("Deleted %s on Artifactory Storage", path));
                }
            } catch (Exception e) {
                LOGGER.error(String.format("Failed to delete %s", path), e);
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
            String sourcePath = Utils.stripTrailingSlash(Utils.getFilePath(oldFullName, ""));
            String targetPath = Utils.stripTrailingSlash(Utils.getFilePath(newFullName, ""));
            LOGGER.debug(
                    String.format("Checking if %s must be moved to %s on Artifactory Storage", sourcePath, targetPath));
            try (ArtifactoryClient client =
                    new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials())) {
                if (client.isFolder(sourcePath)) {
                    LOGGER.debug(String.format("Moving %s...", sourcePath));
                    client.move(sourcePath, targetPath);
                    LOGGER.debug(String.format("Moving %s on Artifactory Storage", targetPath));

                    // TODO: We move all artifact but previous build artifacts still reference old name
                    // We should update the references to the new name ?

                }
            } catch (Exception e) {
                LOGGER.error(
                        String.format("Failed to move %s to %s. Artifactory Pro is needed", sourcePath, targetPath));
            }
        }
    }

    /**
     * Utility interface for operations that can be retried
     */
    @FunctionalInterface
    private interface RetryableOperation {
        void execute() throws Exception;
    }

    /**
     * Executes an operation with retry logic
     *
     * @param operation the operation to retry
     * @param operationName descriptive name for logging
     * @param maxRetries maximum number of retry attempts
     * @param retryDelayMs delay between retries in milliseconds
     * @param failureMessage message to use when all retries are exhausted
     * @throws RuntimeException when all retries are exhausted or interrupted
     */
    private static void executeWithRetry(
            RetryableOperation operation,
            String operationName,
            int maxRetries,
            long retryDelayMs,
            String failureMessage)
            throws RuntimeException {

        // When maxRetries is 0, try once and fail immediately if there's an error
        int maxAttempts = Math.max(1, maxRetries);
        
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                LOGGER.debug(String.format("%s (attempt %d/%d)", operationName, attempt + 1, maxAttempts));
                operation.execute();
                return; // Success, exit retry loop
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxAttempts) {
                    String message = maxRetries == 0 
                        ? String.format("%s on first attempt (no retries configured)", failureMessage)
                        : String.format("%s after %d attempts", failureMessage, maxRetries);
                    LOGGER.error(message, e);
                    throw new RuntimeException(
                            String.format("%s: %s", message, e.getMessage()), e);
                } else {
                    LOGGER.warn(String.format(
                            "%s attempt %d failed, retrying in %dms: %s",
                            operationName, attempt, retryDelayMs, e.getMessage()));
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(operationName + " interrupted during retry delay", ie);
                    }
                }
            }
        }
    }
}
