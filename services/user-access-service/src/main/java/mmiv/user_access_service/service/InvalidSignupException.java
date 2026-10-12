package mmiv.user_access_service.service;

public class InvalidSignupException extends RuntimeException {
  public InvalidSignupException() {
    super("Use a 3–40 character username and an 8–72 byte password");
  }
}
