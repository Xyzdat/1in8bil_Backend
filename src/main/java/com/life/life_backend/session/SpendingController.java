package com.life.life_backend.session;

import com.life.life_backend.economy.FinanceView;
import com.life.life_backend.identity.GuestIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
class SpendingController {
	private final GuestIdentity identity;
	private final SessionService sessions;

	SpendingController(GuestIdentity identity, SessionService sessions) {
		this.identity = identity;
		this.sessions = sessions;
	}

	@GetMapping("/finances")
	ResponseEntity<FinanceView> finances(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId) {
		return current(sessions.finances(identity.requireOwner(token), sessionId));
	}

	@GetMapping("/spending/{categoryId}")
	ResponseEntity<FinanceView.Shop> shop(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId, @PathVariable String categoryId) {
		return current(sessions.shop(identity.requireOwner(token), sessionId, categoryId));
	}

	@PostMapping(path = "/purchases", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<SessionView> purchase(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token,
			@PathVariable UUID sessionId, @RequestHeader("Idempotency-Key") UUID requestKey,
			@Valid @RequestBody PurchaseRequest request) {
		var command = SessionCommand.purchase(request.expectedVersion(), request.categoryId(), request.itemId(), request.petName());
		return current(sessions.apply(identity.requireOwner(token), sessionId, requestKey, command));
	}

	private <T> ResponseEntity<T> current(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record PurchaseRequest(@NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 40) String categoryId,
			@NotBlank @Size(max = 80) String itemId, @Size(max = 40) @Pattern(regexp = "[^\\p{Cc}]*") String petName) {
	}
}
