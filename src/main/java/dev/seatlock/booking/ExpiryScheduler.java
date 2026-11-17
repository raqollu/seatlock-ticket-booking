package dev.seatlock.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticket.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);
    private final BookingService bookings;
    public ExpiryScheduler(BookingService bookings) { this.bookings = bookings; }
    @Scheduled(fixedDelayString = "${ticket.expiry-delay-ms:10000}")
    public void sweep() {
        for (var id : bookings.expiredCandidates()) {
            try { bookings.expire(id); }
            catch (RuntimeException error) { log.warn("Could not expire booking {}; next sweep will retry ({})", id, error.getClass().getSimpleName()); }
        }
    }
}
