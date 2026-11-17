package dev.seatlock.catalog;

import dev.seatlock.api.ApiException;
import dev.seatlock.api.Models.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.*;

@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String EVENT_QUERY = """
        SELECT e.*, count(s.seat_code)::int AS total_seats,
          count(s.seat_code) FILTER (WHERE s.status = 'AVAILABLE' OR
            (s.status = 'HELD' AND s.hold_until <= clock_timestamp()))::int AS available_seats
        FROM event e LEFT JOIN seat_inventory s ON s.event_id = e.id
        """;
    private final RowMapper<EventView> eventMapper = (rs, n) -> new EventView(rs.getObject("id", UUID.class),
            rs.getString("title"), rs.getString("venue"), rs.getString("city"), rs.getTimestamp("starts_at").toInstant(),
            rs.getInt("price_paise"), rs.getInt("seat_rows"), rs.getInt("seat_columns"), rs.getInt("available_seats"), rs.getInt("total_seats"));

    public List<EventView> list() {
        return jdbc.query(EVENT_QUERY + " GROUP BY e.id ORDER BY e.starts_at, e.id LIMIT 100", eventMapper);
    }
    public EventView get(UUID id) {
        return jdbc.query(EVENT_QUERY + " WHERE e.id = ? GROUP BY e.id", eventMapper, id)
                .stream().findFirst().orElseThrow(() -> ApiException.missing("Event"));
    }
    public List<SeatView> seats(UUID id) {
        get(id);
        return jdbc.query("""
            SELECT seat_code, row_label, column_number,
              CASE WHEN status = 'HELD' AND hold_until <= clock_timestamp() THEN 'AVAILABLE' ELSE status END AS effective_status
            FROM seat_inventory WHERE event_id = ? ORDER BY row_label, column_number
            """, (rs, n) -> new SeatView(rs.getString("seat_code"), rs.getString("row_label"),
                rs.getInt("column_number"), rs.getString("effective_status")), id);
    }
    @Transactional
    public EventView create(EventRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,title,venue,city,starts_at,price_paise,seat_rows,seat_columns) VALUES (?,?,?,?,?,?,?,?)",
                id, request.title().trim(), request.venue().trim(), request.city().trim(), Timestamp.from(request.startsAt()),
                request.pricePaise(), request.rows(), request.columns());
        var seats = new ArrayList<Object[]>();
        for (int r = 0; r < request.rows(); r++) {
            String label = Character.toString('A' + r);
            for (int c = 1; c <= request.columns(); c++) seats.add(new Object[]{id, label + c, label, c});
        }
        jdbc.batchUpdate("INSERT INTO seat_inventory(event_id,seat_code,row_label,column_number) VALUES (?,?,?,?)", seats);
        return get(id);
    }
}
