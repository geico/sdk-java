package io.temporal.activity;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import io.temporal.workflow.shared.TestActivities.TestLocalActivity;
import io.temporal.workflow.shared.TestActivities.TestLocalActivityImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnMap;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class DefaultActivityOptionsSetOnWorkflowTest {

  private static final ActivityOptions workflowOps = ActivityTestOptions.newActivityOptions1();
  private static final ActivityOptions workerOps = ActivityTestOptions.newActivityOptions2();
  private static final ActivityOptions activity2Ops =
      SDKTestOptions.newActivityOptions20sScheduleToClose();
  private static final Map<String, ActivityOptions> activity2options =
      Collections.singletonMap("Activity2", activity2Ops);
  private static final Map<String, ActivityOptions> defaultActivity2options =
      Collections.singletonMap(
          "Activity2",
          ActivityOptions.newBuilder().setHeartbeatTimeout(Duration.ofSeconds(2)).build());

  // local activity options
  private static final LocalActivityOptions localActivityWorkflowOps =
      ActivityTestOptions.newLocalActivityOptions1();
  private static final LocalActivityOptions localActivityWorkerOps =
      ActivityTestOptions.newLocalActivityOptions2();
  private static final LocalActivityOptions localActivity2Ops =
      SDKTestOptions.newLocalActivityOptions20sScheduleToClose();
  private static final Map<String, LocalActivityOptions> localActivity2options =
      Collections.singletonMap("LocalActivity2", localActivity2Ops);
  private static final Map<String, LocalActivityOptions> defaultLocalActivity2options =
      Collections.singletonMap(
          "LocalActivity2",
          LocalActivityOptions.newBuilder().setDoNotIncludeArgumentsIntoMarker(false).build());

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setDefaultActivityOptions(workerOps)
                  .setActivityOptions(activity2options)
                  .setDefaultLocalActivityOptions(localActivityWorkerOps)
                  .setLocalActivityOptions(localActivity2options)
                  .build(),
              TestSetDefaultActivityOptionsWorkflowImpl.class)
          .setActivityImplementations(new TestActivityImpl(), new TestLocalActivityImpl())
          .build();

  @Test
  public void testSetWorkflowImplementationOptions() {
    TestWorkflowReturnMap workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflowReturnMap.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    // Check that activity1 has default workerOptions options that were partially overwritten with
    // workflow.
    Map<String, Duration> activity1Values = result.get("Activity1");
    assertEquals(workerOps.getHeartbeatTimeout(), activity1Values.get("HeartbeatTimeout"));
    assertEquals(
        workflowOps.getScheduleToCloseTimeout(), activity1Values.get("ScheduleToCloseTimeout"));
    assertEquals(workflowOps.getStartToCloseTimeout(), activity1Values.get("StartToCloseTimeout"));

    // Check that default options for activity2 were overwritten.
    Map<String, Duration> activity2Values = result.get("Activity2");
    assertEquals(
        defaultActivity2options.get("Activity2").getHeartbeatTimeout(),
        activity2Values.get("HeartbeatTimeout"));
    assertEquals(
        activity2Ops.getScheduleToCloseTimeout(), activity2Values.get("ScheduleToCloseTimeout"));
    assertEquals(workflowOps.getStartToCloseTimeout(), activity2Values.get("StartToCloseTimeout"));
  }

  @Test
  public void testSetLocalActivityWorkflowImplementationOptions() {
    TestWorkflowReturnMap workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflowReturnMap.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    Map<String, Map<String, Duration>> result = workflowStub.execute();

    // Check that local activity1 has default workerOptions options that were partially overwritten
    // with workflow.
    Map<String, Duration> localActivity1Values = result.get("LocalActivity1");
    assertEquals(
        localActivityWorkflowOps.getScheduleToCloseTimeout(),
        localActivity1Values.get("ScheduleToCloseTimeout"));
    assertEquals(
        localActivityWorkflowOps.getStartToCloseTimeout(),
        localActivity1Values.get("StartToCloseTimeout"));
    // Check that default options for local activity2 were overwritten.
    Map<String, Duration> localActivity2Values = result.get("LocalActivity2");
    assertEquals(
        localActivity2Ops.getScheduleToCloseTimeout(),
        localActivity2Values.get("ScheduleToCloseTimeout"));
    assertEquals(
        localActivityWorkflowOps.getStartToCloseTimeout(),
        localActivity2Values.get("StartToCloseTimeout"));
  }

  public static class TestSetDefaultActivityOptionsWorkflowImpl implements TestWorkflowReturnMap {
    @Override
    public Map<String, Map<String, Duration>> execute() {
      Workflow.setDefaultActivityOptions(workflowOps);
      Workflow.applyActivityOptions(defaultActivity2options);
      Workflow.setDefaultLocalActivityOptions(localActivityWorkflowOps);
      Workflow.applyLocalActivityOptions(defaultLocalActivity2options);
      Map<String, Map<String, Duration>> result = new HashMap<>();
      TestActivity activities = Workflow.newActivityStub(TestActivity.class);
      TestLocalActivity localActivities = Workflow.newLocalActivityStub(TestLocalActivity.class);
      result.put("Activity1", activities.activity1());
      result.put("Activity2", activities.activity2());
      result.put("LocalActivity1", localActivities.localActivity1());
      result.put("LocalActivity2", localActivities.localActivity2());
      return result;
    }
  }
}
