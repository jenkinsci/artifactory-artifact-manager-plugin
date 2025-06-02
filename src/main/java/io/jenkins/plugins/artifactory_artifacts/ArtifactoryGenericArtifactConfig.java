package io.jenkins.plugins.artifactory_artifacts;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class ArtifactoryGenericArtifactConfig extends AbstractDescribableImpl<ArtifactoryGenericArtifactConfig>
        implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String SERVER_URL_REGEXP =
            "^(http://|https://)[a-z0-9][a-z0-9-.]{0,}(?::[0-9]{1,5})?(/[0-9a-zA-Z_]*)*$";
    private static final Pattern endPointPattern = Pattern.compile(SERVER_URL_REGEXP, Pattern.CASE_INSENSITIVE);

    public static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryGenericArtifactConfig.class);

    private String storageCredentialId;
    private String serverUrl;
    private String repository;
    private String prefix;
    private int maxUploadRetries = 3;
    private long retryDelaySeconds = 15;

    @DataBoundConstructor
    public ArtifactoryGenericArtifactConfig() {}

    public ArtifactoryGenericArtifactConfig(
            String storageCredentialId, String serverUrl, String repository, String prefix) {
        this.storageCredentialId = storageCredentialId;
        this.serverUrl = serverUrl;
        this.repository = repository;
        this.prefix = prefix;
        this.maxUploadRetries = 3;
        this.retryDelaySeconds = 15;
    }

    public ArtifactoryGenericArtifactConfig(
            String storageCredentialId, String serverUrl, String repository, String prefix, 
            int maxUploadRetries, int retryDelaySeconds) {
        this.storageCredentialId = storageCredentialId;
        this.serverUrl = serverUrl;
        this.repository = repository;
        this.prefix = prefix;
        this.maxUploadRetries = maxUploadRetries;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public String getStorageCredentialId() {
        return storageCredentialId;
    }

    @DataBoundSetter
    public void setStorageCredentialId(String storageCredentialId) {
        this.storageCredentialId = storageCredentialId;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getRepository() {
        return repository;
    }

    @DataBoundSetter
    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getMaxUploadRetries() {
        return maxUploadRetries;
    }

    @DataBoundSetter
    public void setMaxUploadRetries(int maxUploadRetries) {
        this.maxUploadRetries = Math.max(1, maxUploadRetries); // Minimum 1 retry
    }

    public long getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    @DataBoundSetter
    public void setRetryDelaySeconds(long retryDelaySeconds) {
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds); // Minimum 1 second
    }

    public static ArtifactoryGenericArtifactConfig get() {
        return ExtensionList.lookupSingleton(ArtifactoryGenericArtifactConfig.class);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ArtifactoryGenericArtifactConfig> {

        public DescriptorImpl() {
            load();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Generic";
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            save();
            return super.configure(req, json);
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillStorageCredentialIdItems(@AncestorInPath Item item) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(get().getStorageCredentialId());
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(get().getStorageCredentialId());
                }
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernameCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StandardUsernameCredentials.class))
                    .includeCurrentValue(get().getStorageCredentialId());
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckPrefix(@QueryParameter String prefix) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            FormValidation ret;
            if (StringUtils.isBlank(prefix)) {
                ret = FormValidation.ok("Artifacts will be stored in the root folder of the Artifactory Repository.");
            } else if (prefix.endsWith("/")) {
                ret = FormValidation.ok();
            } else {
                ret = FormValidation.error("A prefix must end with a slash.");
            }
            return ret;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckRepository(@QueryParameter String repository) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            FormValidation ret = FormValidation.ok();
            if (StringUtils.isBlank(repository)) {
                ret = FormValidation.error("Repository cannot be blank");
            }
            //
            return ret;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            FormValidation ret = FormValidation.ok();
            if (StringUtils.isBlank(serverUrl)) {
                ret = FormValidation.error("Server url cannot be blank");
            } else if (!endPointPattern.matcher(serverUrl).matches()) {
                ret = FormValidation.error("Server url doesn't seem valid. Should start with http:// or https://");
            }
            return ret;
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckMaxUploadRetries(@QueryParameter int maxUploadRetries) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (maxUploadRetries < 1) {
                return FormValidation.error("Max retries must be at least 1");
            }
            if (maxUploadRetries > 10) {
                return FormValidation.warning("Consider using a lower number of retries to avoid long delays");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckRetryDelaySeconds(@QueryParameter long retryDelaySeconds) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (retryDelaySeconds < 1) {
                return FormValidation.error("Retry delay must be at least 1 second");
            }
            if (retryDelaySeconds > 300) {
                return FormValidation.warning("Consider using a shorter delay to avoid very long waits");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doValidateArtifactoryConfig(
                @QueryParameter("serverUrl") final String serverUrl,
                @QueryParameter("storageCredentialId") final String storageCredentialId,
                @QueryParameter("repository") final String repository,
                @QueryParameter("prefix") final String prefix) {

            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (StringUtils.isBlank(serverUrl)
                    || StringUtils.isBlank(storageCredentialId)
                    || StringUtils.isBlank(repository)) {
                return FormValidation.error("Fields required");
            }

            try {
                Path tmpFile = Files.createTempFile("tmp-", "jenkins-artifactory-plugin-test");
                ArtifactoryClient client =
                        new ArtifactoryClient(serverUrl, repository, Utils.getCredentials(storageCredentialId));

                // Upload and delete artifact to check connectivity
                client.uploadArtifact(tmpFile, Utils.getPath(prefix, tmpFile));
                client.deleteArtifact(Utils.getPath(prefix, tmpFile));

                LOGGER.debug("Artifactory configuration validated");

            } catch (Exception e) {
                LOGGER.error("Unable to connect to Artifactory. Please check the server url and credentials", e);
                return FormValidation.error(
                        "Unable to connect to Artifactory. Please check the server url and credentials : "
                                + e.getMessage());
            }

            return FormValidation.ok("Success");
        }
    }
}
