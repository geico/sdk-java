package io.temporal.opentracing;

import static org.junit.Assert.*;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class SpanContextPropagationTest {

  private static final String BAGGAGE_ITEM_KEY = "baggage-item-key";

  private static final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new OpenTracingAwareActivityImpl())
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String activity1(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow1(String input);
  }

  public static class OpenTracingAwareActivityImpl implements TestActivity {
    @Override
    public String activity1(String input) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();

      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertNotEquals(0, mockSpan.parentId());

      return activeSpan.getBaggageItem(BAGGAGE_ITEM_KEY);
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public String workflow1(String input) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();

      MockSpan mockSpan = (MockSpan) activeSpan;
      assertNotNull(activeSpan);
      assertNotEquals(0, mockSpan.parentId());

      return activity.activity1(input);
    }
  }

  /**
   * This test checks that all elements of a trivial workflow with an activity are connected with
   * the same root span. We set baggage item on the top level in a client span and use the baggage
   * item inside the activity. This way we ensure that the baggage item has been propagated all the
   * way from top to bottom.
   */
  @Test
  public void testBaggageItemPropagationToActivity() {
    Span span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();
      final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
      activeSpan.setBaggageItem(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE);

      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals(BAGGAGE_ITEM_VALUE, workflow.workflow1("input"));
    } finally {
      span.finish();
    }
  }
}
