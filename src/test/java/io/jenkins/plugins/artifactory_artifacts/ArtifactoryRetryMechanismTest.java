package io.jenkins.plugins.artifactory_artifacts;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.Objects;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the retry mechanism implementation in ArtifactoryArtifactManager.
 * These tests validate that uploads retry on failure and eventually succeed or fail appropriately.
 */
@WithJenkins
@WireMockTest
public class ArtifactoryRetryMechanismTest extends BaseTest {

    @Test
    public void shouldRetryArtifactUploadOnTransientFailure(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        String pipelineName = "shouldRetryArtifactUploadOnTransientFailure";

        // Create pipeline that archives a simple file
        String pipeline =
                """
            node {
                writeFile file: 'artifact.txt', text: 'Hello World'
                archiveArtifacts artifacts: 'artifact.txt'
            }
            """;

        // Setup WireMock to fail on first attempt, succeed on 2nd
        WireMock wireMock = wmRuntimeInfo.getWireMock();

        // Scenario to simulate transient failures
        String scenarioName = "retry-scenario";

        // First attempt fails with 500
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.serverError())
                .willSetStateTo("second-attempt"));

        // Second attempt succeeds
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*"))
                .inScenario(scenarioName)
                .whenScenarioStateIs("second-attempt")
                .willReturn(WireMock.okJson(("{}"))));

        // Setup other required stubs (excluding PUT which we handle above)
        setupOtherWireMockStubs(pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should succeed after retries
        assertThat(run.getResult(), equalTo(hudson.model.Result.SUCCESS));

        // Verify exactly 2 requests were made
        wireMock.verifyThat(2, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*")));
    }

    @Test
    public void shouldFailArtifactUploadAfterMaxRetries(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        String pipelineName = "shouldFailArtifactUploadAfterMaxRetries";

        // Create pipeline that archives a simple file
        String pipeline =
                """
            node {
                writeFile file: 'artifact.txt', text: 'Hello World'
                archiveArtifacts artifacts: 'artifact.txt'
            }
            """;

        // Setup WireMock to always fail
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(
                WireMock.put(WireMock.urlMatching("/my-generic-repo/.*")).willReturn(WireMock.serverError()));

        // Setup other required stubs (excluding PUT which we handle above)
        setupOtherWireMockStubs(pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should fail after max retries
        assertThat(run.getResult(), equalTo(hudson.model.Result.FAILURE));

        // Verify exactly 2 requests were made (matching maxUploadRetries configuration)
        wireMock.verifyThat(2, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*")));

        // Check build log contains retry messages
        String buildLog = run.getLog();
        assertThat(buildLog, containsString("Unable to upload files to Artifactory"));
    }

    @Test
    public void shouldRetryStashUploadOnTransientFailure(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        String pipelineName = "shouldRetryStashUploadOnTransientFailure";

        // Create pipeline that creates a stash
        String pipeline =
                """
            node {
                writeFile file: 'stash-file.txt', text: 'Stash content'
                stash name: 'test-stash', includes: 'stash-file.txt'
            }
            """;

        // Setup WireMock to fail on first 2 attempts, succeed on 3rd
        WireMock wireMock = wmRuntimeInfo.getWireMock();

        String scenarioName = "stash-retry-scenario";

        // First attempt fails
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*/stashes/.*"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.serverError())
                .willSetStateTo("second-attempt"));

        // Second attempt succeeds
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*/stashes/.*"))
                .inScenario(scenarioName)
                .whenScenarioStateIs("second-attempt")
                .willReturn(WireMock.okJson("{}")));

        // Setup other required stubs (for artifacts that won't be created in this test)
        setupOtherWireMockStubs(
                pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "test-stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should succeed after retries
        assertThat(run.getResult(), equalTo(hudson.model.Result.SUCCESS));

        // Verify exactly 2 stash upload requests were made
        wireMock.verifyThat(2, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*/stashes/.*")));
    }

    @Test
    public void shouldFailStashUploadAfterMaxRetries(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        String pipelineName = "shouldFailStashUploadAfterMaxRetries";

        // Create pipeline that creates a stash
        String pipeline =
                """
            node {
                writeFile file: 'stash-file.txt', text: 'Stash content'
                stash name: 'test-stash', includes: 'stash-file.txt'
            }
            """;

        // Setup WireMock to always fail for stash uploads
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*/stashes/.*"))
                .willReturn(WireMock.serverError()));

        // Setup other required stubs
        setupOtherWireMockStubs(
                pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "test-stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should fail after max retries
        assertThat(run.getResult(), equalTo(hudson.model.Result.FAILURE));

        // Verify exactly 2 stash upload requests were made (matching maxUploadRetries configuration)
        wireMock.verifyThat(2, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*/stashes/.*")));

        // Check build log contains retry messages
        String buildLog = run.getLog();
        assertThat(buildLog, containsString("Unable to stash files to Artifactory after 2 attempts"));
    }

    @Test
    public void shouldSucceedImmediatelyWhenNoFailures(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        String pipelineName = "shouldSucceedImmediatelyWhenNoFailures";

        // Create pipeline that archives a file and creates a stash
        String pipeline =
                """
            node {
                writeFile file: 'artifact.txt', text: 'Hello World'
                writeFile file: 'stash-file.txt', text: 'Stash content'
                archiveArtifacts artifacts: 'artifact.txt'
                stash name: 'test-stash', includes: 'stash-file.txt'
            }
            """;

        // Setup WireMock to always succeed
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(
                WireMock.put(WireMock.urlMatching("/my-generic-repo/.*")).willReturn(WireMock.okJson("{}")));

        // Setup other required stubs
        setupOtherWireMockStubs(
                pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "test-stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should succeed immediately
        assertThat(run.getResult(), equalTo(hudson.model.Result.SUCCESS));

        // Verify only 2 requests were made (1 for artifact, 1 for stash) - no retries
        wireMock.verifyThat(2, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*")));
    }

    @Test
    public void shouldFailImmediatelyWithZeroRetries(JenkinsRule jenkinsRule, WireMockRuntimeInfo wmRuntimeInfo)
            throws Exception {
        // Configure with 0 retries
        ArtifactoryGenericArtifactConfig config = configureConfig(jenkinsRule, wmRuntimeInfo.getHttpPort(), "");
        config.setMaxUploadRetries(0);
        
        String pipelineName = "shouldFailImmediatelyWithZeroRetries";

        // Create pipeline that archives a file
        String pipeline =
                """
            node {
                writeFile file: 'artifact.txt', text: 'Hello World'
                archiveArtifacts artifacts: 'artifact.txt'
            }
            """;

        // Setup WireMock to always fail
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(WireMock.put(WireMock.urlMatching("/my-generic-repo/.*"))
                .willReturn(WireMock.serverError()));

        // Setup other required stubs
        setupOtherWireMockStubs(
                pipelineName, wireMock, wmRuntimeInfo.getHttpPort(), "", "artifact.txt", "stash.tgz");

        // Run the pipeline
        WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
        workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
        WorkflowRun run = Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
        jenkinsRule.waitForCompletion(run);

        // Should fail immediately
        assertThat(run.getResult(), equalTo(hudson.model.Result.FAILURE));

        // Verify exactly 1 request was made (no retries)
        wireMock.verifyThat(1, WireMock.putRequestedFor(WireMock.urlMatching("/my-generic-repo/.*")));

        // Check build log contains appropriate message
        String buildLog = run.getLog();
        assertThat(buildLog, containsString("on first attempt (no retries configured)"));
    }
}
