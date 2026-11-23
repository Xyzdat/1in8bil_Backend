package com.life.life_backend.engine.onein8b;

import com.life.life_backend.web.ApiFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
class OneInEightBillionClient {
	private static final URI LIVE = URI.create("https://1in8b.life/api/v1/live");
	private static final URI JOBS = URI.create("https://1in8b.life/api/v1/jobs");
	private static final String NEWBORN = "https://1in8b.life/api/v1/person?age=0&n=1&token=1&record=1&lang=ru&seed=";
	private static final int CALLS_PER_MINUTE = 30;
	private static final Pattern KEY_FORMAT = Pattern.compile("k8b_[A-Za-z0-9_-]+");
	private final ObjectMapper json;
	private final String apiKey;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final Deque<Instant> calls = new ArrayDeque<>();
	private final Object catalogLock = new Object();
	private Map<String, String> jobLabels = Map.of();
	private Instant nextCatalogRequest = Instant.EPOCH;

	OneInEightBillionClient(ObjectMapper json, @Value("${life.engine.onein8b.api-key:}") String apiKey) {
		this.json = json;
		this.apiKey = apiKey.strip();
	}

	boolean available() {
		return !apiKey.isEmpty();
	}

	JsonNode newborn(long seed) {
		var people = request(URI.create(NEWBORN + seed), null).path("people");
		if (!people.isArray() || people.size() != 1 || !people.get(0).isObject()) {
			throw invalidResponse();
		}
		return people.get(0);
	}

	JsonNode live(Map<String, ?> body) {
		return request(LIVE, body);
	}

	Map<String, String> jobLabels() {
		synchronized (catalogLock) {
			if (Instant.now().isBefore(nextCatalogRequest)) {
				return jobLabels;
			}
			jobLabels = fetchRussianJobLabels();
			// A failed catalog lookup should neither fail a birth nor trigger a retry for every new life.
			nextCatalogRequest = Instant.now().plus(jobLabels.isEmpty() ? Duration.ofMinutes(1) : Duration.ofHours(1));
			return jobLabels;
		}
	}

	private Map<String, String> fetchRussianJobLabels() {
		var request = HttpRequest.newBuilder(JOBS).header("Accept", "application/json")
				.timeout(Duration.ofSeconds(3)).GET().build();
		try {
			var response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return Map.of();
			}
			var labels = new HashMap<String, String>();
			for (var job : json.readTree(response.body()).path("jobs")) {
				String code = job.path("id").asString("");
				String label = job.path("label").path("ru").asString("");
				if (!code.isBlank() && !label.isBlank()) {
					labels.put(code, label);
				}
			}
			return Map.copyOf(labels);
		} catch (IOException | JacksonException exception) {
			return Map.of();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return Map.of();
		}
	}

	private JsonNode request(URI uri, Map<String, ?> body) {
		if (!available()) {
			throw unavailable();
		}
		if (!KEY_FORMAT.matcher(apiKey).matches()) {
			throw invalidKey();
		}
		reserveCall();
		try {
			var request = HttpRequest.newBuilder(uri)
					.header("Authorization", "Bearer " + apiKey)
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(8));
			if (body != null) {
				request.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			} else {
				request.GET();
			}
			var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw providerFailure(response.statusCode(), errorCode(response.body()));
			}
			return json.readTree(response.body());
		} catch (JacksonException exception) {
			throw invalidResponse();
		} catch (IOException exception) {
			throw unavailable();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
	}

	private synchronized void reserveCall() {
		var now = Instant.now();
		var earliest = now.minusSeconds(60);
		while (!calls.isEmpty() && !calls.getFirst().isAfter(earliest)) {
			calls.removeFirst();
		}
		if (calls.size() >= CALLS_PER_MINUTE) {
			throw rateLimit();
		}
		calls.addLast(now);
	}

	private String errorCode(String body) {
		try {
			return json.readTree(body).path("code").asString("");
		} catch (JacksonException exception) {
			return "";
		}
	}

	private ApiFailure providerFailure(int status, String code) {
		if (status == 429) {
			return switch (code) {
				case "month", "free_day" -> new ApiFailure(HttpStatus.TOO_MANY_REQUESTS,
						"ENGINE_QUOTA_EXCEEDED", "The 1in8b Engine quota has been reached.");
				default -> rateLimit();
			};
		}
		if (status == 401 || status == 403) {
			return invalidKey();
		}
		if (code.equals("token") || code.equals("live_state")) {
			return new ApiFailure(HttpStatus.CONFLICT, "ENGINE_CURSOR_REJECTED",
					"1in8b Engine cannot continue from this checkpoint.");
		}
		return status >= 500 ? unavailable() : invalidResponse();
	}

	private ApiFailure rateLimit() {
		return new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "ENGINE_RATE_LIMIT",
				"Too many requests to 1in8b Engine. Try again later.");
	}

	private ApiFailure invalidKey() {
		return new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_KEY_REJECTED",
				"The configured 1in8b Engine key is invalid or was rejected.");
	}

	static ApiFailure invalidResponse() {
		return new ApiFailure(HttpStatus.BAD_GATEWAY, "ENGINE_INVALID_RESPONSE",
				"1in8b Engine returned an unsupported response.");
	}

	private ApiFailure unavailable() {
		return new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_UNAVAILABLE",
				"1in8b Engine is unavailable.");
	}
}
