package mmiv.transfer.domain;

import java.util.UUID;

/**
 * Existing stored-file aggregate identity and deletion policy. Metadata owns its uploader
 * assignment.
 */
public record StoredFile(String key) {
  public String folder() {
    int slash = key.indexOf('/');
    if (slash < 1) throw new IllegalArgumentException("No access to folder");
    return key.substring(0, slash);
  }

  public UUID migrationId() {
    var match =
        java.util.regex.Pattern.compile("^[^/]+/migrations/([0-9a-fA-F-]{36})/").matcher(key);
    try {
      return match.find() ? UUID.fromString(match.group(1)) : null;
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  public boolean canDelete(boolean administrator, String authorizedUploaderKey) {
    return administrator || migrationId() != null && key.equals(authorizedUploaderKey);
  }
}
