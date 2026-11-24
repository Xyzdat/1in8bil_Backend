package com.life.life_backend.session;

import com.life.life_backend.identity.GuestIdentity;
import com.life.life_backend.engine.local.LocalSimulation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
class LocalSimulationController {
    private final GuestIdentity identity;
    private final SessionService sessions;
    LocalSimulationController(GuestIdentity identity, SessionService sessions) { this.identity = identity; this.sessions = sessions; }

    @PostMapping(path = "/generated/local", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionView> generate(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody GenerateRequest request) {
        var result = sessions.generateLocal(identity.requireOwner(token), key,
                new LocalSimulation.BirthRequest(request.countryIso3(), request.birthYear() == null ? 2026 : request.birthYear()));
        return (result.created() ? ResponseEntity.created(URI.create("/api/v1/sessions/" + result.session().id())) : ResponseEntity.ok())
                .cacheControl(CacheControl.noStore()).body(result.session());
    }

    @PostMapping(path = "/{id}/step", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionView> step(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody StepRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.apply(identity.requireOwner(token), id,
                key, SessionCommand.step(request.expectedVersion())));
    }
    record GenerateRequest(@Pattern(regexp = "[A-Z]{3}") String countryIso3, @Min(2023) @Max(2026) Integer birthYear) { }
    record StepRequest(@NotNull @Positive Long expectedVersion) { }
}
