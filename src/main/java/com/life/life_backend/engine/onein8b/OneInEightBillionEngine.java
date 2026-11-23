package com.life.life_backend.engine.onein8b;

import com.life.life_backend.engine.BirthDetails;
import com.life.life_backend.engine.CharacterProfile;
import com.life.life_backend.engine.LifeEngine;
import com.life.life_backend.engine.LifeState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.life.life_backend.engine.onein8b.OneInEightBillionClient.invalidResponse;

@Component
public class OneInEightBillionEngine implements LifeEngine {
	public static final String ID = "1in8b";
	private static final Duration TOKEN_LIFETIME = Duration.ofDays(90);
	private final OneInEightBillionClient client;
	private final NewbornProfileMapper profiles;
	private final EngineTokenCipher cipher;

	OneInEightBillionEngine(OneInEightBillionClient client, NewbornProfileMapper profiles,
			@Value("${life.engine.onein8b.api-key:}") String apiKey,
			@Value("${life.engine.onein8b.token-secret:}") String tokenSecret) {
		this.client = client;
		this.profiles = profiles;
		cipher = new EngineTokenCipher(tokenSecret.isBlank() ? apiKey.strip() : tokenSecret);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean available() {
		return client.available();
	}

	public GeneratedLife generate(UUID ownerId, UUID requestKey) {
		var startedAt = Instant.now();
		// The upstream accepts an unsigned 32-bit seed. Reuse it when a start is retried.
		var stableInput = (ownerId + ":" + requestKey).getBytes(StandardCharsets.UTF_8);
		long seed = Integer.toUnsignedLong(UUID.nameUUIDFromBytes(stableInput).hashCode());
		var response = client.newborn(seed);
		var state = response.path("state");
		if (wholeAge(state.path("age")) != 0) {
			throw invalidResponse();
		}
		var profile = profiles.map(state, response.path("family_at_birth"));
		var character = profile.character();
		var birth = new LifeState.HistoryEvent("birth", 0, "Рождение", character.birthplace().country());
		var record = response.path("record");
		var history = record.isMissingNode() || record.isNull()
				? List.of(birth) : appendEvents(List.of(birth), record, 0);
		var snapshot = new LifeState(0, LifeState.Status.ACTIVE, stats(state), null, history);
		var cursor = new Cursor(cipher.encrypt(requiredText(response.path("token"))), startedAt.plus(TOKEN_LIFETIME));
		return new GeneratedLife(character, profile.details(), new Checkpoint(snapshot, cursor,
				ProviderEconomyMapper.map(state, response.path("sheet"), 0, false)));
	}

	@Override
	public Checkpoint advance(Checkpoint current) {
		var cursor = requireCursor(current);
		var response = client.live(Map.of("token", cipher.decrypt(cursor.token()), "until", "year"));
		return checkpoint(response, current.state(), cursor.expiresAt());
	}

	@Override
	public Checkpoint answer(Checkpoint current, String optionId) {
		var cursor = requireCursor(current);
		var response = client.live(Map.of("token", cipher.decrypt(cursor.token()), "choose", optionId, "until", "year"));
		return checkpoint(response, current.state(), cursor.expiresAt());
	}

	private Checkpoint checkpoint(JsonNode response, LifeState previous, Instant expiresAt) {
		var endedValue = response.path("ended");
		if (!endedValue.isBoolean()) {
			throw invalidResponse();
		}
		boolean ended = endedValue.asBoolean();
		var state = response.path("state");
		int age = ended && response.path("death").path("age").isNumber()
				? wholeAge(response.path("death").path("age")) : wholeAge(state.path("age"));
		if (age < previous.age() || age - previous.age() > 1) {
			throw invalidResponse();
		}
		var stats = ended && !state.path("health").isNumber() ? previous.stats() : stats(state);
		var decision = ended ? null : decision(response.path("decision"));
		var history = appendEvents(previous.history(), response.path("events"), age);
		var snapshot = new LifeState(age, ended ? LifeState.Status.COMPLETE : LifeState.Status.ACTIVE,
				stats, decision, history);
		var cursor = ended ? null : new Cursor(cipher.encrypt(requiredText(response.path("token"))), expiresAt);
		var economy = ProviderEconomyMapper.map(state, response.path("sheet"), age, ended);
		return new Checkpoint(snapshot, cursor, economy);
	}

	private List<LifeState.HistoryEvent> appendEvents(List<LifeState.HistoryEvent> history,
			JsonNode events, int currentAge) {
		var entries = new ArrayList<>(history);
		for (var event : requiredArray(events)) {
			if (!event.isArray() || event.size() < 2) {
				throw invalidResponse();
			}
			int age = wholeAge(event.get(0));
			if (age > currentAge) {
				throw invalidResponse();
			}
			entries.add(new LifeState.HistoryEvent("1in8b-" + entries.size(), age,
					"Событие жизни", requiredText(event.get(1))));
		}
		return entries;
	}

	private LifeState.Stats stats(JsonNode state) {
		return new LifeState.Stats(score(state.path("health")), score(state.path("happiness")),
				score(state.path("knowledge")), score(state.path("bonds")), null, null);
	}

	private LifeState.Decision decision(JsonNode node) {
		if (node.isNull()) {
			return null;
		}
		var options = new ArrayList<LifeState.Option>();
		for (var option : requiredArray(node.path("choices"))) {
			options.add(new LifeState.Option(requiredText(option.path("key")), requiredText(option.path("label"))));
		}
		if (options.isEmpty()) {
			throw invalidResponse();
		}
		return new LifeState.Decision(requiredText(node.path("id")), requiredText(node.path("question")), options);
	}

	private int score(JsonNode node) {
		int score = wholeAge(node);
		if (score > 100) {
			throw invalidResponse();
		}
		return score;
	}

	private int wholeAge(JsonNode node) {
		if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) {
			throw invalidResponse();
		}
		return node.asInt();
	}

	private String requiredText(JsonNode node) {
		String text = node.asString("");
		if (!node.isString() || text.isBlank()) {
			throw invalidResponse();
		}
		return text;
	}

	private JsonNode requiredArray(JsonNode node) {
		if (!node.isArray()) {
			throw invalidResponse();
		}
		return node;
	}

	private Cursor requireCursor(Checkpoint current) {
		if (current.cursor() == null) {
			throw invalidResponse();
		}
		return current.cursor();
	}

	public record GeneratedLife(CharacterProfile character, BirthDetails birthDetails, Checkpoint checkpoint) {
	}
}
