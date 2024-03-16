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
        String localKey = stripTrailingSlash(key);
        return localKey.replaceFirst(".+/", "");
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
        return super.toExternalURL();
    }

    @Override
    public VirtualFile getParent() {
        return new ArtifactoryVirtualFile(this.key.replaceFirst("/[^/]+$", ""), this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyWithNoSlash = stripTrailingSlash(this.key);
        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }
        return new ArtifactoryClient().isFolder(this.key);
    }

    @Override
    public boolean isFile() throws IOException {
        String keyS = this.key + "/";
        if (keyS.endsWith("/*view*/")) {
            return false;
        }
        return new ArtifactoryClient().isFile(this.key);
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() throws IOException {
        String prefix = stripTrailingSlash(this.key) + "/";
        return listFilesFromPrefix(prefix).toArray(new VirtualFile[0]);
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String joinedKey = stripTrailingSlash(this.key) + "/" + name;
        return new ArtifactoryVirtualFile(joinedKey, build);
    }

    @Override
    public long length() throws IOException {
        return new ArtifactoryClient().size(this.key);
    }

    @Override
    public long lastModified() throws IOException {
        return new ArtifactoryClient().lastUpdated(this.key);
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.info(String.format("Opening %s...", this.key));
        if (isDirectory()) {
            throw new FileNotFoundException("Cannot open it because it is a directory.");
        }
        if (!isFile()) {
            throw new FileNotFoundException("Cannot open it because it is not a file.");
        }
        ArtifactoryClient client = new ArtifactoryClient();
        return client.downloadArtifact(this.key);
    }

    private List<VirtualFile> listFilesFromPrefix(String prefix) {
        ArtifactoryClient client = new ArtifactoryClient();
        try {
            List<String> files = client.list(prefix);
            List<VirtualFile> virtualFiles = new ArrayList<>();
            for (String file : files) {
                virtualFiles.add(new ArtifactoryVirtualFile(stripTrailingSlash(file), this.build));
            }
            return virtualFiles;
        } catch (IOException e) {
            LOGGER.warn(String.format("Failed to list files from prefix %s", prefix), e);
            return Collections.emptyList();
        }
    }

    private String stripTrailingSlash(String key) {
        String localKey = key;
        if (key.endsWith("/")) {
            localKey = localKey.substring(0, localKey.length() - 1);
        }
        return localKey;
    }
}
