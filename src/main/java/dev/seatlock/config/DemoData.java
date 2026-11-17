package dev.seatlock.config;

import dev.seatlock.api.Models.EventRequest;
import dev.seatlock.catalog.CatalogService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "ticket.demo-enabled", havingValue = "true")
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final CatalogService catalog;
    public DemoData(JdbcTemplate jdbc, PasswordEncoder passwords, CatalogService catalog) {
        this.jdbc = jdbc; this.passwords = passwords; this.catalog = catalog;
    }
    @Override public void run(ApplicationArguments args) {
        addUser("demo@seatlock.dev", "DemoPass123!", "Rahul", "USER");
        addUser("admin@seatlock.dev", "AdminPass123!", "Demo Admin", "ADMIN");
        if (jdbc.queryForObject("SELECT count(*) FROM event", Integer.class) == 0) {
            Instant start = Instant.now().plusSeconds(14 * 86400);
            catalog.create(new EventRequest("The Midnight Collective", "The Glasshouse", "Hyderabad", start, 149900, 8, 12));
            catalog.create(new EventRequest("An Evening of Jazz", "Blue Room", "Bengaluru", start.plusSeconds(86400), 99900, 6, 10));
            catalog.create(new EventRequest("Beyond the Ordinary", "Theatre No. 8", "Mumbai", start.plusSeconds(172800), 79900, 7, 10));
        }
    }
    private void addUser(String email, String password, String name, String role) {
        jdbc.update("INSERT INTO app_user(id,email,display_name,password_hash,role) VALUES (?,?,?,?,?) ON CONFLICT (email) DO NOTHING",
                UUID.randomUUID(), email, name, passwords.encode(password), role);
    }
}
