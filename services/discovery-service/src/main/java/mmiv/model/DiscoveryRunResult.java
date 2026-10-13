package mmiv.model;

public class DiscoveryRunResult {

  private final MigrationFileInventory inventory;
  private final int insertedCount;

  public DiscoveryRunResult(MigrationFileInventory inventory, int insertedCount) {

    this.inventory = inventory;
    this.insertedCount = insertedCount;
  }

  public MigrationFileInventory getInventory() {
    return inventory;
  }

  public int getInsertedCount() {
    return insertedCount;
  }
}
