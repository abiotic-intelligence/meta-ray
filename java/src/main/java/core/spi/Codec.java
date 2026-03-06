package core.spi;

public interface Codec {
  byte[] encode(Object message);
  <T> T decode(byte[] bytes, Class<T> type);
}
