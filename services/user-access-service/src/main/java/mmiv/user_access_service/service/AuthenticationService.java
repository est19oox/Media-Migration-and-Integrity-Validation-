package mmiv.user_access_service.service;

import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User authenticate(String username, String password) {
        if (username == null
                || username.isBlank()
                || password == null
                || password.isBlank()) {
            throw new InvalidCredentialsException();
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        String passwordHash = user.getPasswordHash();
        if (!user.isActive()
                || passwordHash == null
                || passwordHash.isBlank()
                || !passwordEncoder.matches(password, passwordHash)) {
            throw new InvalidCredentialsException();
        }

        return user;
    }
}
