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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.util.VirtualFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactoryVirtualFile extends ArtifactoryAbstractVirtualFile {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryVirtualFile.class);

    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String key;

    private final transient Run<?, ?> build;

    public ArtifactoryVirtualFile(String key, Run<?, ?> build) {
        this.key = key;
        this.build = build;
    }

    public String getKey() {
        return key;
    }

    @NonNull
    @Override
    public String getName() {
        String localKey = Utils.stripTrailingSlash(key);
        String name = localKey.replaceFirst(".*/artifacts/", "");
        LOGGER.trace(String.format("Returning name %s for file %s", name, localKey));
        return name;
    }

    @NonNull
    @Override
    public URI toURI() {
        try {
            URI uri = new URI(Utils.getUrl(this.key));
            LOGGER.trace(String.format("Returning URI %s for file %s", uri, this.key));
            return uri;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        URL url = new URL(Utils.getUrl(this.key));
        LOGGER.trace(String.format("Returning URL %s for file %s", url, this.key));
        return url;
    }

    @Override
    public VirtualFile getParent() {
        LOGGER.trace(String.format("Getting parent of %s", this.key));
        ArtifactoryVirtualFile file = new ArtifactoryVirtualFile(this.key.replaceFirst("/[^/]+$", ""), this.build);
        LOGGER.trace(String.format("Parent of %s is %s", this.key, file.getKey()));
        return file;
    }

    @Override
    public boolean isDirectory() throws IOException {
        LOGGER.trace(String.format("Checking if %s is a directory", this.key));
        String keyWithNoSlash = Utils.stripTrailingSlash(this.key);
        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }
        return buildArtifactoryClient().isFolder(this.key);
    }

    @Override
    public boolean isFile() throws IOException {
        LOGGER.trace(String.format("Checking if %s is a file", this.key));
        String keyS = this.key + "/";
        if (keyS.endsWith("/*view*/")) {
            return false;
        }
        return buildArtifactoryClient().isFile(this.key);
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
        return buildArtifactoryClient().size(this.key);
    }

    @Override
    public long lastModified() throws IOException {
        return buildArtifactoryClient().lastUpdated(this.key);
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
        ArtifactoryClient client = buildArtifactoryClient();
        return client.downloadArtifact(this.key);
    }

    private ArtifactoryClient buildArtifactoryClient() {
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        return new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
    }

    /**
     * List the files from a prefix
     * @param prefix the prefix
     * @return the list of files from the prefix
     */
    private List<VirtualFile> listFilesFromPrefix(String prefix) {
        ArtifactoryClient client = buildArtifactoryClient();
        try {
            List<String> files = client.list(prefix);
            List<VirtualFile> virtualFiles = new ArrayList<>();
            for (String file : files) {
                String key = Utils.stripTrailingSlash(file);
                LOGGER.trace(String.format("Adding virtual file with key %s", key));
                virtualFiles.add(new ArtifactoryVirtualFile(key, this.build));
            }
            return virtualFiles;
        } catch (IOException e) {
            LOGGER.warn(String.format("Failed to list files from prefix %s", prefix), e);
            return Collections.emptyList();
        }
    }
}
