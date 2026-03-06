package core.spi;

import core.model.ContextFrame;
import java.util.List;

public interface ContextCache {
  void insert(String contextId, ContextFrame frame, long retentionTimeInMs);

  List<ContextFrame> getList(String contextId);

  void clearList(String contextId);
}
