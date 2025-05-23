package io.temporal.common.converter;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

public final class ProtobufJsonPayloadConverter extends AbstractProtobufPayloadConverter
    implements PayloadConverter {

  private final JsonFormat.Printer printer;
  private final JsonFormat.Parser parser;

  public ProtobufJsonPayloadConverter() {
    this(JsonFormat.printer(), JsonFormat.parser().ignoringUnknownFields(), false);
  }

  public ProtobufJsonPayloadConverter(boolean excludeProtobufMessageTypes) {
    this(
        JsonFormat.printer(),
        JsonFormat.parser().ignoringUnknownFields(),
        excludeProtobufMessageTypes);
  }

  public ProtobufJsonPayloadConverter(JsonFormat.Printer printer, JsonFormat.Parser parser) {
    this(printer, parser, false);
  }

  public ProtobufJsonPayloadConverter(
      JsonFormat.Printer printer, JsonFormat.Parser parser, boolean excludeProtobufMessageTypes) {
    super(excludeProtobufMessageTypes);
    this.printer = Objects.requireNonNull(printer);
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_PROTOBUF_JSON_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (!(value instanceof MessageOrBuilder)) {
      return Optional.empty();
    }

    try {
      String data = printer.print((MessageOrBuilder) value);
      Payload.Builder builder =
          Payload.newBuilder()
              .putMetadata(
                  EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_PROTOBUF_JSON)
              .setData(ByteString.copyFrom(data, UTF_8));
      super.addMessageType(builder, value);
      return Optional.of(builder.build());
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ByteString data = content.getData();
    if (!MessageOrBuilder.class.isAssignableFrom(valueClass)) {
      throw new IllegalArgumentException("Not a protobuf. valueClass=" + valueClass.getName());
    }
    try {
      Method toBuilder = valueClass.getMethod("newBuilder");
      Message.Builder builder = (Message.Builder) toBuilder.invoke(null);
      parser.merge(data.toString(UTF_8), builder);
      Message instance = builder.build();
      super.checkMessageType(content, instance);
      return (T) instance;
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }
}
