package mmiv.transfer.files;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable key identifier, not an authorization token or a database record. */
public final class FileIds {
    private FileIds() {}
    public static String encode(String key) {
        validateKey(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
    public static String decode(String id) {
        if (id == null || id.length() > 1400 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid fileId");
        }
        try {
            String key = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            validateKey(key);
            if (!encode(key).equals(id)) throw new IllegalArgumentException("Invalid fileId");
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid fileId");
        }
    }
    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")
                || key.getBytes(StandardCharsets.UTF_8).length > 1024
                || key.chars().anyMatch(Character::isISOControl) || key.contains("\\")
                || java.util.Arrays.stream(key.split("/",-1)).anyMatch(part->part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Invalid file key");
        }
    }
}
