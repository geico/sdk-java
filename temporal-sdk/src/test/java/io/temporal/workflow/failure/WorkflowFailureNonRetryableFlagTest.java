package io.temporal.workflow.failure;

import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowFailureNonRetryableFlagTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowNonRetryableFlag.class).build();

  @Test
  public void nonRetryableFlag() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(100)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setRetryOptions(workflowRetryOptions)
                    .build());
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
      Assert.assertEquals("foo", ((ApplicationFailure) e.getCause()).getType());
      Assert.assertEquals(
          "details1", ((ApplicationFailure) e.getCause()).getDetails().get(0, String.class));
      Assert.assertEquals(
          Integer.valueOf(123),
          ((ApplicationFailure) e.getCause()).getDetails().get(1, Integer.class));
      Assert.assertEquals(
          "message='simulated 3', type='foo', nonRetryable=true", e.getCause().getMessage());
    }
  }

  public static class TestWorkflowNonRetryableFlag implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.computeIfAbsent(testName, ignore -> new AtomicInteger());
      int c = count.incrementAndGet();
      ApplicationFailure f =
          ApplicationFailure.newFailure("simulated " + c, "foo", "details1", 123);
      if (c == 3) {
        f.setNonRetryable(true);
      }
      throw f;
    }
  }
}
