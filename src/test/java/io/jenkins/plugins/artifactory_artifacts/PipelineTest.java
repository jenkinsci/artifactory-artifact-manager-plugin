package io.jenkins.plugins.artifactory_artifacts;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import hudson.model.Label;
import hudson.slaves.DumbSlave;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.commons.io.IOUtils;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

@WireMockTest
public class PipelineTest extends BaseTest {
    @RegisterExtension
    final RealJenkinsExtension realJenkinsExtension = new RealJenkinsExtension();

    @Test
    public void testPipelineWithPrefix(WireMockRuntimeInfo wmRuntimeInfo) throws Throwable {
        final String pipelineName = "testPipelineWithPrefix";
        final String prefix = "jenkins/";
        final String artifact = "artifact.txt";

        int wireMockPort = wmRuntimeInfo.getHttpPort();
        String pipeline = IOUtils.toString(
                Objects.requireNonNull(PipelineTest.class.getResourceAsStream("/pipelines/archiveController.groovy")),
                StandardCharsets.UTF_8);

        // Setup wiremock stubs
        setupWireMockStubs(pipelineName, wmRuntimeInfo.getWireMock(), wireMockPort, prefix, artifact, "stash.tgz");

        runWithRealJenkins(realJenkinsExtension, jenkinsRule -> {
            configureConfig(jenkinsRule, wireMockPort, prefix);
            WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
            workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
            WorkflowRun run1 =
                    Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
            jenkinsRule.waitForCompletion(run1);
            assertThat(run1.getResult(), equalTo(hudson.model.Result.SUCCESS));
            assertThat(run1.getArtifacts(), hasSize(1));
        });

        WireMock.verify(WireMock.putRequestedFor(WireMock.urlEqualTo(getArtifactUrl(prefix, pipelineName, artifact)))
                .withRequestBody(WireMock.equalTo("Hello, World!")));
    }

    @Test
    public void testPipelineWithSpaces(WireMockRuntimeInfo wmRuntimeInfo) throws Throwable {

        final String pipelineName = "test Pipeline With spaces";
        final String prefix = "jenkins artifacts/";
        final String artifact = "my artifact.txt";

        int wireMockPort = wmRuntimeInfo.getHttpPort();
        String pipeline = IOUtils.toString(
                Objects.requireNonNull(
                        PipelineTest.class.getResourceAsStream("/pipelines/archiveControllerWithSpaces.groovy")),
                StandardCharsets.UTF_8);

        // Setup wiremock stubs
        setupWireMockStubs(pipelineName, wmRuntimeInfo.getWireMock(), wireMockPort, prefix, artifact, "my stash.tgz");

        runWithRealJenkins(realJenkinsExtension, jenkinsRule -> {
            configureConfig(jenkinsRule, wireMockPort, prefix);
            WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
            workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
            WorkflowRun run1 =
                    Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
            jenkinsRule.waitForCompletion(run1);
            assertThat(run1.getResult(), equalTo(hudson.model.Result.SUCCESS));
            assertThat(run1.getArtifacts(), hasSize(1));
        });

        WireMock.verify(WireMock.putRequestedFor(WireMock.urlEqualTo(getArtifactUrl(prefix, pipelineName, artifact)))
                .withRequestBody(WireMock.equalTo("Hello, World!")));
    }

    @Test
    public void testPipelineWithoutPrefix(WireMockRuntimeInfo wmRuntimeInfo) throws Throwable {

        final String pipelineName = "testPipelineWithoutPrefix";
        final String prefix = "";
        final String artifact = "artifact.txt";

        int wireMockPort = wmRuntimeInfo.getHttpPort();
        String pipeline = IOUtils.toString(
                Objects.requireNonNull(PipelineTest.class.getResourceAsStream("/pipelines/archiveController.groovy")),
                StandardCharsets.UTF_8);

        // Setup wiremock stubs
        setupWireMockStubs(pipelineName, wmRuntimeInfo.getWireMock(), wireMockPort, prefix, artifact, "stash.tgz");

        runWithRealJenkins(realJenkinsExtension, jenkinsRule -> {
            configureConfig(jenkinsRule, wireMockPort, prefix);
            WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
            workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
            WorkflowRun run1 =
                    Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
            jenkinsRule.waitForCompletion(run1);
            assertThat(run1.getResult(), equalTo(hudson.model.Result.SUCCESS));
            assertThat(run1.getArtifacts(), hasSize(1));
        });

        WireMock.verify(WireMock.putRequestedFor(WireMock.urlEqualTo(getArtifactUrl(prefix, pipelineName, artifact)))
                .withRequestBody(WireMock.equalTo("Hello, World!")));
    }

    @Test
    public void testPipelineOnAgentWithPrefix(WireMockRuntimeInfo wmRuntimeInfo) throws Throwable {

        final String pipelineName = "testPipelineWithPrefix";
        final String prefix = "jenkins/";
        final String artifact = "artifact.txt";

        int wireMockPort = wmRuntimeInfo.getHttpPort();
        String pipeline = IOUtils.toString(
                Objects.requireNonNull(PipelineTest.class.getResourceAsStream("/pipelines/archiveAgent.groovy")),
                StandardCharsets.UTF_8);

        // Setup wiremock stubs
        setupWireMockStubs(pipelineName, wmRuntimeInfo.getWireMock(), wireMockPort, prefix, artifact, "stash.tgz");

        runWithRealJenkins(realJenkinsExtension, jenkinsRule -> {
            configureConfig(jenkinsRule, wireMockPort, prefix);
            DumbSlave s = jenkinsRule.createSlave(Label.get("agent"));
            WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
            workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
            WorkflowRun run1 =
                    Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
            jenkinsRule.waitForCompletion(run1);
            assertThat(run1.getResult(), equalTo(hudson.model.Result.SUCCESS));
            assertThat(run1.getArtifacts(), hasSize(1));
        });

        WireMock.verify(WireMock.putRequestedFor(WireMock.urlEqualTo(getArtifactUrl(prefix, pipelineName, artifact)))
                .withRequestBody(WireMock.equalTo("Hello, World!")));
    }

    @Test
    public void testReplay(WireMockRuntimeInfo wmRuntimeInfo) throws Throwable {

        final String pipelineName = "testReplay";

        int wireMockPort = wmRuntimeInfo.getHttpPort();
        String pipeline = IOUtils.toString(
                Objects.requireNonNull(PipelineTest.class.getResourceAsStream("/pipelines/replay.groovy")),
                StandardCharsets.UTF_8);

        // Setup wiremock stubs
        setupWireMockStubs(
                pipelineName, wmRuntimeInfo.getWireMock(), wireMockPort, "jenkins/", "artifact.txt", "stash.tgz");

        runWithRealJenkins(realJenkinsExtension, jenkinsRule -> {
            configureConfig(jenkinsRule, wireMockPort, "jenkins/");
            WorkflowJob workflowJob = jenkinsRule.createProject(WorkflowJob.class, pipelineName);
            workflowJob.setDefinition(new CpsFlowDefinition(pipeline, true));
            WorkflowRun run1 =
                    Objects.requireNonNull(workflowJob.scheduleBuild2(0)).waitForStart();
            jenkinsRule.waitForCompletion(run1);
            System.out.println(run1.getLog());
            assertThat(run1.getResult(), equalTo(hudson.model.Result.SUCCESS));
            HtmlPage buildPage = jenkinsRule.createWebClient().goTo("job/testReplay/1");
            final HtmlAnchor replayLink = buildPage.getAnchorByText("Replay");
            final HtmlPage replayPage = replayLink.click();
            // HtmlSelect stageSelect = replayPage.getFirstByXPath("//select[@name='Result']");
            // final HtmlOption resultOption = stageSelect.getOptionByText("Result");
            // stageSelect.setSelectedAttribute(resultOption, true);
        });
    }

    private String getArtifactUrl(String prefix, String pipelineName, String artifact) {
        return ("/my-generic-repo/" + prefix + pipelineName + "/1/artifacts/" + artifact).replaceAll(" ", "%20");
    }
}
