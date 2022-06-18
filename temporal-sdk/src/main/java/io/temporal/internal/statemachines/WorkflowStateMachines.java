/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.statemachines;

import static io.temporal.internal.common.WorkflowExecutionUtils.getEventTypeForCommand;
import static io.temporal.internal.common.WorkflowExecutionUtils.isCommandEvent;
import static io.temporal.internal.statemachines.LocalActivityStateMachine.*;
import static io.temporal.serviceclient.CheckedExceptionWrapper.unwrap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.*;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.history.MarkerUtils;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.replay.InternalWorkflowTaskException;
import io.temporal.internal.replay.StartChildWorkflowExecutionParameters;
import io.temporal.internal.sync.WorkflowThread;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.worker.NonDeterministicException;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.Functions;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WorkflowStateMachines {

  enum HandleEventStatus {
    OK,
    NON_MATCHING_EVENT
  }

  /**
   * EventId of the WorkflowTaskStarted event of the Workflow Task that was picked up by a worker
   * and triggered a current replay or execution. It's expected to be the last event in the history
   * if we continue to execute the workflow.
   *
   * <p>For direct (legacy) queries, it may be:
   *
   * <ul>
   *   <li>0 if it's query a closed workflow execution
   *   <li>id of the last successfully completed Workflow Task if the workflow is not closed
   * </ul>
   *
   * <p>Set from the "outside" from the PollWorkflowTaskQueueResponse. Not modified by the SDK state
   * machines.
   */
  private long workflowTaskStartedEventId;

  /**
   * EventId of the WorkflowTaskStarted event of the last successfully executed Workflow Task in the
   * history. This variable plays a critical role in state machines understanding if it replays code
   * for an existing history, or it executes it first time and produces new commands.
   *
   * <p>Set from the "outside" of state machines from the PollWorkflowTaskQueueResponse. Not
   * modified by the SDK state machines.
   */
  private long previousStartedEventId;

  /** EventId of the last WorkflowTaskStarted event handled by these state machines. */
  private long currentStartedEventId;

  /**
   * EventId of the last event seen by these state machines. Events earlier than this one will be
   * discarded.
   */
  private long lastHandledEventId;

  private final StatesMachinesCallback callbacks;

  /** Callback to send new commands to. */
  private final Functions.Proc1<CancellableCommand> commandSink;

  /**
   * currentRunId is used as seed by Workflow.newRandom and randomUUID. It allows to generate them
   * deterministically.
   */
  private String currentRunId;

  /** Used Workflow.newRandom and randomUUID together with currentRunId. */
  private long idCounter;

  /** Current workflow time. */
  private long currentTimeMillis = -1;

  private final Map<Long, EntityStateMachine> stateMachines = new HashMap<>();

  private final Queue<CancellableCommand> commands = new ArrayDeque<>();

  /**
   * Commands generated by the currently processed workflow task. It is a queue as commands can be
   * added (due to marker based commands) while iterating over already added commands.
   */
  private final Queue<CancellableCommand> cancellableCommands = new ArrayDeque<>();

  /** Is workflow executing new code or replaying from the history. */
  private boolean replaying;

  /** Used to ensure that event loop is not executed recursively. */
  private boolean eventLoopExecuting;

  /**
   * Used to avoid recursive calls to {@link #prepareCommands()}.
   *
   * <p>Such calls happen when sideEffects and localActivity markers are processed.
   */
  private boolean preparing;

  /** Key is mutable side effect id */
  private final Map<String, MutableSideEffectStateMachine> mutableSideEffects = new HashMap<>();

  /** Key is changeId */
  private final Map<String, VersionStateMachine> versions = new HashMap<>();

  /** Map of local activities by their id. */
  private final Map<String, LocalActivityStateMachine> localActivityMap = new HashMap<>();

  private List<ExecuteLocalActivityParameters> localActivityRequests = new ArrayList<>();

  private final Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink;
  private final Functions.Proc1<StateMachine> stateMachineSink;

  private final WFTBuffer wftBuffer = new WFTBuffer();

  public WorkflowStateMachines(StatesMachinesCallback callbacks) {
    this(callbacks, (stateMachine) -> {});
  }

  @VisibleForTesting
  public WorkflowStateMachines(
      StatesMachinesCallback callbacks, Functions.Proc1<StateMachine> stateMachineSink) {
    this.callbacks = Objects.requireNonNull(callbacks);
    this.commandSink = cancellableCommands::add;
    this.stateMachineSink = stateMachineSink;
    this.localActivityRequestSink = (request) -> localActivityRequests.add(request);
  }

  /**
   * @param previousStartedEventId eventId of the last EVENT_TYPE_WORKFLOW_TASK_STARTED in history
   *     that was successfully finished.
   * @param workflowTaskStartedEventId eventId of the workflowTask that was picked up by a worker
   *     and triggered a replay or an execution
   */
  public void setStartedIds(long previousStartedEventId, long workflowTaskStartedEventId) {
    if (previousStartedEventId < currentStartedEventId) {
      // if previousStartedEventId < currentStartedEventId - the last workflow task handled by these
      // state machines is ahead of the last handled workflow task known by the server. Something is
      // off, the server lost progress.
      throw new IllegalStateException(
          "Server history for the workflow is below the progress of the workflow on the worker, the progress needs to be discarded");
    }
    this.previousStartedEventId = previousStartedEventId;
    this.workflowTaskStartedEventId = workflowTaskStartedEventId;
    // if previousStartedEventId == currentStartedEventId -
    // we just apply events generated by this workflow task during execution and state a new WFt,
    // it's not a replay.
    this.replaying = previousStartedEventId > currentStartedEventId;
  }

  /**
   * Handle a single event from the workflow history.
   *
   * @param event event from the history.
   * @param hasNextEvent false if this is the last event in the history.
   */
  public void handleEvent(HistoryEvent event, boolean hasNextEvent) {
    long eventId = event.getEventId();
    if (eventId <= lastHandledEventId) {
      // already handled
      return;
    }
    lastHandledEventId = eventId;
    boolean readyToPeek = wftBuffer.addEvent(event, hasNextEvent);
    if (readyToPeek) {
      handleEventsBatch(wftBuffer.fetch(), hasNextEvent);
    }
  }

  /**
   * Handle an events batch for one workflow task. Events that are related to one workflow task
   * during replay should be prefetched and supplied in one batch.
   *
   * @param events events belong to one workflow task
   * @param hasNextEvent true if there are more events in the history follow this batch, false if
   *     this batch contains the last events of the history
   */
  private void handleEventsBatch(List<HistoryEvent> events, boolean hasNextEvent) {
    for (HistoryEvent event : events) {
      try {
        preloadVersionMarker(event);
      } catch (RuntimeException e) {
        throw createEventProcessingException(e, event);
      }
    }

    for (Iterator<HistoryEvent> iterator = events.iterator(); iterator.hasNext(); ) {
      HistoryEvent event = iterator.next();
      try {
        handleSingleEvent(event, iterator.hasNext() || hasNextEvent);
      } catch (RuntimeException e) {
        throw createEventProcessingException(e, event);
      }
    }
  }

  private RuntimeException createEventProcessingException(RuntimeException e, HistoryEvent event) {
    Throwable ex = unwrap(e);
    if (ex instanceof NonDeterministicException) {
      // just appending the message in front of an existing message, saving the original stacktrace
      NonDeterministicException modifiedException =
          new NonDeterministicException(
              createEventHandlingMessage(event)
                  + ". "
                  + ex.getMessage()
                  + ". "
                  + createShortCurrentStateMessagePostfix(),
              ex.getCause());
      modifiedException.setStackTrace(ex.getStackTrace());
      return modifiedException;
    } else {
      return new InternalWorkflowTaskException(
          createEventHandlingMessage(event) + ". " + createShortCurrentStateMessagePostfix(), ex);
    }
  }

  private void handleSingleEvent(HistoryEvent event, boolean hasNextEvent) {
    if (isCommandEvent(event)) {
      handleCommandEvent(event);
      return;
    }

    Long initialCommandEventId = getInitialCommandEventId(event);
    EntityStateMachine c = stateMachines.get(initialCommandEventId);
    if (c != null) {
      c.handleEvent(event, hasNextEvent);
      if (c.isFinalState()) {
        stateMachines.remove(initialCommandEventId);
      }
    } else {
      handleNonStatefulEvent(event, hasNextEvent);
    }

    if (replaying
        && currentStartedEventId >= previousStartedEventId
        && event.getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
      // Important note for understanding how does this condition for finishing replay work:
      // 1. when we process EVENT_TYPE_WORKFLOW_TASK_STARTED,
      // currentStartedEventId still points on the previous workflow task.
      // 2. command events are not reaching this line by having a return earlier.
      // ^ This allows to correctly set replaying=false after the whole last
      // EVENT_TYPE_WORKFLOW_TASK_STARTED, EVENT_TYPE_WORKFLOW_TASK_COMPLETED, COMMAND_EVENTS
      // sequence is finished processing.
      replaying = false;
    }
  }

  /**
   * Handles command event. Command event is an event which is generated from a command emitted by a
   * past decision. Each command has a correspondent event. For example ScheduleActivityTaskCommand
   * is recorded to the history as ActivityTaskScheduledEvent.
   *
   * <p>Command events always follow WorkflowTaskCompletedEvent.
   *
   * <p>The handling consists from verifying that the next command in the commands queue matches the
   * event, command state machine is notified about the event and the command is removed from the
   * commands queue.
   */
  private void handleCommandEvent(HistoryEvent event) {
    if (handleLocalActivityMarker(event)) {
      return;
    }
    // Match event to the next command in the stateMachine queue.
    // After matching the command is notified about the event and is removed from the
    // queue.
    CancellableCommand matchingCommand = null;
    while (matchingCommand == null) {
      // handleVersionMarker can skip a marker event if the getVersion call was removed.
      // In this case we don't want to consume a command.
      // That's why peek is used instead of poll.
      CancellableCommand command = commands.peek();
      if (command == null) {
        if (handleNonMatchingVersionMarker(event)) {
          // this event is a version marker for removed getVersion call.
          // Handle the version marker as unmatched and return even if there is no commands to match
          // it against.
          return;
        } else {
          throw new NonDeterministicException("No command scheduled that corresponds to " + event);
        }
      }

      if (command.isCanceled()) {
        // Consume and skip the command
        commands.poll();
        continue;
      }

      // Note that handleEvent can cause a command cancellation in case of
      // 1. MutableSideEffect
      // 2. Version State Machine during replay cancels the command and enters SKIPPED state
      //    if it handled non-matching event.
      HandleEventStatus status = command.handleEvent(event, true);

      if (command.isCanceled()) {
        // Consume and skip the command
        commands.poll();
        continue;
      }

      switch (status) {
        case OK:
          // Consume the command
          commands.poll();
          matchingCommand = command;
          break;
        case NON_MATCHING_EVENT:
          if (handleNonMatchingVersionMarker(event)) {
            // this event is a version marker for removed getVersion call.
            // Handle the version marker as unmatched and return without consuming the command
            return;
          } else {
            throw new NonDeterministicException(
                "Event "
                    + event.getEventId()
                    + " of type "
                    + event.getEventType()
                    + " does not"
                    + " match command type "
                    + command.getCommandType());
          }
        default:
          throw new IllegalStateException(
              "Got " + status + " value from command.handleEvent which is not handled");
      }
    }

    validateCommand(matchingCommand.getCommand(), event);
    EntityStateMachine stateMachine = matchingCommand.getStateMachine();
    if (!stateMachine.isFinalState()) {
      stateMachines.put(event.getEventId(), stateMachine);
    }
    // Marker is the only command processing of which can cause workflow code execution
    // and generation of new state machines.
    if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED) {
      prepareCommands();
    }
  }

  private void preloadVersionMarker(HistoryEvent event) {
    if (replaying && VersionMarkerUtils.hasVersionMarkerStructure(event)) {
      String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
      if (changeId == null) {
        // if we can't extract changeId, this event will later fail to match with anything
        // and the corresponded exception will be thrown
        return;
      }
      VersionStateMachine versionStateMachine =
          versions.computeIfAbsent(
              changeId,
              (idKey) ->
                  VersionStateMachine.newInstance(
                      changeId, this::isReplaying, commandSink, stateMachineSink));
      versionStateMachine.handleMarkersPreload(event);
    }
  }

  private boolean handleNonMatchingVersionMarker(HistoryEvent event) {
    String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
    if (changeId == null) {
      return false;
    }
    VersionStateMachine versionStateMachine = versions.get(changeId);
    Preconditions.checkNotNull(
        versionStateMachine,
        "versionStateMachine is expected to be initialized already by execution or preloading");
    versionStateMachine.handleNonMatchingEvent(event);
    return true;
  }

  public List<Command> takeCommands() {
    List<Command> result = new ArrayList<>(commands.size());
    for (CancellableCommand command : commands) {
      if (!command.isCanceled()) {
        result.add(command.getCommand());
      }
    }
    return result;
  }

  private void prepareCommands() {
    if (preparing) {
      return;
    }
    preparing = true;
    try {
      prepareImpl();
    } finally {
      preparing = false;
    }
  }

  private void prepareImpl() {
    // handleCommand can lead to code execution because of SideEffect, MutableSideEffect or local
    // activity completion. And code execution can lead to creation of new commands and
    // cancellation of existing commands. That is the reason for using Queue as a data structure for
    // commands.
    while (true) {
      CancellableCommand command = cancellableCommands.poll();
      if (command == null) {
        break;
      }
      // handleCommand should be called even on canceled ones to support mutableSideEffect
      command.handleCommand(command.getCommandType());
      commands.add(command);
    }
  }

  /**
   * Local activity is different from all other entities. It doesn't schedule a marker command when
   * the {@link #scheduleLocalActivityTask(ExecuteLocalActivityParameters, Functions.Proc2)} is
   * called. The marker is scheduled only when activity completes through ({@link
   * #handleLocalActivityCompletion(ActivityTaskHandler.Result)}). That's why the normal logic of
   * {@link #handleCommandEvent(HistoryEvent)}, which assumes that each event has a correspondent
   * command during replay, doesn't work. Instead, local activities are matched by their id using
   * localActivityMap.
   *
   * @return true if matched and false if normal event handling should continue.
   */
  private boolean handleLocalActivityMarker(HistoryEvent event) {
    if (!MarkerUtils.verifyMarkerName(event, LOCAL_ACTIVITY_MARKER_NAME)) {
      return false;
    }

    MarkerRecordedEventAttributes markerAttributes = event.getMarkerRecordedEventAttributes();
    String id =
        MarkerUtils.getValueFromMarker(markerAttributes, MARKER_ACTIVITY_ID_KEY, String.class);
    LocalActivityStateMachine stateMachine = localActivityMap.remove(id);
    if (stateMachine == null) {
      String activityType =
          MarkerUtils.getValueFromMarker(markerAttributes, MARKER_ACTIVITY_TYPE_KEY, String.class);
      throw new NonDeterministicException(
          String.format(
              "Local activity of type %s is recorded in the history with id %s but was not expected by the execution",
              activityType, id));
    }
    // RESULT_NOTIFIED state means that there is outstanding command that has to be matched
    // using standard logic. So return false to let the handleCommand method to run its standard
    // logic.
    if (stateMachine.getState() == LocalActivityStateMachine.State.RESULT_NOTIFIED) {
      return false;
    }
    stateMachine.handleEvent(event, true);
    eventLoop();
    return true;
  }

  private void eventLoop() {
    if (eventLoopExecuting) {
      return;
    }
    eventLoopExecuting = true;
    try {
      callbacks.eventLoop();
    } finally {
      eventLoopExecuting = false;
    }
    prepareCommands();
  }

  private void handleNonStatefulEvent(HistoryEvent event, boolean hasNextEvent) {
    switch (event.getEventType()) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
        this.currentRunId =
            event.getWorkflowExecutionStartedEventAttributes().getOriginalExecutionRunId();
        callbacks.start(event);
        break;
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
        WorkflowTaskStateMachine c =
            WorkflowTaskStateMachine.newInstance(
                workflowTaskStartedEventId, new WorkflowTaskCommandsListener());
        c.handleEvent(event, hasNextEvent);
        stateMachines.put(event.getEventId(), c);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
        callbacks.signal(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        callbacks.cancel(event);
        break;
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case UNRECOGNIZED:
        break;
      default:
        throw new IllegalArgumentException("Unexpected event:" + event);
    }
  }

  private long setCurrentTimeMillis(long currentTimeMillis) {
    if (this.currentTimeMillis < currentTimeMillis) {
      this.currentTimeMillis = currentTimeMillis;
    }
    return this.currentTimeMillis;
  }

  public long getLastStartedEventId() {
    return currentStartedEventId;
  }

  /**
   * @param attributes attributes used to schedule an activity
   * @param callback completion callback
   * @return an instance of ActivityCommands
   */
  public Functions.Proc scheduleActivityTask(
      ExecuteActivityParameters attributes, Functions.Proc2<Optional<Payloads>, Failure> callback) {
    checkEventLoopExecuting();
    ActivityStateMachine activityStateMachine =
        ActivityStateMachine.newInstance(
            attributes,
            (p, f) -> {
              callback.apply(p, f);
              if (f != null && f.hasCause() && f.getCause().hasCanceledFailureInfo()) {
                eventLoop();
              }
            },
            commandSink,
            stateMachineSink);
    return activityStateMachine::cancel;
  }

  /**
   * Creates a new timer state machine
   *
   * @param attributes timer command attributes
   * @param completionCallback invoked when timer fires or reports cancellation. One of
   *     TimerFiredEvent, TimerCanceledEvent.
   * @return cancellation callback that should be invoked to initiate timer cancellation
   */
  public Functions.Proc newTimer(
      StartTimerCommandAttributes attributes, Functions.Proc1<HistoryEvent> completionCallback) {
    checkEventLoopExecuting();
    TimerStateMachine timer =
        TimerStateMachine.newInstance(
            attributes,
            (event) -> {
              completionCallback.apply(event);
              // Needed due to immediate cancellation
              if (event.getEventType() == EventType.EVENT_TYPE_TIMER_CANCELED) {
                eventLoop();
              }
            },
            commandSink,
            stateMachineSink);
    return timer::cancel;
  }

  /**
   * Creates a new child state machine
   *
   * @param parameters child workflow start command parameters.
   * @param startedCallback callback that is notified about child start
   * @param completionCallback invoked when child reports completion or failure.
   * @return cancellation callback that should be invoked to cancel the child
   */
  public Functions.Proc startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Functions.Proc1<WorkflowExecution> startedCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback) {
    checkEventLoopExecuting();
    StartChildWorkflowExecutionCommandAttributes attributes = parameters.getRequest().build();
    ChildWorkflowCancellationType cancellationType = parameters.getCancellationType();
    ChildWorkflowStateMachine child =
        ChildWorkflowStateMachine.newInstance(
            attributes, startedCallback, completionCallback, commandSink, stateMachineSink);
    return () -> {
      if (cancellationType == ChildWorkflowCancellationType.ABANDON) {
        notifyChildCanceled(completionCallback);
        return;
      }
      // The only time child can be canceled directly is before its start command
      // was sent out to the service. After that RequestCancelExternal should be used.
      if (child.isCancellable()) {
        child.cancel();
        return;
      }
      if (!child.isFinalState()) {
        requestCancelExternalWorkflowExecution(
            RequestCancelExternalWorkflowExecutionCommandAttributes.newBuilder()
                .setWorkflowId(attributes.getWorkflowId())
                .setNamespace(attributes.getNamespace())
                .setChildWorkflowOnly(true)
                .build(),
            (r, e) -> { // TODO(maxim): Decide what to do if an error is passed to the callback.
              if (cancellationType == ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED) {
                notifyChildCanceled(completionCallback);
              }
            });
        if (cancellationType == ChildWorkflowCancellationType.TRY_CANCEL) {
          notifyChildCanceled(completionCallback);
        }
      }
    };
  }

  private void notifyChildCanceled(
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback) {
    CanceledFailure failure =
        new CanceledFailure("Child canceled", new EncodedValues(Optional.empty()), null);
    completionCallback.apply(Optional.empty(), failure);
    eventLoop();
  }

  /**
   * @param attributes
   * @param completionCallback invoked when signal delivery completes of fails. The following types
   */
  public Functions.Proc signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes attributes,
      Functions.Proc2<Void, Failure> completionCallback) {
    checkEventLoopExecuting();
    return SignalExternalStateMachine.newInstance(
        attributes, completionCallback, commandSink, stateMachineSink);
  }

  /**
   * @param attributes attributes to use to cancel external workflow
   * @param completionCallback one of ExternalWorkflowExecutionCancelRequestedEvent,
   */
  public void requestCancelExternalWorkflowExecution(
      RequestCancelExternalWorkflowExecutionCommandAttributes attributes,
      Functions.Proc2<Void, RuntimeException> completionCallback) {
    checkEventLoopExecuting();
    CancelExternalStateMachine.newInstance(
        attributes, completionCallback, commandSink, stateMachineSink);
  }

  public void upsertSearchAttributes(SearchAttributes attributes) {
    checkEventLoopExecuting();
    UpsertSearchAttributesStateMachine.newInstance(attributes, commandSink, stateMachineSink);
  }

  public void completeWorkflow(Optional<Payloads> workflowOutput) {
    checkEventLoopExecuting();
    CompleteWorkflowStateMachine.newInstance(workflowOutput, commandSink, stateMachineSink);
  }

  public void failWorkflow(Failure failure) {
    checkEventLoopExecuting();
    FailWorkflowStateMachine.newInstance(failure, commandSink, stateMachineSink);
  }

  public void cancelWorkflow() {
    checkEventLoopExecuting();
    CancelWorkflowStateMachine.newInstance(
        CancelWorkflowExecutionCommandAttributes.getDefaultInstance(),
        commandSink,
        stateMachineSink);
  }

  public void continueAsNewWorkflow(ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    checkEventLoopExecuting();
    ContinueAsNewWorkflowStateMachine.newInstance(attributes, commandSink, stateMachineSink);
  }

  public boolean isReplaying() {
    return replaying;
  }

  public long currentTimeMillis() {
    return currentTimeMillis;
  }

  public UUID randomUUID() {
    checkEventLoopExecuting();
    String runId = currentRunId;
    if (runId == null) {
      throw new Error("null currentRunId");
    }
    String id = runId + ":" + idCounter++;
    byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
    return UUID.nameUUIDFromBytes(bytes);
  }

  public Random newRandom() {
    checkEventLoopExecuting();
    return new Random(randomUUID().getLeastSignificantBits());
  }

  public void sideEffect(
      Functions.Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback) {
    checkEventLoopExecuting();
    SideEffectStateMachine.newInstance(
        this::isReplaying,
        func,
        (payloads) -> {
          callback.apply(payloads);
          // callback unblocked sideEffect call. Give workflow code chance to make progress.
          eventLoop();
        },
        commandSink,
        stateMachineSink);
  }

  /**
   * @param id mutable side effect id
   * @param func given the value from the last marker returns value to store. If result is empty
   *     nothing is recorded into the history.
   * @param callback used to report result or failure
   */
  public void mutableSideEffect(
      String id,
      Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback) {
    checkEventLoopExecuting();
    MutableSideEffectStateMachine stateMachine =
        mutableSideEffects.computeIfAbsent(
            id,
            (idKey) ->
                MutableSideEffectStateMachine.newInstance(
                    idKey, this::isReplaying, commandSink, stateMachineSink));
    stateMachine.mutableSideEffect(
        func,
        (r) -> {
          callback.apply(r);
          // callback unblocked mutableSideEffect call. Give workflow code chance to make progress.
          eventLoop();
        },
        stateMachineSink);
  }

  public void getVersion(
      String changeId,
      int minSupported,
      int maxSupported,
      Functions.Proc2<Integer, RuntimeException> callback) {
    VersionStateMachine stateMachine =
        versions.computeIfAbsent(
            changeId,
            (idKey) ->
                VersionStateMachine.newInstance(
                    changeId, this::isReplaying, commandSink, stateMachineSink));
    stateMachine.getVersion(
        minSupported,
        maxSupported,
        (v, e) -> {
          callback.apply(v, e);
          // without this getVersion call will trigger the end of WFT,
          // instead we want to prepare subsequent commands and unblock the execution one more
          // time.
          eventLoop();
        });
  }

  public List<ExecuteLocalActivityParameters> takeLocalActivityRequests() {
    List<ExecuteLocalActivityParameters> result = localActivityRequests;
    localActivityRequests = new ArrayList<>();
    for (ExecuteLocalActivityParameters parameters : result) {
      LocalActivityStateMachine stateMachine =
          localActivityMap.get(parameters.getActivityTask().getActivityId());
      stateMachine.markAsSent();
    }
    return result;
  }

  public void handleLocalActivityCompletion(ActivityTaskHandler.Result laCompletion) {
    LocalActivityStateMachine commands = localActivityMap.get(laCompletion.getActivityId());
    if (commands == null) {
      throw new IllegalStateException("Unknown local activity: " + laCompletion.getActivityId());
    }
    commands.handleCompletion(laCompletion);
    prepareCommands();
  }

  public Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, Failure> callback) {
    checkEventLoopExecuting();
    String activityId = parameters.getActivityTask().getActivityId();
    if (Strings.isNullOrEmpty(activityId)) {
      throw new IllegalArgumentException("Missing activityId: " + activityId);
    }
    if (localActivityMap.containsKey(activityId)) {
      throw new IllegalArgumentException("Duplicated local activity id: " + activityId);
    }
    LocalActivityStateMachine commands =
        LocalActivityStateMachine.newInstance(
            this::isReplaying,
            this::setCurrentTimeMillis,
            parameters,
            (r, e) -> {
              callback.apply(r, e);
              // callback unblocked local activity call. Give workflow code chance to make progress.
              eventLoop();
            },
            localActivityRequestSink,
            commandSink,
            stateMachineSink,
            currentTimeMillis);
    localActivityMap.put(activityId, commands);
    return commands::cancel;
  }

  /** Validates that command matches the event during replay. */
  private void validateCommand(Command command, HistoryEvent event) {
    // TODO(maxim): Add more thorough validation logic. For example check if activity IDs are
    // matching.
    assertMatch(
        command,
        event,
        "eventType",
        getEventTypeForCommand(command.getCommandType()),
        event.getEventType());
    switch (command.getCommandType()) {
      case COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK:
        {
          ScheduleActivityTaskCommandAttributes commandAttributes =
              command.getScheduleActivityTaskCommandAttributes();
          ActivityTaskScheduledEventAttributes eventAttributes =
              event.getActivityTaskScheduledEventAttributes();
          assertMatch(
              command,
              event,
              "activityId",
              commandAttributes.getActivityId(),
              eventAttributes.getActivityId());
          assertMatch(
              command,
              event,
              "activityType",
              commandAttributes.getActivityType(),
              eventAttributes.getActivityType());
        }
        break;
      case COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION:
        {
          StartChildWorkflowExecutionCommandAttributes commandAttributes =
              command.getStartChildWorkflowExecutionCommandAttributes();
          StartChildWorkflowExecutionInitiatedEventAttributes eventAttributes =
              event.getStartChildWorkflowExecutionInitiatedEventAttributes();
          assertMatch(
              command,
              event,
              "workflowId",
              commandAttributes.getWorkflowId(),
              eventAttributes.getWorkflowId());
          assertMatch(
              command,
              event,
              "workflowType",
              commandAttributes.getWorkflowType(),
              eventAttributes.getWorkflowType());
        }
        break;
      case COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK:
      case COMMAND_TYPE_START_TIMER:
        {
          StartTimerCommandAttributes commandAttributes = command.getStartTimerCommandAttributes();
          TimerStartedEventAttributes eventAttributes = event.getTimerStartedEventAttributes();
          assertMatch(
              command,
              event,
              "timerId",
              commandAttributes.getTimerId(),
              eventAttributes.getTimerId());
        }
        break;
      case COMMAND_TYPE_CANCEL_TIMER:
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_RECORD_MARKER:
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
        break;
      case UNRECOGNIZED:
      case COMMAND_TYPE_UNSPECIFIED:
        throw new IllegalArgumentException("Unexpected command type: " + command.getCommandType());
    }
  }

  private void assertMatch(
      Command command, HistoryEvent event, String checkType, Object expected, Object actual) {
    if (!expected.equals(actual)) {
      String message =
          String.format(
              "Command %s doesn't match event %s with EventId=%s on check %s "
                  + "with an expected value %s and an actual value %s",
              command.getCommandType(),
              event.getEventType(),
              event.getEventId(),
              checkType,
              expected,
              actual);
      throw new NonDeterministicException(message);
    }
  }

  private class WorkflowTaskCommandsListener implements WorkflowTaskStateMachine.Listener {
    @Override
    public void workflowTaskStarted(
        long startedEventId, long currentTimeMillis, boolean nonProcessedWorkflowTask) {
      setCurrentTimeMillis(currentTimeMillis);
      for (CancellableCommand cancellableCommand : commands) {
        cancellableCommand.handleWorkflowTaskStarted();
      }
      // Give local activities a chance to recreate their requests if they were lost due
      // to the last workflow task failure. The loss could happen only the last workflow task
      // was forcibly created by setting forceCreate on RespondWorkflowTaskCompletedRequest.
      if (nonProcessedWorkflowTask) {
        for (LocalActivityStateMachine value : localActivityMap.values()) {
          value.nonReplayWorkflowTaskStarted();
        }
      }

      WorkflowStateMachines.this.currentStartedEventId = startedEventId;

      eventLoop();
    }

    @Override
    public void updateRunId(String currentRunId) {
      WorkflowStateMachines.this.currentRunId = currentRunId;
    }
  }

  private long getInitialCommandEventId(HistoryEvent event) {
    switch (event.getEventType()) {
      case EVENT_TYPE_ACTIVITY_TASK_STARTED:
        return event.getActivityTaskStartedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
        return event.getActivityTaskCompletedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_ACTIVITY_TASK_FAILED:
        return event.getActivityTaskFailedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
        return event.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
        return event.getActivityTaskCancelRequestedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
        return event.getActivityTaskCanceledEventAttributes().getScheduledEventId();
      case EVENT_TYPE_TIMER_FIRED:
        return event.getTimerFiredEventAttributes().getStartedEventId();
      case EVENT_TYPE_TIMER_CANCELED:
        return event.getTimerCanceledEventAttributes().getStartedEventId();
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        return event
            .getRequestCancelExternalWorkflowExecutionFailedEventAttributes()
            .getInitiatedEventId();
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        return event
            .getExternalWorkflowExecutionCancelRequestedEventAttributes()
            .getInitiatedEventId();
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
        return event.getStartChildWorkflowExecutionFailedEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
        return event.getChildWorkflowExecutionStartedEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
        return event.getChildWorkflowExecutionCompletedEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
        return event.getChildWorkflowExecutionFailedEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
        return event.getChildWorkflowExecutionCanceledEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
        return event.getChildWorkflowExecutionTimedOutEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
        return event.getChildWorkflowExecutionTerminatedEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        return event
            .getSignalExternalWorkflowExecutionFailedEventAttributes()
            .getInitiatedEventId();
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED:
        return event.getExternalWorkflowExecutionSignaledEventAttributes().getInitiatedEventId();
      case EVENT_TYPE_WORKFLOW_TASK_STARTED:
        return event.getWorkflowTaskStartedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        return event.getWorkflowTaskCompletedEventAttributes().getScheduledEventId();
      case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
        return event.getWorkflowTaskTimedOutEventAttributes().getScheduledEventId();
      case EVENT_TYPE_WORKFLOW_TASK_FAILED:
        return event.getWorkflowTaskFailedEventAttributes().getScheduledEventId();

      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
      case EVENT_TYPE_TIMER_STARTED:
      case EVENT_TYPE_MARKER_RECORDED:
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
      case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        return event.getEventId();
      case UNRECOGNIZED:
      case EVENT_TYPE_UNSPECIFIED:
        throw new IllegalArgumentException("Unexpected event type: " + event.getEventType());
    }
    throw new IllegalStateException("unreachable");
  }

  /**
   * Workflow code executes only while event loop is running. So operations that can be invoked from
   * the workflow have to satisfy this condition.
   */
  private void checkEventLoopExecuting() {
    if (!eventLoopExecuting) {
      // this call doesn't yield or await, because the await function returns true,
      // but it checks if the workflow thread needs to be destroyed
      WorkflowThread.await("kill workflow thread if destroy requested", () -> true);
      throw new IllegalStateException("Operation allowed only while eventLoop is running");
    }
  }

  private String createEventHandlingMessage(HistoryEvent event) {
    return "Failure handling event "
        + event.getEventId()
        + " of type '"
        + event.getEventType()
        + "' "
        + (this.isReplaying() ? "during replay" : "during execution");
  }

  private String createShortCurrentStateMessagePostfix() {
    return String.format(
        "{PreviousStartedEventId=%s, WorkflowTaskStartedEventId=%s, CurrentStartedEventId=%s}",
        this.previousStartedEventId, this.workflowTaskStartedEventId, this.currentStartedEventId);
  }
}
