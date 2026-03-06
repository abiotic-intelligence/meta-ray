package core.model.wire;

public interface SchemaRegistry {
  void validate(WireMessage<?> message);
}
