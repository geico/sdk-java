package io.temporal.workflow.activityTests;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.io.IOException;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityRetryOptionsChangeTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncActivityRetryOptionsChange.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testAsyncActivityRetryOptionsChange() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      assertThat(e.getCause(), instanceOf(ActivityFailure.class));
      assertThat(e.getCause().getCause(), instanceOf(ApplicationFailure.class));
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  public static class TestAsyncActivityRetryOptionsChange implements TestWorkflow1 {

    private VariousTestActivities activities;

    @Override
    public String execute(String taskQueue) {
      ActivityOptions.Builder options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setScheduleToCloseTimeout(Duration.ofSeconds(8));
      if (WorkflowUnsafe.isReplaying()) {
        options.setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setDoNotRetry(NullPointerException.class.getName())
                .build());
      } else {
        options.setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumInterval(Duration.ofSeconds(1))
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(2)
                .setDoNotRetry(NullPointerException.class.getName())
                .build());
      }
      this.activities = Workflow.newActivityStub(VariousTestActivities.class, options.build());
      Async.procedure(activities::throwIO).get();
      return "ignored";
    }
  }
}
