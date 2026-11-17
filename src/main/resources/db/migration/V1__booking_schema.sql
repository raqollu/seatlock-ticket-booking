CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE event (
    id UUID PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    venue VARCHAR(120) NOT NULL,
    city VARCHAR(80) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    price_paise INTEGER NOT NULL CHECK (price_paise > 0),
    seat_rows INTEGER NOT NULL CHECK (seat_rows BETWEEN 1 AND 26),
    seat_columns INTEGER NOT NULL CHECK (seat_columns BETWEEN 1 AND 30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE booking (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    event_id UUID NOT NULL REFERENCES event(id),
    status VARCHAR(12) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    total_paise INTEGER NOT NULL CHECK (total_paise > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key),
    UNIQUE (id, event_id)
);

-- One authoritative row per physical seat: it can reference only one current booking.
CREATE TABLE seat_inventory (
    event_id UUID NOT NULL REFERENCES event(id),
    seat_code VARCHAR(4) NOT NULL,
    row_label CHAR(1) NOT NULL,
    column_number INTEGER NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    booking_id UUID,
    hold_until TIMESTAMPTZ,
    PRIMARY KEY (event_id, seat_code),
    FOREIGN KEY (booking_id, event_id) REFERENCES booking(id, event_id),
    CHECK (
      (status = 'AVAILABLE' AND booking_id IS NULL AND hold_until IS NULL) OR
      (status = 'HELD' AND booking_id IS NOT NULL AND hold_until IS NOT NULL) OR
      (status = 'BOOKED' AND booking_id IS NOT NULL AND hold_until IS NULL)
    )
);

-- Immutable selection history survives expiry, cancellation and seat reassignment.
CREATE TABLE booking_seat (
    booking_id UUID NOT NULL,
    event_id UUID NOT NULL,
    seat_code VARCHAR(4) NOT NULL,
    PRIMARY KEY (booking_id, seat_code),
    FOREIGN KEY (booking_id, event_id) REFERENCES booking(id, event_id),
    FOREIGN KEY (event_id, seat_code) REFERENCES seat_inventory(event_id, seat_code)
);
CREATE INDEX booking_owner_created ON booking(user_id, created_at DESC);
CREATE INDEX booking_expiry ON booking(expires_at) WHERE status = 'HELD';
CREATE INDEX inventory_booking ON seat_inventory(booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX event_start ON event(starts_at);
