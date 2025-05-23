package io.temporal.client.schedules;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DataConverter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Description of a schedule. */
public final class ScheduleDescription {
  private final String id;
  private final ScheduleInfo info;
  private final Schedule schedule;
  private final Map<String, List<?>> searchAttributes;
  private final SearchAttributes typedSearchAttributes;
  private final Map<String, Payload> memo;
  private final DataConverter dataConverter;

  public ScheduleDescription(
      String id,
      ScheduleInfo info,
      Schedule schedule,
      Map<String, List<?>> searchAttributes,
      SearchAttributes typedSearchAttributes,
      Map<String, Payload> memo,
      DataConverter dataConverter) {
    this.id = id;
    this.info = info;
    this.schedule = schedule;
    this.searchAttributes = searchAttributes;
    this.typedSearchAttributes = typedSearchAttributes;
    this.memo = memo;
    this.dataConverter = dataConverter;
  }

  /**
   * Get the ID of the schedule.
   *
   * @return schedule ID
   */
  public @Nonnull String getId() {
    return id;
  }

  /**
   * Get information about the schedule.
   *
   * @return schedule info
   */
  public @Nonnull ScheduleInfo getInfo() {
    return info;
  }

  /**
   * Gets the schedule details.
   *
   * @return schedule details
   */
  public @Nonnull Schedule getSchedule() {
    return schedule;
  }

  /**
   * Gets the search attributes on the schedule.
   *
   * @return search attributes
   * @deprecated use {@link ScheduleDescription#getTypedSearchAttributes} instead.
   */
  @Nonnull
  public Map<String, List<?>> getSearchAttributes() {
    return searchAttributes;
  }

  /**
   * Gets the search attributes on the schedule.
   *
   * @return search attributes
   */
  @Nonnull
  public SearchAttributes getTypedSearchAttributes() {
    return typedSearchAttributes;
  }

  @Nullable
  public <T> Object getMemo(String key, Class<T> valueClass) {
    return getMemo(key, valueClass, valueClass);
  }

  @Nullable
  public <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memoPayload = memo.get(key);
    if (memoPayload == null) {
      return null;
    }
    return dataConverter.fromPayload(memoPayload, valueClass, genericType);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleDescription that = (ScheduleDescription) o;
    return Objects.equals(id, that.id)
        && Objects.equals(info, that.info)
        && Objects.equals(schedule, that.schedule)
        && Objects.equals(searchAttributes, that.searchAttributes)
        && Objects.equals(typedSearchAttributes, that.typedSearchAttributes)
        && Objects.equals(memo, that.memo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, info, schedule, searchAttributes, typedSearchAttributes, memo);
  }

  @Override
  public String toString() {
    return "ScheduleDescription{"
        + "id='"
        + id
        + '\''
        + ", info="
        + info
        + ", schedule="
        + schedule
        + ", searchAttributes="
        + searchAttributes
        + ", typedSearchAttributes="
        + typedSearchAttributes
        + ", memo="
        + memo
        + '}';
  }
}
