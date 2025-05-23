
package io.temporal.common.metadata

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowMethodNameTest {

  @Test
  fun `workflowSignalName should resolve simple workflow signal name`() {
    assertEquals("signal1", workflowSignalName(Workflow1::signal1))
  }

  @Test
  fun `workflowSignalName should resolve workflow signal name override`() {
    assertEquals("customSignalName", workflowSignalName(Workflow1::signal2))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowSignalName should fail if provided with query method instead of signal method`() {
    workflowSignalName(Workflow1::query1)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowSignalName should fail if used with non-workflow method`() {
    workflowSignalName(NotAWorkflow::aMethod)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowSignalName should fail if not provided with a method reference`() {
    workflowSignalName(::String)
  }

  @Test
  fun `workflowQueryType should resolve simple workflow query type`() {
    assertEquals("query1", workflowQueryType(Workflow1::query1))
  }

  @Test
  fun `workflowQueryType should resolve workflow query type override`() {
    assertEquals("customQueryType", workflowQueryType(Workflow1::query2))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowQueryType should fail if provided with signal method instead of query method`() {
    workflowQueryType(Workflow1::signal1)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowQueryType should fail if used with non-workflow method`() {
    workflowQueryType(NotAWorkflow::aMethod)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `workflowQueryType should fail if not provided with a method reference`() {
    workflowQueryType(::String)
  }

  @WorkflowInterface
  interface Workflow1 {

    @WorkflowMethod
    fun someWorkflowMethod()

    @SignalMethod
    fun signal1()

    @SignalMethod(name = "customSignalName")
    fun signal2()

    @QueryMethod
    fun query1(): Int

    @QueryMethod(name = "customQueryType")
    fun query2(): Long
  }

  abstract class NotAWorkflow {
    abstract fun aMethod()
  }
}
