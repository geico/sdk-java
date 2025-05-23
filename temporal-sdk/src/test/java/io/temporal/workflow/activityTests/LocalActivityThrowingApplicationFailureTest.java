package io.temporal.workflow.activityTests;

import static io.temporal.workflow.activityTests.ActivityThrowingApplicationFailureTest.FAILURE_TYPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.ApplicationFailureActivity;
import io.temporal.workflow.shared.TestActivities.TestActivity4;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow4;
import java.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class LocalActivityThrowingApplicationFailureTest {

  private static WorkflowOptions options;

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LocalActivityThrowsErrorWorkflow.class)
          .setActivityImplementations(new ApplicationFailureActivity())
          .build();

  @Before
  public void setUp() {
    options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(1)
                    .setInitialInterval(Duration.ofMinutes(2))
                    .build())
            .build();
  }

  @Test
  public void retryable() {
    String name = testName.getMethodName();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow4 workflow = client.newWorkflowStub(TestWorkflow4.class, options);
    try {
      workflow.execute(name, true);
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals(FAILURE_TYPE, ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(3, ApplicationFailureActivity.invocations.get(name).get());
    }
  }

  @Test
  public void nonRetryable() {
    String name = testName.getMethodName();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow4 workflow = client.newWorkflowStub(TestWorkflow4.class, options);
    try {
      workflow.execute(name, false);
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals(FAILURE_TYPE, ((ApplicationFailure) e.getCause().getCause()).getType());
      assertEquals(1, ApplicationFailureActivity.invocations.get(name).get());
    }
  }

  public static class LocalActivityThrowsErrorWorkflow implements TestWorkflow4 {

    private final TestActivity4 activity1 =
        Workflow.newLocalActivityStub(
            TestActivity4.class,
            LocalActivityOptions.newBuilder()
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofMinutes(2))
                        .build())
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public String execute(String testName, boolean retryable) {
      activity1.execute(testName, retryable);
      return testName;
    }
  }
}
