package io.jenkins.plugins.artifactory_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

@Restricted(NoExternalUse.class)
public class ArtifactoryArtifactManagerFactory extends ArtifactManagerFactory {

    private final ArtifactoryGenericArtifactConfig config;

    @DataBoundConstructor
    public ArtifactoryArtifactManagerFactory(ArtifactoryGenericArtifactConfig config) {
        if (config == null) {
            throw new IllegalArgumentException();
        }
        this.config = config;
    }

    @CheckForNull
    @Override
    public ArtifactManager managerFor(Run<?, ?> build) {
        return new ArtifactoryArtifactManager(build, Utils.getArtifactConfig());
    }

    public ArtifactoryGenericArtifactConfig getConfig() {
        return config;
    }

    @Extension
    @Symbol("artifactory")
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Artifactory Artifact Storage";
        }
    }
}
