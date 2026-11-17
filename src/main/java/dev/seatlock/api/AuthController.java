package dev.seatlock.api;

import dev.seatlock.api.Models.*;
import dev.seatlock.security.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthView register(@Valid @RequestBody RegisterRequest request) { return auth.register(request); }
    @PostMapping("/login")
    public AuthView login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt token) { return auth.me(UUID.fromString(token.getSubject())); }
}
