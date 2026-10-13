package mmiv.transfer.files;

public class FileRangeException extends RuntimeException {
  private final long size;

  public FileRangeException(long size) {
    super("Requested byte range is not satisfiable");
    this.size = size;
  }

  public long size() {
    return size;
  }
}
