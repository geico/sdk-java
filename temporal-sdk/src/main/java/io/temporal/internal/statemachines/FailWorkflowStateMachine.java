package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;

final class FailWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        FailWorkflowStateMachine.State,
        FailWorkflowStateMachine.ExplicitEvent,
        FailWorkflowStateMachine> {

  private final FailWorkflowExecutionCommandAttributes failWorkflowAttributes;

  public static void newInstance(
      Failure failure,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    FailWorkflowExecutionCommandAttributes attributes =
        FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(failure).build();
    new FailWorkflowStateMachine(attributes, commandSink, stateMachineSink);
  }

  private FailWorkflowStateMachine(
      FailWorkflowExecutionCommandAttributes failWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.failWorkflowAttributes = failWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    FAIL_WORKFLOW_COMMAND_CREATED,
    FAIL_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, FailWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, FailWorkflowStateMachine>newInstance(
                  "FailWorkflow", State.CREATED, State.FAIL_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.FAIL_WORKFLOW_COMMAND_CREATED,
                  FailWorkflowStateMachine::createFailWorkflowCommand)
              .add(
                  State.FAIL_WORKFLOW_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION,
                  State.FAIL_WORKFLOW_COMMAND_CREATED)
              .add(
                  State.FAIL_WORKFLOW_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED,
                  State.FAIL_WORKFLOW_COMMAND_RECORDED);

  private void createFailWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .setFailWorkflowExecutionCommandAttributes(failWorkflowAttributes)
            .build());
  }
}
