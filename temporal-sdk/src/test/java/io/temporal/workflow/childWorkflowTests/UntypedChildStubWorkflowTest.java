package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UntypedChildStubWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUntypedChildStubWorkflow.class, TestMultiArgWorkflowImpl.class)
          .build();

  @Test
  public void testUntypedChildStubWorkflow() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflow1.class);
    Assert.assertEquals(null, client.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestUntypedChildStubWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder().setTaskQueue(taskQueue).build();
      ChildWorkflowStub stubF =
          Workflow.newUntypedChildWorkflowStub("TestNoArgsWorkflowFunc", workflowOptions);
      assertEquals("func", stubF.execute(String.class));
      // Workflow type overridden through the @WorkflowMethod.name
      ChildWorkflowStub stubF1 = Workflow.newUntypedChildWorkflowStub("func1", workflowOptions);
      assertEquals("1", stubF1.execute(String.class, "1"));
      ChildWorkflowStub stubF2 =
          Workflow.newUntypedChildWorkflowStub("Test2ArgWorkflowFunc", workflowOptions);
      assertEquals("12", stubF2.execute(String.class, "1", 2));
      ChildWorkflowStub stubF3 =
          Workflow.newUntypedChildWorkflowStub("Test3ArgWorkflowFunc", workflowOptions);
      assertEquals("123", stubF3.execute(String.class, "1", 2, 3));
      ChildWorkflowStub stubF4 =
          Workflow.newUntypedChildWorkflowStub("Test4ArgWorkflowFunc", workflowOptions);
      assertEquals("1234", stubF4.execute(String.class, "1", 2, 3, 4));
      ChildWorkflowStub stubF5 =
          Workflow.newUntypedChildWorkflowStub("Test5ArgWorkflowFunc", workflowOptions);
      assertEquals("12345", stubF5.execute(String.class, "1", 2, 3, 4, 5));
      ChildWorkflowStub stubF6 =
          Workflow.newUntypedChildWorkflowStub("Test6ArgWorkflowFunc", workflowOptions);
      assertEquals("123456", stubF6.execute(String.class, "1", 2, 3, 4, 5, 6));

      ChildWorkflowStub stubP =
          Workflow.newUntypedChildWorkflowStub("TestNoArgsWorkflowProc", workflowOptions);
      stubP.execute(Void.class);
      ChildWorkflowStub stubP1 =
          Workflow.newUntypedChildWorkflowStub("Test1ArgWorkflowProc", workflowOptions);
      stubP1.execute(Void.class, "1");
      ChildWorkflowStub stubP2 =
          Workflow.newUntypedChildWorkflowStub("Test2ArgWorkflowProc", workflowOptions);
      stubP2.execute(Void.class, "1", 2);
      ChildWorkflowStub stubP3 =
          Workflow.newUntypedChildWorkflowStub("Test3ArgWorkflowProc", workflowOptions);
      stubP3.execute(Void.class, "1", 2, 3);
      ChildWorkflowStub stubP4 =
          Workflow.newUntypedChildWorkflowStub("Test4ArgWorkflowProc", workflowOptions);
      stubP4.execute(Void.class, "1", 2, 3, 4);
      ChildWorkflowStub stubP5 =
          Workflow.newUntypedChildWorkflowStub("Test5ArgWorkflowProc", workflowOptions);
      stubP5.execute(Void.class, "1", 2, 3, 4, 5);
      ChildWorkflowStub stubP6 =
          Workflow.newUntypedChildWorkflowStub("Test6ArgWorkflowProc", workflowOptions);
      stubP6.execute(Void.class, "1", 2, 3, 4, 5, 6);
      return null;
    }
  }
}
