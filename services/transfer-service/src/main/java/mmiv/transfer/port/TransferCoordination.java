package mmiv.transfer.port;

import java.util.UUID;

/** Transfer-owned coordination contract for operations that span service replicas. */
public interface TransferCoordination {
  Scope folder(String folder);

  VerificationScope verificationRun(String owner, UUID run);

  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  interface VerificationScope extends Scope {
    boolean isClosed();

    void closeForCleanup();
  }
}
