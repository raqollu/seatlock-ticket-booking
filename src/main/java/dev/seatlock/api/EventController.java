package dev.seatlock.api;

import dev.seatlock.api.Models.*;
import dev.seatlock.catalog.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final CatalogService catalog;
    public EventController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping public List<EventView> list() { return catalog.list(); }
    @GetMapping("/{id}") public EventView get(@PathVariable UUID id) { return catalog.get(id); }
    @GetMapping("/{id}/seats") public List<SeatView> seats(@PathVariable UUID id) { return catalog.seats(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public EventView create(@Valid @RequestBody EventRequest request) { return catalog.create(request); }
}
