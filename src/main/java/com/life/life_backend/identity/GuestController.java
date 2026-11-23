package com.life.life_backend.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GuestController {
	private final GuestIdentity identity;
	private final boolean secureCookie;

	GuestController(GuestIdentity identity, @Value("${life.guest.secure-cookie:false}") boolean secureCookie) {
		this.identity = identity;
		this.secureCookie = secureCookie;
	}

	@PostMapping(path = "/api/v1/guest", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Void> ensureGuest(@CookieValue(name = GuestIdentity.COOKIE_NAME, required = false) String token) {
		var response = ResponseEntity.noContent().cacheControl(CacheControl.noStore());
		identity.ensureGuest(token).ifPresent(newToken -> response.header(HttpHeaders.SET_COOKIE, cookie(newToken)));
		return response.build();
	}

	private String cookie(String token) {
		return ResponseCookie.from(GuestIdentity.COOKIE_NAME, token)
				.httpOnly(true)
				.secure(secureCookie)
				.sameSite("Lax")
				.path("/api/v1")
				.maxAge(GuestIdentity.COOKIE_LIFETIME)
				.build().toString();
	}
}
