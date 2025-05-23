package io.temporal.common.converter;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import io.temporal.api.common.v1.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractProtobufPayloadConverter {
  protected static final Logger log =
      LoggerFactory.getLogger(AbstractProtobufPayloadConverter.class);

  private final boolean excludeProtobufMessageTypes;

  protected AbstractProtobufPayloadConverter() {
    this.excludeProtobufMessageTypes = false;
  }

  protected AbstractProtobufPayloadConverter(boolean excludeProtobufMessageTypes) {
    this.excludeProtobufMessageTypes = excludeProtobufMessageTypes;
  }

  protected void addMessageType(Payload.Builder builder, Object value) {
    if (this.excludeProtobufMessageTypes) {
      return;
    }
    String messageTypeName = ((MessageOrBuilder) value).getDescriptorForType().getFullName();
    builder.putMetadata(
        EncodingKeys.METADATA_MESSAGE_TYPE_KEY, ByteString.copyFrom(messageTypeName, UTF_8));
  }

  protected void checkMessageType(Payload payload, Object instance) {
    if (!log.isWarnEnabled()) {
      return;
    }

    ByteString messageTypeBytes =
        payload.getMetadataMap().get(EncodingKeys.METADATA_MESSAGE_TYPE_KEY);
    if (messageTypeBytes != null) {
      String messageType = messageTypeBytes.toString(UTF_8);
      String instanceType = ((MessageOrBuilder) instance).getDescriptorForType().getFullName();

      if (!messageType.equals(instanceType)) {
        log.warn(
            "Encoded protobuf message type \""
                + messageType
                + "\" does not match value type \""
                + instanceType
                + '"');
      }
    }
  }
}
