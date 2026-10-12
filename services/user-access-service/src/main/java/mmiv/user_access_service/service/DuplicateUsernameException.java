package mmiv.user_access_service.service;

public class DuplicateUsernameException extends RuntimeException {
  public DuplicateUsernameException() {
    super("Username is already taken");
  }
}
