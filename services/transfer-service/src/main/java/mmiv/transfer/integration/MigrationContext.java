package mmiv.transfer.integration;

/** Correlation belongs to this synchronous request and is always cleared in a finally block. */
public final class MigrationContext {
  private static final ThreadLocal<MetadataClient.Plan> CURRENT = new ThreadLocal<>();

  private MigrationContext() {}

  public static MetadataClient.Plan get() {
    return CURRENT.get();
  }

  public static void set(MetadataClient.Plan plan) {
    CURRENT.set(plan);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
