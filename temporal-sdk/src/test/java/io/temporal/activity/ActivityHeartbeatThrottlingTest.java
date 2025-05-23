package io.temporal.activity;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class ActivityHeartbeatThrottlingTest {

  private static final Signal secondHeartbeatSent = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new HeartBeatingActivityImpl())
          .build();

  /**
   * Tests that Activity#heartbeat throws ActivityWorkerShutdownException after {@link
   * WorkerFactory#shutdown()} is closed.
   */
  @Test
  public void activityHeartbeatsGetThrottled() throws InterruptedException {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    secondHeartbeatSent.waitForSignal();

    DescribeWorkflowExecutionResponse describeResponse =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setExecution(execution)
                    .build());

    String payload =
        new JacksonJsonPayloadConverter()
            .fromData(
                describeResponse.getPendingActivities(0).getHeartbeatDetails().getPayloads(0),
                String.class,
                String.class);
    assertEquals(
        "Only the first heartbeat should've get through, others should be throttled", "1", payload);
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {

    private final TestActivities.NoArgsActivity activities =
        Workflow.newActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public void execute() {
      activities.execute();
    }
  }

  public static class HeartBeatingActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      Activity.getExecutionContext().heartbeat("1");
      Activity.getExecutionContext().heartbeat("2");
      secondHeartbeatSent.signal();

      int i = 2;
      while (true) {
        try {
          Activity.getExecutionContext().heartbeat("" + ++i);
        } catch (ActivityWorkerShutdownException e) {
          // it's a normal shutdown, just exit if this happens
          return;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    }
  }
}
