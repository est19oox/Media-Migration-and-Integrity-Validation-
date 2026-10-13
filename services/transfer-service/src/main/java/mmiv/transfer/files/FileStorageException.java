package mmiv.transfer.files;

public class FileStorageException extends RuntimeException {
  private final int status;

  public FileStorageException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
