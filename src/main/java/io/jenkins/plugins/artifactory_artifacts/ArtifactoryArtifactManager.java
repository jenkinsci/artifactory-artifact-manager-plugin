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
            LOGGER.trace(String.format("Archiving key %s with value %s into %s", entry.getKey(), entry.getValue(), filePath));
            files.add(new UploadFile(entry.getKey(), filePath));
        }

        workspace.act(new UploadToArtifactoryStorage(buildArtifactoryClient(), files));
    }

    @Override
    public boolean delete() throws IOException, InterruptedException {
        String virtualPath = getFilePath("");
        ArtifactoryClient client = buildArtifactoryClient();
        LOGGER.trace(String.format("Deleting %s...", virtualPath));
        try {
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
                buildArtifactoryClient(),
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
        workspace.act(new Unstash(buildArtifactoryClient(), path, listener));
    }

    @Override
    public void clearAllStashes(@NonNull TaskListener listener) throws IOException, InterruptedException {
        String virtualPath = getFilePath("stashes");
        ArtifactoryClient client = buildArtifactoryClient();
        LOGGER.trace(String.format("Deleting %s...", virtualPath));
        try {
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
        try {
            String stashedPath = getFilePath("stashes");
            String artifactPath = getFilePath("artifacts");
            String toStashedPath = artifactoryArtifactManager.getFilePath("stashes");
            String toArtifactPath = artifactoryArtifactManager.getFilePath("artifacts");
            ArtifactoryClient client = buildArtifactoryClient();
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
        private final ArtifactoryClient client;
        private final String path, includes, excludes;
        private final boolean useDefaultExcludes;
        private final boolean allowEmpty;
        private final String tempDir;
        private final TaskListener listener;

        public Stash(
                ArtifactoryClient client,
                String path,
                String includes,
                String excludes,
                boolean useDefaultExcludes,
                boolean allowEmpty,
                String tempDir,
                TaskListener listener)
                throws IOException {
            this.client = client;
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
                client.uploadArtifact(tmp, path);
                listener.getLogger().printf("Stashed %d file(s) to %s%n", count, path);
                return null;
            } finally {
                listener.getLogger().flush();
                Files.delete(tmp);
            }
        }
    }

    /**
     * Master to slave callable that unstashes files from Artifactory storage.
     */
    private static final class Unstash extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private ArtifactoryClient client;
        private final String path;
        private final TaskListener listener;

        public Unstash(ArtifactoryClient client, String path, TaskListener listener) throws IOException {
            this.client = client;
            this.path = path;
            this.listener = listener;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            try (InputStream is = client.downloadArtifact(path)) {
                new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
            } catch (Exception e) {
                LOGGER.error("Unable to unstash file", e);
                listener.getLogger().println(String.format("Unable to unstash file: %s", e.getMessage()));
                return null;
            } finally {
                listener.getLogger().flush();
            }
            return null;
        }
    }

    /**
     * Master to slave callable that uploads files to Artifactory storage.
     */
    private static class UploadToArtifactoryStorage extends MasterToSlaveFileCallable<Void> {

        private final List<UploadFile> files;
        private final ArtifactoryClient client;

        public UploadToArtifactoryStorage(ArtifactoryClient client, List<UploadFile> files) {
            this.client = client;
            this.files = files;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            for (UploadFile file : files) {
                LOGGER.debug(String.format("Uploading %s to %s", file.getName(), file.getUrl()));
                client.uploadArtifact(new File(f, file.getName()).toPath(), file.getUrl());
            }
            return null;
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
            ArtifactoryClient client =
                    new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
            String path = Utils.stripTrailingSlash(Utils.getFilePath(item.getFullName(), ""));
            LOGGER.debug(String.format("Checking if %s must be deleted on Artifactory Storage", path));
            try {
                if (client.isFolder(path)) {
                    LOGGER.debug(String.format("Deleting %s...", path));
                    client.deleteArtifact(path);
                    LOGGER.debug(String.format("Deleted %s on Artifactory Storage", path));
                }
            } catch (IOException e) {
                LOGGER.error(String.format("Failed to delete %s", path), e);
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
            ArtifactoryClient client =
                    new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
            String sourcePath = Utils.stripTrailingSlash(Utils.getFilePath(oldFullName, ""));
            String targetPath = Utils.stripTrailingSlash(Utils.getFilePath(newFullName, ""));
            LOGGER.debug(
                    String.format("Checking if %s must be moved to %s on Artifactory Storage", sourcePath, targetPath));
            try {
                if (client.isFolder(sourcePath)) {
                    LOGGER.debug(String.format("Moving %s...", sourcePath));
                    client.move(sourcePath, targetPath);
                    LOGGER.debug(String.format("Moving %s on Artifactory Storage", targetPath));

                    // TODO: We move all artifact but previous build artifacts still reference old name
                    // We should update the references to the new name ?

                }
            } catch (IOException e) {
                LOGGER.error(
                        String.format("Failed to move %s to %s. Artifactory Pro is needed", sourcePath, targetPath));
            }
        }
    }
}
