package dev.seatlock.booking;

import dev.seatlock.api.ApiException;
import dev.seatlock.api.Models.*;
import dev.seatlock.catalog.CatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CatalogService catalog;

    public BookingService(JdbcTemplate jdbc, CatalogService catalog) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.catalog = catalog;
    }

    /** The idempotency claim, seat locks, validation and assignment commit atomically. */
    @Transactional
    public HoldResult hold(UUID userId, String key, HoldRequest request) {
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) throw ApiException.invalid("Idempotency-Key must contain 1-128 letters, digits, '.', '_', ':' or '-'");
        var codes = request.seats().stream().distinct().sorted().toList();
        if (codes.size() != request.seats().size()) throw ApiException.invalid("Duplicate seat codes are not allowed");
        String hash = fingerprint(request.eventId() + "|" + String.join(",", codes));
        EventView event = catalog.get(request.eventId());
        UUID id = UUID.randomUUID();
        // A concurrent INSERT of the same (user,key) waits for its winner to commit.
        int inserted = jdbc.update("""
            INSERT INTO booking(id,user_id,event_id,status,idempotency_key,request_hash,total_paise,expires_at)
            VALUES (?,?,?,'HELD',?,?,?,clock_timestamp() + interval '15 minutes')
            ON CONFLICT (user_id,idempotency_key) DO NOTHING
            """, id, userId, event.id(), key, hash, Math.multiplyExact(event.pricePaise(), codes.size()));
        if (inserted == 0) {
            var prior = jdbc.queryForMap("SELECT id,request_hash FROM booking WHERE user_id = ? AND idempotency_key = ?", userId, key);
            if (!hash.equals(prior.get("request_hash"))) throw ApiException.conflict("IDEMPOTENCY_MISMATCH", "This key was already used with a different event or seat selection");
            // Resource idempotency: replay returns this booking's CURRENT lifecycle state.
            return new HoldResult(get(userId, (UUID) prior.get("id")), true);
        }

        var seats = lockSeats(event.id(), codes);
        if (seats.size() != codes.size()) throw ApiException.invalid("One or more seat codes do not exist for this event");
        Instant now = databaseNow(); // Read AFTER waiting for every seat lock.
        if (!event.startsAt().isAfter(now)) throw ApiException.conflict("EVENT_STARTED", "Booking has closed for this event");
        for (InventorySeat seat : seats) {
            boolean expired = seat.status().equals("HELD") && !seat.holdUntil().isAfter(now);
            if (!seat.status().equals("AVAILABLE") && !expired) {
                throw ApiException.conflict("SEAT_UNAVAILABLE", "Seat " + seat.code() + " is no longer available");
            }
        }
        Instant expires = now.plusSeconds(900).isBefore(event.startsAt()) ? now.plusSeconds(900) : event.startsAt();
        jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", Timestamp.from(expires), id);
        for (String code : codes) {
            jdbc.update("INSERT INTO booking_seat(booking_id,event_id,seat_code) VALUES (?,?,?)", id, event.id(), code);
            jdbc.update("UPDATE seat_inventory SET status = 'HELD', booking_id = ?, hold_until = ? WHERE event_id = ? AND seat_code = ?",
                    id, Timestamp.from(expires), event.id(), code);
        }
        return new HoldResult(get(userId, id), false);
    }

    @Transactional
    public BookingView confirm(UUID userId, UUID id) {
        BookingRow booking = lockBooking(id, userId);
        if (booking.status().equals("CONFIRMED")) return get(userId, id);
        var seats = lockSeats(booking.eventId(), selection(id));
        requireLiveHold(booking, databaseNow());
        if (seats.stream().anyMatch(s -> !id.equals(s.bookingId()) || !s.status().equals("HELD"))) {
            throw ApiException.conflict("LOST_HOLD", "The booking no longer owns all selected seats");
        }
        jdbc.update("UPDATE seat_inventory SET status = 'BOOKED', hold_until = NULL WHERE booking_id = ?", id);
        jdbc.update("UPDATE booking SET status = 'CONFIRMED', confirmed_at = clock_timestamp() WHERE id = ?", id);
        return get(userId, id);
    }

    @Transactional
    public BookingView cancel(UUID userId, UUID id) {
        BookingRow booking = lockBooking(id, userId);
        if (booking.status().equals("CANCELLED")) return get(userId, id);
        lockSeats(booking.eventId(), selection(id));
        if (booking.status().equals("EXPIRED") || (booking.status().equals("HELD") && !booking.expiresAt().isAfter(databaseNow()))) {
            throw expired();
        }
        releaseOwnedSeats(id);
        jdbc.update("UPDATE booking SET status = 'CANCELLED' WHERE id = ?", id);
        return get(userId, id);
    }

    public BookingView get(UUID userId, UUID id) {
        return jdbc.query(VIEW_QUERY + " WHERE b.user_id = ? AND b.id = ?", viewMapper, userId, id)
                .stream().findFirst().orElseThrow(() -> ApiException.missing("Booking"));
    }
    public List<BookingView> list(UUID userId) {
        return jdbc.query(VIEW_QUERY + " WHERE b.user_id = ? ORDER BY b.created_at DESC,b.id LIMIT 100", viewMapper, userId);
    }
    public List<UUID> expiredCandidates() {
        return jdbc.query("SELECT id FROM booking WHERE status = 'HELD' AND expires_at <= clock_timestamp() ORDER BY expires_at,id LIMIT 100",
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    /** One transaction per booking; safe when several app instances run the sweeper. */
    @Transactional
    public void expire(UUID id) {
        BookingRow booking = lockBooking(id, null);
        if (!booking.status().equals("HELD")) return;
        lockSeats(booking.eventId(), selection(id));
        if (booking.expiresAt().isAfter(databaseNow())) return;
        // Never clear a seat that has already been reassigned to a newer booking.
        releaseOwnedSeats(id);
        jdbc.update("UPDATE booking SET status = 'EXPIRED' WHERE id = ?", id);
    }

    private List<InventorySeat> lockSeats(UUID eventId, List<String> codes) {
        return named.query("""
            SELECT seat_code,status,booking_id,hold_until FROM seat_inventory
            WHERE event_id = :event AND seat_code IN (:codes) ORDER BY seat_code FOR UPDATE
            """, Map.of("event", eventId, "codes", codes), (rs, n) -> new InventorySeat(rs.getString("seat_code"),
                rs.getString("status"), rs.getObject("booking_id", UUID.class),
                rs.getTimestamp("hold_until") == null ? null : rs.getTimestamp("hold_until").toInstant()));
    }
    private BookingRow lockBooking(UUID id, UUID userId) {
        String sql = "SELECT id,event_id,status,expires_at FROM booking WHERE id = ?";
        Object[] args = userId == null ? new Object[]{id} : new Object[]{id, userId};
        if (userId != null) sql += " AND user_id = ?";
        return jdbc.query(sql + " FOR UPDATE", (rs, n) -> new BookingRow(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()), args)
                .stream().findFirst().orElseThrow(() -> ApiException.missing("Booking"));
    }
    private List<String> selection(UUID id) {
        return jdbc.query("SELECT seat_code FROM booking_seat WHERE booking_id = ? ORDER BY seat_code", (rs, n) -> rs.getString(1), id);
    }
    private void releaseOwnedSeats(UUID id) {
        jdbc.update("UPDATE seat_inventory SET status = 'AVAILABLE', booking_id = NULL, hold_until = NULL WHERE booking_id = ?", id);
    }
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
    }
    private void requireLiveHold(BookingRow booking, Instant now) {
        if (booking.status().equals("EXPIRED") || (booking.status().equals("HELD") && !booking.expiresAt().isAfter(now))) throw expired();
        if (!booking.status().equals("HELD")) throw ApiException.conflict("INVALID_STATE", "Only a live hold can be confirmed");
    }
    private ApiException expired() { return new ApiException(HttpStatus.GONE, "HOLD_EXPIRED", "This hold has expired; select seats again"); }
    private String fingerprint(String canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record InventorySeat(String code, String status, UUID bookingId, Instant holdUntil) {}
    private record BookingRow(UUID id, UUID eventId, String status, Instant expiresAt) {}

    private static final String VIEW_QUERY = """
        SELECT b.*,e.title,e.venue,e.city,e.starts_at,
          ARRAY(SELECT bs.seat_code FROM booking_seat bs WHERE bs.booking_id = b.id ORDER BY bs.seat_code) AS seats,
          CASE WHEN b.status = 'HELD' AND b.expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE b.status END AS effective_status
        FROM booking b JOIN event e ON e.id = b.event_id
        """;
    private final RowMapper<BookingView> viewMapper = (rs, n) -> new BookingView(rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class), rs.getString("title"), rs.getString("venue"), rs.getString("city"),
            rs.getTimestamp("starts_at").toInstant(), Arrays.asList((String[]) rs.getArray("seats").getArray()),
            rs.getString("effective_status"), rs.getInt("total_paise"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant());
}
