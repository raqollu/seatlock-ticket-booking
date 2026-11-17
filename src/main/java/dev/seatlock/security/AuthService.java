package dev.seatlock.security;

import dev.seatlock.api.ApiException;
import dev.seatlock.api.Models.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final String dummyHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder tokens) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("dummy-password-for-timing");
    }
    public AuthView register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.invalid("Password must be at most 72 UTF-8 bytes");
        }
        UUID id = UUID.randomUUID();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        int inserted = jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash) VALUES (?,?,?,?) ON CONFLICT (email) DO NOTHING",
                id, email, request.displayName().trim(), passwords.encode(request.password()));
        if (inserted == 0) throw ApiException.conflict("EMAIL_EXISTS", "An account with this email already exists");
        return issue(me(id));
    }
    public AuthView login(LoginRequest request) {
        var users = jdbc.query("SELECT * FROM app_user WHERE email = ?", (rs, row) -> new Account(
                new UserView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("role")),
                rs.getString("password_hash")), request.email().trim().toLowerCase(Locale.ROOT));
        String hash = users.isEmpty() ? dummyHash : users.getFirst().hash();
        boolean oversized = request.password().getBytes(StandardCharsets.UTF_8).length > 72;
        boolean valid = passwords.matches(oversized ? "invalid-oversized-password" : request.password(), hash);
        if (!valid || users.isEmpty() || oversized) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        }
        return issue(users.getFirst().user());
    }
    public UserView me(UUID id) {
        return jdbc.query("SELECT id,email,display_name,role FROM app_user WHERE id = ?", (rs, row) ->
                new UserView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("role")), id)
                .stream().findFirst().orElseThrow(() -> ApiException.missing("Account"));
    }
    private AuthView issue(UserView user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER).audience(List.of(SecurityConfig.AUDIENCE))
                .subject(user.id().toString()).issuedAt(now).expiresAt(now.plusSeconds(3600))
                .claim("role", user.role()).build();
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthView(token, "Bearer", 3600, user);
    }
    private record Account(UserView user, String hash) {}
}
