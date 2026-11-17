package dev.seatlock;

import com.fasterxml.jackson.databind.*;
import dev.seatlock.api.*;
import dev.seatlock.api.Models.*;
import dev.seatlock.booking.*;
import dev.seatlock.catalog.CatalogService;
import dev.seatlock.config.DemoData;
import dev.seatlock.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Every persistence/concurrency assertion uses real PostgreSQL, never H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingService bookings;
    @Autowired CatalogService catalog;
    @Autowired AuthService auth;
    @Autowired JwtEncoder encoder;
    @Autowired DemoData demo;
    UUID user, admin, event;
    String token, adminToken;

    @BeforeAll void safetyAndTokens() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).as("Tests must use a dedicated *_test database").endsWith("_test");
        var login = auth.login(new LoginRequest("demo@seatlock.dev", "DemoPass123!"));
        user = login.user().id(); token = login.accessToken();
        var elevated = auth.login(new LoginRequest("admin@seatlock.dev", "AdminPass123!"));
        admin = elevated.user().id(); adminToken = elevated.accessToken();
    }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE booking_seat, seat_inventory, booking, event CASCADE");
        event = catalog.create(new EventRequest("Integration concert", "Test Theatre", "Hyderabad", Instant.now().plusSeconds(86400), 149900, 3, 4)).id();
    }

    @Test void completeHttpLifecycleAndNaturalIdempotency() throws Exception {
        var created = holdHttp("happy", "A1", "A2").andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.totalPaise").value(299800)).andExpect(jsonPath("$.status").value("HELD"));
        String id = body(created).get("id").asText();
        mvc.perform(get("/api/bookings").header("Authorization", bearer())).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/bookings/" + id).header("Authorization", bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.seats.length()").value(2));
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/bookings/" + id + "/confirm").header("Authorization", bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(catalog.get(event).availableSeats()).isEqualTo(10);
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/bookings/" + id + "/cancel").header("Authorization", bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(catalog.get(event).availableSeats()).isEqualTo(12);
        mvc.perform(post("/api/bookings/" + id + "/confirm").header("Authorization", bearer())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }
    @Test void retryCanonicalizesSelectionAndPreservesLifecycle() throws Exception {
        String id = body(holdHttp("retry", "A2", "A1").andExpect(status().isCreated())).get("id").asText();
        holdHttp("retry", "A1", "A2").andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true")).andExpect(jsonPath("$.id").value(id));
        bookings.confirm(user, UUID.fromString(id));
        holdHttp("retry", "A1", "A2").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        holdHttp("retry", "A3").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_MISMATCH"));
        assertThat(count("booking")).isEqualTo(1);
    }
    @Test void conflictingMultiSeatRequestRollsBackEverythingIncludingKey() throws Exception {
        holdHttp("first", "A2").andExpect(status().isCreated());
        holdHttp("second", "A1", "A2").andExpect(status().isConflict());
        assertThat(count("booking")).isEqualTo(1);
        assertThat(catalog.seats(event).stream().filter(s -> s.code().equals("A1")).findFirst().orElseThrow().status()).isEqualTo("AVAILABLE");
        holdHttp("second", "A1").andExpect(status().isCreated());
    }
    @Test void validatesSeatsKeysAndUnknownResources() throws Exception {
        holdHttp("dup", "A1", "A1").andExpect(status().isBadRequest());
        holdHttp("bad key!", "A1").andExpect(status().isBadRequest());
        holdHttp("none").andExpect(status().isBadRequest());
        holdHttp("too-many", "A1","A2","A3","A4","B1","B2","B3","B4","C1").andExpect(status().isBadRequest());
        holdHttp("format", "<script>").andExpect(status().isBadRequest());
        holdHttp("unknown", "Z30").andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings/holds").header("Authorization", bearer()).contentType("application/json").content(json.writeValueAsString(new HoldRequest(event,List.of("A1"))))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/no-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/events/" + UUID.randomUUID() + "/seats")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/" + UUID.randomUUID()).header("Authorization", bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/holds").header("Authorization", bearer()).header("Idempotency-Key", "nulls").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
    }
    @Test void rejectsMalformedJsonAndClientSuppliedPricing() throws Exception {
        mvc.perform(post("/api/bookings/holds").header("Authorization", bearer()).header("Idempotency-Key", "bad-json").contentType("application/json").content("{" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings/holds").header("Authorization", bearer()).header("Idempotency-Key", "price").contentType("application/json").content("{\"eventId\":\""+event+"\",\"seats\":[\"A1\"],\"totalPaise\":1}" )).andExpect(status().isBadRequest());
    }
    @Test void defaultTtlIsFifteenMinutesAndEventStartCapsIt() {
        var normal = hold(user,"ttl","A1");
        long seconds = java.time.Duration.between(normal.createdAt(), normal.expiresAt()).toSeconds();
        assertThat(seconds).isBetween(899L,901L);
        Instant start = Instant.now().plusSeconds(60);
        jdbc.update("UPDATE event SET starts_at = ? WHERE id = ?", Timestamp.from(start), event);
        assertThat(hold(user,"capped","A2").expiresAt()).isCloseTo(start, within(1, java.time.temporal.ChronoUnit.MICROS));
    }
    @Test void refusesNewHoldAfterEventStartsButAllowsReplay() {
        var original = hold(user,"before","A1");
        jdbc.update("UPDATE event SET starts_at = clock_timestamp() - interval '1 minute' WHERE id = ?",event);
        assertThat(hold(user,"before","A1").id()).isEqualTo(original.id());
        assertThatThrownBy(() -> hold(user,"after","A2")).isInstanceOf(ApiException.class).hasMessageContaining("closed");
        assertThat(count("booking")).isEqualTo(1);
    }
    @Test void lazyExpiryReclaimsSeatsWithoutSchedulerAndOldCleanupCannotSteal() {
        var old = hold(user,"old","A1"); makeExpired(old.id());
        assertThat(bookings.get(user,old.id()).status()).isEqualTo("EXPIRED");
        assertThat(catalog.get(event).availableSeats()).isEqualTo(12);
        var replacement = hold(admin,"new","A1");
        assertThatThrownBy(() -> bookings.confirm(user,old.id())).isInstanceOf(ApiException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> bookings.cancel(user,old.id())).isInstanceOf(ApiException.class).hasMessageContaining("expired");
        new ExpiryScheduler(bookings).sweep();
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?",String.class,old.id())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT booking_id FROM seat_inventory WHERE event_id = ? AND seat_code = 'A1'",UUID.class,event)).isEqualTo(replacement.id());
        assertThat(bookings.confirm(admin,replacement.id()).status()).isEqualTo("CONFIRMED");
        bookings.expire(old.id());
    }
    @Test void sweeperReleasesOnlyExpiredHoldsAndCanRunTwice() {
        var old = hold(user,"old-sweep","A1"); var live = hold(user,"live-sweep","A2");
        bookings.expire(live.id()); assertThat(bookings.get(user,live.id()).status()).isEqualTo("HELD");
        makeExpired(old.id()); new ExpiryScheduler(bookings).sweep(); new ExpiryScheduler(bookings).sweep();
        assertThat(catalog.get(event).availableSeats()).isEqualTo(11);
        assertThatThrownBy(() -> bookings.confirm(user,old.id())).isInstanceOf(ApiException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> bookings.cancel(user,old.id())).isInstanceOf(ApiException.class).hasMessageContaining("expired");
        assertThat(bookings.get(user,old.id()).seats()).containsExactly("A1");
    }
    @Test void ownersCannotReadConfirmOrCancelOtherPeoplesBookings() throws Exception {
        var booking = hold(user,"private","A1");
        mvc.perform(get("/api/bookings/"+booking.id()).header("Authorization","Bearer "+adminToken)).andExpect(status().isNotFound());
        for (String action : List.of("confirm","cancel")) mvc.perform(post("/api/bookings/"+booking.id()+"/"+action).header("Authorization","Bearer "+adminToken)).andExpect(status().isNotFound());
        assertThat(bookings.list(admin)).isEmpty();
    }
    @Test void idempotencyKeysAreScopedToUser() {
        assertThat(hold(user,"same-key","A1").id()).isNotEqualTo(hold(admin,"same-key","A2").id());
    }
    @Test void cancelHeldSeatThenBookItAgain() {
        var first = hold(user,"cancel-hold","A1");
        assertThat(bookings.cancel(user,first.id()).status()).isEqualTo("CANCELLED");
        assertThat(hold(admin,"rebook","A1").status()).isEqualTo("HELD");
        assertThat(bookings.get(user,first.id()).seats()).containsExactly("A1");
    }
    @Test void jwtAndRoleSecurityAreEnforced() throws Exception {
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization",bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("demo@seatlock.dev"));
        mvc.perform(post("/api/events").header("Authorization",bearer()).contentType("application/json").content(eventJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/events").header("Authorization","Bearer "+adminToken).contentType("application/json").content(eventJson())).andExpect(status().isCreated()).andExpect(jsonPath("$.totalSeats").value(12));
        mvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/events/"+event+"/seats")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
    @Test void rejectsExpiredWrongIssuerWrongAudienceAndWrongSignatureTokens() throws Exception {
        for (String[] pair : List.of(new String[]{"wrong",SecurityConfig.AUDIENCE}, new String[]{SecurityConfig.ISSUER,"wrong"})) {
            mvc.perform(get("/api/bookings").header("Authorization","Bearer "+signedToken(pair[0],pair[1],Instant.now().plusSeconds(600)))).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/bookings").header("Authorization","Bearer "+signedToken(SecurityConfig.ISSUER,SecurityConfig.AUDIENCE,Instant.now().minusSeconds(120)))).andExpect(status().isUnauthorized());
        String[] segments = token.split("\\.");
        String badSignature = (segments[2].charAt(0) == 'A' ? "B" : "A") + segments[2].substring(1);
        mvc.perform(get("/api/bookings").header("Authorization","Bearer "+segments[0]+"."+segments[1]+"."+badSignature)).andExpect(status().isUnauthorized());
    }
    @Test void registerLoginNormalizeEmailAndHashPassword() throws Exception {
        String email = "New-"+UUID.randomUUID()+"@Example.com";
        var registration = mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(new RegisterRequest(email,"SecurePass123!","New Person")))).andExpect(status().isCreated()).andExpect(jsonPath("$.user.role").value("USER"));
        String id = body(registration).get("user").get("id").asText();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",String.class,UUID.fromString(id))).startsWith("$2a$").isNotEqualTo("SecurePass123!");
        mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(new LoginRequest(email.toLowerCase(Locale.ROOT),"SecurePass123!")))).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(new RegisterRequest(email,"SecurePass123!","Duplicate")))).andExpect(status().isConflict());
    }
    @Test void invalidAccountsAndPasswordsDoNotLeakCredentials() throws Exception {
        for (LoginRequest request : List.of(new LoginRequest("unknown@example.com","wrong"),new LoginRequest("demo@seatlock.dev","wrong"),new LoginRequest("demo@seatlock.dev","界".repeat(30)))) {
            mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(new RegisterRequest("x@example.com","short","X")))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(new RegisterRequest("x@example.com","界".repeat(30),"X")))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> auth.me(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
    @Test void demoInitializationIsRepeatable() {
        demo.run(null); assertThat(count("event")).isEqualTo(1);
        jdbc.execute("TRUNCATE seat_inventory,event CASCADE"); demo.run(null); demo.run(null);
        assertThat(count("event")).isEqualTo(3);
    }
    @Test void invalidEventInputsAreRejected() throws Exception {
        var request = new EventRequest(" ","Test","City",Instant.now().minusSeconds(1),-1,99,0);
        mvc.perform(post("/api/events").header("Authorization","Bearer "+adminToken).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
    }

    @Test void fiveHundredConcurrentContendersHaveExactlyOneWinner() throws Exception {
        List<Attempt> outcomes = race(500, index -> {
            try { return new Attempt(hold(user,"race-"+index,"A1").id(),null); }
            catch (ApiException error) { return new Attempt(null,error.code()); }
        });
        var winners = outcomes.stream().filter(a -> a.id()!=null).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes.stream().filter(a -> "SEAT_UNAVAILABLE".equals(a.error()))).hasSize(499);
        bookings.confirm(user,winners.getFirst().id());
        assertThat(count("booking")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_inventory WHERE status = 'BOOKED'",Integer.class)).isEqualTo(1);
        assertNoBrokenOwnership();
    }
    @Test void concurrentSameKeyCreatesOneBookingAndOneSeatAssignment() throws Exception {
        List<UUID> ids = race(50,index -> hold(user,"shared-retry","A1","A2").id());
        assertThat(new HashSet<>(ids)).hasSize(1); assertThat(count("booking")).isEqualTo(1);
        assertThat(count("booking_seat")).isEqualTo(2); assertNoBrokenOwnership();
    }
    @Test void overlappingSelectionsInReverseOrderDoNotDeadlockOrPartiallyAllocate() throws Exception {
        var results = race(50,index -> {
            try { return new Attempt(hold(user,"overlap-"+index,index%2==0?new String[]{"A1","A2"}:new String[]{"A2","A1"}).id(),null); }
            catch (ApiException error) { return new Attempt(null,error.code()); }
        });
        assertThat(results.stream().filter(a -> a.id()!=null)).hasSize(1);
        assertThat(results.stream().filter(a -> "SEAT_UNAVAILABLE".equals(a.error()))).hasSize(49);
        assertThat(count("booking_seat")).isEqualTo(2); assertNoBrokenOwnership();
    }
    @Test void simultaneousConfirmAndCancelLeaveAConsistentFinalState() throws Exception {
        var booking = hold(user,"transitions","A1");
        race(2,index -> {
            try { return index == 0 ? bookings.confirm(user,booking.id()).status() : bookings.cancel(user,booking.id()).status(); }
            catch (ApiException error) { assertThat(error.code()).isEqualTo("INVALID_STATE"); return error.code(); }
        });
        assertThat(bookings.get(user,booking.id()).status()).isEqualTo("CANCELLED");
        assertThat(catalog.get(event).availableSeats()).isEqualTo(12); assertNoBrokenOwnership();
    }
    @Test void concurrentReclaimAndExpiryPreserveNewOwner() throws Exception {
        var old = hold(user,"old-race","A1"); makeExpired(old.id());
        var results = race(2,index -> {
            if (index == 0) { bookings.expire(old.id()); return old.id(); }
            return hold(admin,"new-race","A1").id();
        });
        UUID current = results.get(1);
        assertThat(jdbc.queryForObject("SELECT booking_id FROM seat_inventory WHERE event_id = ? AND seat_code = 'A1'",UUID.class,event)).isEqualTo(current);
        bookings.confirm(admin,current); assertNoBrokenOwnership();
    }
    @Test void concurrentDifferentPayloadSameKeyHasOneWinnerAndOneMismatch() throws Exception {
        var outcomes = race(2,index -> {
            try { return new Attempt(hold(user,"mismatched-race",index==0?"A1":"A2").id(),null); }
            catch (ApiException error) { return new Attempt(null,error.code()); }
        });
        assertThat(outcomes.stream().filter(a -> a.id()!=null)).hasSize(1);
        assertThat(outcomes.stream().filter(a -> "IDEMPOTENCY_MISMATCH".equals(a.error()))).hasSize(1);
        assertThat(count("booking")).isEqualTo(1); assertNoBrokenOwnership();
    }

    private BookingView hold(UUID owner,String key,String... seats) { return bookings.hold(owner,key,new HoldRequest(event,List.of(seats))).booking(); }
    private ResultActions holdHttp(String key,String... seats) throws Exception {
        return mvc.perform(post("/api/bookings/holds").header("Authorization",bearer()).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(new HoldRequest(event,List.of(seats)))));
    }
    private String bearer() { return "Bearer "+token; }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private void makeExpired(UUID id) {
        jdbc.update("UPDATE booking SET expires_at = clock_timestamp() - interval '1 second' WHERE id = ?",id);
        jdbc.update("UPDATE seat_inventory SET hold_until = clock_timestamp() - interval '1 second' WHERE booking_id = ?",id);
    }
    private String eventJson() throws Exception { return json.writeValueAsString(new EventRequest("New concert","Venue","City",Instant.now().plusSeconds(3600),9900,3,4)); }
    private String signedToken(String issuer,String audience,Instant expires) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(user.toString()).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expires).claim("role","USER").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    private void assertNoBrokenOwnership() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM booking b JOIN booking_seat bs ON bs.booking_id=b.id
            JOIN seat_inventory s ON s.event_id=bs.event_id AND s.seat_code=bs.seat_code
            WHERE (b.status='CONFIRMED' OR (b.status='HELD' AND b.expires_at>clock_timestamp()))
              AND (s.booking_id IS DISTINCT FROM b.id OR s.status NOT IN ('HELD','BOOKED'))
            """,Integer.class)).isZero();
    }
    private <T> List<T> race(int count, java.util.function.IntFunction<T> action) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0,count).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); if (!start.await(20,TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                return action.apply(index);
            })).toList();
            assertThat(ready.await(20,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(60,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); }
    }
    private record Attempt(UUID id,String error) {}
}
