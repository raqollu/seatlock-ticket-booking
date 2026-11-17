package dev.seatlock.api;

import dev.seatlock.api.Models.*;
import dev.seatlock.booking.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookings;
    public BookingController(BookingService bookings) { this.bookings = bookings; }
    @PostMapping("/holds")
    public ResponseEntity<BookingView> hold(@AuthenticationPrincipal Jwt token,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody HoldRequest request) {
        HoldResult result = bookings.hold(user(token), key, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/bookings/" + result.booking().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.booking());
    }
    @GetMapping public List<BookingView> list(@AuthenticationPrincipal Jwt token) { return bookings.list(user(token)); }
    @GetMapping("/{id}") public BookingView get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) { return bookings.get(user(token), id); }
    @PostMapping("/{id}/confirm") public BookingView confirm(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) { return bookings.confirm(user(token), id); }
    @PostMapping("/{id}/cancel") public BookingView cancel(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) { return bookings.cancel(user(token), id); }
    private UUID user(Jwt token) { return UUID.fromString(token.getSubject()); }
}
