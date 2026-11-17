package dev.seatlock.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}
    public record UserView(UUID id, String email, String displayName, String role) {}
    public record AuthView(String accessToken, String tokenType, long expiresIn, UserView user) {}
    public record EventRequest(@NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 120) String venue, @NotBlank @Size(max = 80) String city,
            @NotNull @Future Instant startsAt, @Min(1) @Max(100_000_000) int pricePaise,
            @Min(1) @Max(26) int rows, @Min(1) @Max(30) int columns) {}
    public record EventView(UUID id, String title, String venue, String city, Instant startsAt,
            int pricePaise, int rows, int columns, int availableSeats, int totalSeats) {}
    public record SeatView(String code, String row, int column, String status) {}
    public record HoldRequest(@NotNull UUID eventId,
            @NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = "[A-Z]([1-9]|[12][0-9]|30)") String> seats) {}
    public record BookingView(UUID id, UUID eventId, String eventTitle, String venue, String city,
            Instant startsAt, List<String> seats, String status, int totalPaise,
            Instant createdAt, Instant expiresAt, Instant confirmedAt) {}
    public record HoldResult(BookingView booking, boolean replayed) {}
}
