package mmiv.integration;

import java.util.List;

public record BatchAllocationRequest(
    String destinationId, List<String> occupiedPaths, List<FileItem> files) {

  public record FileItem(String fileName, Long sizeBytes, String relativePath) {}
}
