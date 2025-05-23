package io.temporal.internal.client;

import static io.temporal.internal.common.HeaderUtils.intoPayloadMap;

import com.google.common.collect.Iterators;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.schedule.v1.*;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.ListScheduleListDescriptionIterator;
import io.temporal.client.schedules.*;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.*;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RootScheduleClientInvoker implements ScheduleClientCallsInterceptor {
  private static final Logger log = LoggerFactory.getLogger(RootScheduleClientInvoker.class);

  private final GenericWorkflowClient genericClient;

  private final ScheduleClientOptions clientOptions;

  private final ScheduleProtoUtil scheduleRequestHeader;

  public RootScheduleClientInvoker(
      GenericWorkflowClient genericClient, ScheduleClientOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
    this.scheduleRequestHeader = new ScheduleProtoUtil(genericClient, clientOptions);
  }

  @Override
  @SuppressWarnings("deprecation")
  public void createSchedule(CreateScheduleInput input) {

    CreateScheduleRequest.Builder request =
        CreateScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(UUID.randomUUID().toString())
            .setScheduleId(input.getId())
            .setSchedule(scheduleRequestHeader.scheduleToProto(input.getSchedule()));

    if (input.getOptions().getMemo() != null) {
      // TODO we don't have a workflow context here, maybe we need a schedule context?
      request.setMemo(
          Memo.newBuilder()
              .putAllFields(
                  intoPayloadMap(clientOptions.getDataConverter(), input.getOptions().getMemo())));
    }

    if (input.getOptions().getSearchAttributes() != null
        && !input.getOptions().getSearchAttributes().isEmpty()) {
      if (input.getOptions().getTypedSearchAttributes() != null) {
        throw new IllegalArgumentException(
            "Cannot have search attributes and typed search attributes");
      }
      request.setSearchAttributes(
          SearchAttributesUtil.encode(input.getOptions().getSearchAttributes()));
    } else if (input.getOptions().getTypedSearchAttributes() != null) {
      request.setSearchAttributes(
          SearchAttributesUtil.encodeTyped(input.getOptions().getTypedSearchAttributes()));
    }

    if (input.getOptions().isTriggerImmediately()
        || (input.getOptions().getBackfills() != null
            && input.getOptions().getBackfills().size() > 0)) {
      SchedulePatch.Builder patchBuilder = SchedulePatch.newBuilder();

      if (input.getOptions().getBackfills() != null) {
        input.getOptions().getBackfills().stream()
            .forEach(b -> patchBuilder.addBackfillRequest(backfillToProto(b)));
      }

      if (input.getOptions().isTriggerImmediately()) {
        TriggerImmediatelyRequest.Builder triggerRequest = TriggerImmediatelyRequest.newBuilder();
        if (input.getSchedule().getPolicy() != null) {
          triggerRequest.setOverlapPolicy(input.getSchedule().getPolicy().getOverlap());
        }
        patchBuilder.setTriggerImmediately(triggerRequest.build());
      }

      request.setInitialPatch(patchBuilder.build());
    }

    try {
      genericClient.createSchedule(request.build());
    } catch (StatusRuntimeException e) {
      if (Status.Code.ALREADY_EXISTS.equals(e.getStatus().getCode())) {
        throw new ScheduleAlreadyRunningException(e);
      } else {
        throw new ScheduleException(e);
      }
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public ListScheduleOutput listSchedules(ListSchedulesInput input) {
    ListScheduleListDescriptionIterator iterator =
        new ListScheduleListDescriptionIterator(
            clientOptions.getNamespace(), input.getQuery(), input.getPageSize(), genericClient);
    iterator.init();
    Iterator<ScheduleListDescription> wrappedIterator =
        Iterators.transform(
            iterator, entry -> scheduleRequestHeader.protoToScheduleListDescription(entry));

    final int CHARACTERISTICS =
        Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    return new ListScheduleOutput(
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(wrappedIterator, CHARACTERISTICS), false));
  }

  public BackfillRequest backfillToProto(ScheduleBackfill backfill) {
    return BackfillRequest.newBuilder()
        .setStartTime(ProtobufTimeUtils.toProtoTimestamp(backfill.getStartAt()))
        .setEndTime(ProtobufTimeUtils.toProtoTimestamp(backfill.getEndAt()))
        .setOverlapPolicy(backfill.getOverlapPolicy())
        .build();
  }

  @Override
  public void backfillSchedule(BackfillScheduleInput input) {
    ArrayList<BackfillRequest> backfillRequests =
        new ArrayList<BackfillRequest>(input.getBackfills().size());
    for (ScheduleBackfill backfill : input.getBackfills()) {
      backfillRequests.add(backfillToProto(backfill));
    }

    SchedulePatch patch =
        SchedulePatch.newBuilder().addAllBackfillRequest(backfillRequests).build();

    PatchScheduleRequest request =
        PatchScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .setPatch(patch)
            .build();
    try {
      genericClient.patchSchedule(request);
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public void deleteSchedule(DeleteScheduleInput input) {
    DeleteScheduleRequest request =
        DeleteScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .build();
    try {
      genericClient.deleteSchedule(request);
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public DescribeScheduleOutput describeSchedule(DescribeScheduleInput input) {
    DescribeScheduleRequest request =
        DescribeScheduleRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .build();

    try {
      DescribeScheduleResponse response = genericClient.describeSchedule(request);
      return new DescribeScheduleOutput(
          new ScheduleDescription(
              input.getScheduleId(),
              scheduleRequestHeader.protoToScheduleInfo(response.getInfo()),
              scheduleRequestHeader.protoToSchedule(response.getSchedule()),
              Collections.unmodifiableMap(
                  SearchAttributesUtil.decode(response.getSearchAttributes())),
              SearchAttributesUtil.decodeTyped(response.getSearchAttributes()),
              response.getMemo().getFieldsMap(),
              clientOptions.getDataConverter()));
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public void pauseSchedule(PauseScheduleInput input) {
    SchedulePatch patch = SchedulePatch.newBuilder().setPause(input.getNote()).build();

    PatchScheduleRequest request =
        PatchScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .setPatch(patch)
            .build();
    try {
      genericClient.patchSchedule(request);
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public void triggerSchedule(TriggerScheduleInput input) {
    TriggerImmediatelyRequest trigger =
        TriggerImmediatelyRequest.newBuilder().setOverlapPolicy(input.getOverlapPolicy()).build();

    SchedulePatch patch = SchedulePatch.newBuilder().setTriggerImmediately(trigger).build();

    PatchScheduleRequest request =
        PatchScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .setPatch(patch)
            .build();
    try {
      genericClient.patchSchedule(request);
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public void unpauseSchedule(UnpauseScheduleInput input) {
    SchedulePatch patch = SchedulePatch.newBuilder().setUnpause(input.getNote()).build();

    PatchScheduleRequest request =
        PatchScheduleRequest.newBuilder()
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setScheduleId(input.getScheduleId())
            .setPatch(patch)
            .build();
    try {
      genericClient.patchSchedule(request);
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }

  @Override
  public void updateSchedule(UpdateScheduleInput input) {
    ScheduleUpdate schedule =
        input.getUpdater().apply(new ScheduleUpdateInput(input.getDescription()));
    if (schedule == null) {
      return;
    }

    UpdateScheduleRequest.Builder request =
        UpdateScheduleRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setScheduleId(input.getDescription().getId())
            .setRequestId(UUID.randomUUID().toString())
            .setSchedule(scheduleRequestHeader.scheduleToProto(schedule.getSchedule()));
    if (schedule.getTypedSearchAttributes() != null) {
      SearchAttributes encodedSa =
          SearchAttributesUtil.encodeTyped(schedule.getTypedSearchAttributes());
      if (encodedSa == null) {
        encodedSa = SearchAttributes.getDefaultInstance();
      }
      request.setSearchAttributes(encodedSa);
    }
    try {
      genericClient.updateSchedule(request.build());
    } catch (Exception e) {
      throw new ScheduleException(e);
    }
  }
}
