package com.life.life_backend.engine.onein8b;

import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class EngineTokenCipher {
	private static final String FORMAT = "v1.";
	private static final int IV_SIZE = 12;
	private static final int TAG_BITS = 128;
	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	EngineTokenCipher(String secret) {
		try {
			byte[] keyMaterial = MessageDigest.getInstance("SHA-256")
					.digest(("life-backend:onein8b:cursor:" + secret).getBytes(StandardCharsets.UTF_8));
			key = new SecretKeySpec(keyMaterial, "AES");
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	String encrypt(String token) {
		try {
			byte[] iv = new byte[IV_SIZE];
			random.nextBytes(iv);
			byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(token.getBytes(StandardCharsets.UTF_8));
			byte[] payload = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
			return FORMAT + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Could not encrypt the engine cursor", exception);
		}
	}

	String decrypt(String encrypted) {
		if (!encrypted.startsWith(FORMAT)) {
			throw unreadable();
		}
		try {
			var payload = ByteBuffer.wrap(Base64.getUrlDecoder().decode(encrypted.substring(FORMAT.length())));
			if (payload.remaining() < IV_SIZE + TAG_BITS / Byte.SIZE) {
				throw unreadable();
			}
			byte[] iv = new byte[IV_SIZE];
			payload.get(iv);
			byte[] ciphertext = new byte[payload.remaining()];
			payload.get(ciphertext);
			return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException exception) {
			throw unreadable();
		}
	}

	private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
		var cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}

	private ApiFailure unreadable() {
		return new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_CURSOR_UNREADABLE",
				"The saved engine cursor cannot be opened with the configured secret.");
	}
}
