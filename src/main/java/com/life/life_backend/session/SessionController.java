package com.life.life_backend.session;

import com.life.life_backend.identity.GuestIdentity;
import com.life.life_backend.web.ApiFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
class SessionController {
	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private final GuestIdentity identity;
	private final SessionService sessions;

	SessionController(GuestIdentity identity, SessionService sessions) {
		this.identity = identity;
		this.sessions = sessions;
	}

	@PatchMapping(path = "/{sessionId}/character", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> editCharacter(
			@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId, @RequestHeader(IDEMPOTENCY_KEY) UUID requestKey,
			@Valid @RequestBody CharacterEditRequest request) {
		return current(sessions.apply(identity.requireOwner(token), sessionId, requestKey,
				SessionCommand.editCharacter(request.expectedVersion(), request.changes())));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> create(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@RequestHeader(IDEMPOTENCY_KEY) UUID requestKey, @Valid @RequestBody CreateSessionRequest request) {
		return creation(sessions.create(identity.requireOwner(token), requestKey, request));
	}

	@PostMapping(path = "/generated", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> generate(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@RequestHeader(IDEMPOTENCY_KEY) UUID requestKey, @RequestBody Map<String, Object> request) {
		if (!request.isEmpty()) {
			throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Send an empty object to generate a life.");
		}
		return creation(sessions.generate(identity.requireOwner(token), requestKey));
	}

	private ResponseEntity<SessionView> creation(SessionService.Creation result) {
		var response = result.created()
				? ResponseEntity.created(URI.create("/api/v1/sessions/" + result.session().id()))
				: ResponseEntity.ok();
		return response.cacheControl(CacheControl.noStore()).body(result.session());
	}

	@GetMapping
	ResponseEntity<List<SessionView.Summary>> list(
			@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token) {
		return current(sessions.list(identity.requireOwner(token)));
	}

	@GetMapping("/{sessionId}")
	ResponseEntity<SessionView> get(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId) {
		return current(sessions.get(identity.requireOwner(token), sessionId));
	}

	@PostMapping(path = "/{sessionId}/advance", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> advance(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId, @RequestHeader(IDEMPOTENCY_KEY) UUID requestKey,
			@Valid @RequestBody AdvanceRequest request) {
		var command = SessionCommand.advance(request.expectedVersion());
		return current(sessions.apply(identity.requireOwner(token), sessionId, requestKey, command));
	}

	@PostMapping(path = "/{sessionId}/decisions/{decisionId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> answer(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId, @PathVariable String decisionId, @RequestHeader(IDEMPOTENCY_KEY) UUID requestKey,
			@Valid @RequestBody AnswerRequest request) {
		var command = SessionCommand.answer(request.expectedVersion(), decisionId, request.optionId());
		return current(sessions.apply(identity.requireOwner(token), sessionId, requestKey, command));
	}

	private <T> ResponseEntity<T> current(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record AdvanceRequest(@NotNull @Positive Long expectedVersion) {
	}

	record AnswerRequest(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 80) String optionId) {
	}

	record CharacterEditRequest(@NotNull @Positive Long expectedVersion, @NotNull @Valid CharacterChanges changes) {
	}
}
