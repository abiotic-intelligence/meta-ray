package io.metaray.android.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import core.spi.Codec;
import java.io.IOException;
import java.util.Objects;

public final class JacksonJsonCodec implements Codec {
  private final ObjectMapper mapper;

  public JacksonJsonCodec() {
    this(defaultMapper());
  }

  public JacksonJsonCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public byte[] encode(Object message) {
    Objects.requireNonNull(message, "message must not be null");
    try {
      return mapper.writeValueAsBytes(message);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to encode message as JSON", e);
    }
  }

  @Override
  public <T> T decode(byte[] bytes, Class<T> type) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(type, "type must not be null");
    try {
      return mapper.readValue(bytes, type);
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to decode JSON payload", e);
    }
  }

  private static ObjectMapper defaultMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
    mapper.configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true);
    mapper.registerModule(new ParameterNamesModule());
    return mapper;
  }
}
