package com.life.life_backend.identity;

import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GuestIdentity {
	public static final String COOKIE_NAME = "life_owner";
	static final Duration COOKIE_LIFETIME = Duration.ofDays(365);
	private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
	private final SecureRandom random = new SecureRandom();
	private final GuestOwnerRepository owners;

	GuestIdentity(GuestOwnerRepository owners) {
		this.owners = owners;
	}

	@Transactional
	Optional<String> ensureGuest(String currentToken) {
		if (findOwner(currentToken).isPresent()) {
			return Optional.empty();
		}
		byte[] secret = new byte[32];
		random.nextBytes(secret);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		owners.save(new GuestOwner(UUID.randomUUID(), hash(token), Instant.now().plus(COOKIE_LIFETIME)));
		return Optional.of(token);
	}

	public UUID requireOwner(String token) {
		return findOwner(token).map(GuestOwner::id).orElseThrow(this::unauthorized);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockOwner(UUID ownerId) {
		owners.lockActiveOwner(ownerId, Instant.now()).orElseThrow(this::unauthorized);
	}

	private Optional<GuestOwner> findOwner(String token) {
		if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
			return Optional.empty();
		}
		return owners.findByTokenHashAndExpiresAtAfter(hash(token), Instant.now());
	}

	private String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}

	private ApiFailure unauthorized() {
		return new ApiFailure(HttpStatus.UNAUTHORIZED, "GUEST_REQUIRED", "A valid guest cookie is required.");
	}
}
