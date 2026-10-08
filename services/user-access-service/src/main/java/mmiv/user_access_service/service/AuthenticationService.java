package mmiv.user_access_service.service;

import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthenticationService(
            UserRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public String login(String username, String password) {

        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new InvalidCredentialsException();
        }

        User user = repository.findByUsername(username)
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.isActive()
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(
                        password,
                        user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return jwtService.generateToken(user);
    }
}