package mmiv.user_access_service.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import mmiv.user_access_service.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final JwtEncoder encoder;
  private final String issuer;

  public JwtService(JwtEncoder encoder, @Value("${jwt.issuer}") String issuer) {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("JWT issuer must not be blank");
    }
    this.encoder = encoder;
    this.issuer = issuer;
  }

  public String generateToken(User user) {
    if (user == null || !user.isActive()) {
      throw new IllegalArgumentException("Cannot issue token for inactive or missing user");
    }

    Instant issuedAt = Instant.now();
    List<String> permissions = user.getPermissions().stream().map(Enum::name).sorted().toList();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(user.getUserId())
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(30, ChronoUnit.MINUTES))
            .claim("username", user.getUsername())
            .claim("role", user.getRole())
            .claim("permissions", permissions)
            .build();

    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
