package com.life.life_backend;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {
		"spring.datasource.url=${LIFE_TEST_DB_URL}",
		"spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}",
		"spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}"
})
@AutoConfigureMockMvc
class LifeBackendApplicationTests {
	private static final String SESSIONS = "/api/v1/sessions";
	private static final String SETUP = """
			{
			  "character": {
			    "name": "Алекс", "gender": "Мужчина", "birthYear": 2026,
			    "birthplace": {"countryCode": "CA", "country": "Канада", "city": "Оттава"},
			    "background": {"family": "Полная семья"}
			  },
			  "startingStats": {"health": 91, "intelligence": 72, "appearance": 64}
			}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Test
	void startsAnswersSavesAndContinuesTheSameLife() throws Exception {
		Cookie owner = guest();
		UUID startKey = UUID.randomUUID();
		String session = create(owner, startKey, SETUP).andExpect(status().isCreated())
				.andExpect(jsonPath("$.state.age").value(0))
				.andExpect(jsonPath("$.character.background.family").value("Полная семья"))
				.andExpect(jsonPath("$.state.history[0].id").value("birth"))
				.andExpect(jsonPath("$.state.stats.appearance").value(64))
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

		create(owner, startKey, SETUP.replace("Алекс", "Маша")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		mvc.perform(get(session).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.character.birthplace.city").value("Оттава"))
				.andExpect(jsonPath("$.version").value(1));

		for (int year = 1; year <= 3; year++) {
			advance(owner, session, UUID.randomUUID(), year).andExpect(status().isOk())
					.andExpect(jsonPath("$.state.age").value(year));
		}
		advance(owner, session, UUID.randomUUID(), 4).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DECISION_REQUIRED"));
		String decision = session + "/decisions/first-workshop/answers";
		action(owner, decision, UUID.randomUUID(), """
				{"expectedVersion":4,"optionId":"unknown"}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OPTION"));
		UUID answerKey = UUID.randomUUID();
		String answer = """
				{"expectedVersion":4,"optionId":"build"}
				""";
		action(owner, decision, answerKey, answer).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(5))
				.andExpect(jsonPath("$.state.stats.intelligence").value(75))
				.andExpect(jsonPath("$.state.pendingDecision").value(nullValue()));

		mvc.perform(get(session).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.history[1].id").value("first-workshop"))
				.andExpect(jsonPath("$.state.history.length()").value(2));
		advance(owner, session, UUID.randomUUID(), 5).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.age").value(4))
				.andExpect(jsonPath("$.version").value(6));
		action(owner, decision, answerKey, answer).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(6))
				.andExpect(jsonPath("$.state.history.length()").value(2));
		action(owner, decision, answerKey, answer.replace("build", "paint")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		String id = session.substring(session.lastIndexOf('/') + 1);
		create(owner, startKey, SETUP).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.version").value(6));
		mvc.perform(get(SESSIONS).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id));
	}

	@Test
	void requiresAnOwnerAndValidPayloads() throws Exception {
		var owner = guest();
		var other = guest();
		mvc.perform(post("/api/v1/guest").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		mvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("GUEST_REQUIRED"));
		create(owner, UUID.randomUUID(), SETUP.replace("91", "101")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		String session = create(owner, UUID.randomUUID(), SETUP).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		mvc.perform(get(session).cookie(other)).andExpect(status().isNotFound());
		advance(other, session, UUID.randomUUID(), 1).andExpect(status().isNotFound());
		mvc.perform(get(SESSIONS).cookie(other)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void serializesSimultaneousActionsAgainstTheSameVersion() throws Exception {
		var owner = guest();
		String session = create(owner, UUID.randomUUID(), SETUP).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		var barrier = new CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var requests = List.of(UUID.randomUUID(), UUID.randomUUID()).stream().map(key -> executor.submit(() -> {
				barrier.await(5, TimeUnit.SECONDS);
				return advance(owner, session, key, 1).andReturn().getResponse();
			})).toList();
			var first = requests.get(0).get(10, TimeUnit.SECONDS);
			var second = requests.get(1).get(10, TimeUnit.SECONDS);
			assertThat(List.of(first.getStatus(), second.getStatus())).containsExactlyInAnyOrder(200, 409);
			var conflict = first.getStatus() == 409 ? first : second;
			assertThat(json.readTree(conflict.getContentAsByteArray()).path("code").asString())
					.isEqualTo("VERSION_CONFLICT");
		}
		mvc.perform(get(session).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.age").value(1)).andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void purchasesAPetOnceAndChargesUpkeepAtTheNextBirthday() throws Exception {
		var owner = guest();
		String session = create(owner, UUID.randomUUID(), SETUP).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		String finances = session + "/finances";
		String shop = session + "/spending/pets";
		String purchases = session + "/purchases";
		mvc.perform(get(finances).cookie(guest())).andExpect(status().isNotFound());
		mvc.perform(get(finances).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("RUB"))
				.andExpect(jsonPath("$.summary.availableBalance").value(0))
				.andExpect(jsonPath("$.categories.length()").value(9))
				.andExpect(jsonPath("$.categories[6].id").value("education"))
				.andExpect(jsonPath("$.categories[6].priceFrom").value(15800));
		action(owner, purchases, UUID.randomUUID(),
				purchase(1, "pets", "hamster", "Луна")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SPENDING_AGE_RESTRICTED"));

		long version = advanceTo(owner, session, 7);
		mvc.perform(get(shop).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.availableBalance").value(1200))
				.andExpect(jsonPath("$.offers[3].id").value("hamster"))
				.andExpect(jsonPath("$.offers[3].balanceAfterPurchase").value(600))
				.andExpect(jsonPath("$.offers[3].yearlyCost").value(1200));
		action(owner, purchases, UUID.randomUUID(),
				purchase(version, "pets", "cat", "Луна")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SPENDING_INSUFFICIENT_FUNDS"));
		action(owner, purchases, UUID.randomUUID(), purchase(version, "pets", "hamster", " "))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PET_NAME"));

		UUID key = UUID.randomUUID();
		String request = purchase(version, "pets", "hamster", "Луна");
		action(owner, purchases, key, request).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(version + 1))
				.andExpect(jsonPath("$.state.age").value(7))
				.andExpect(jsonPath("$.state.stats.happiness").value(82));
		action(owner, purchases, key, request).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(version + 1));
		action(owner, purchases, key, request.replace("Луна", "Мико")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		action(owner, purchases, UUID.randomUUID(), request).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		mvc.perform(get(finances).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.purchases.length()").value(1))
				.andExpect(jsonPath("$.purchases[0].petName").value("Луна"))
				.andExpect(jsonPath("$.ledger[0].expense").value(600))
				.andExpect(jsonPath("$.summary.savings").value(600));

		advance(owner, session, UUID.randomUUID(), version + 1).andExpect(status().isOk());
		mvc.perform(get(finances).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.income").value(2406))
				.andExpect(jsonPath("$.summary.expenses").value(2400))
				.andExpect(jsonPath("$.summary.savings").value(606));
	}

	@Test
	void completesTrainingAndKeepsBirthplaceAfterMoving() throws Exception {
		var owner = guest();
		String session = create(owner, UUID.randomUUID(), SETUP).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		long version = advanceTo(owner, session, 18);
		action(owner, session + "/purchases", UUID.randomUUID(), purchase(version, "country", "georgia", null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.character.birthplace.countryCode").value("CA"));
		mvc.perform(get(session + "/spending/city").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.offers[0].location.countryCode").value("GE"))
				.andExpect(jsonPath("$.offers[0].purchasable").value(false))
				.andExpect(jsonPath("$.offers[1].purchasable").value(true));
		action(owner, session + "/purchases", UUID.randomUUID(), purchase(version + 1, "education", "repair", null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.state.stats.intelligence").value(75));
		mvc.perform(get(session + "/finances").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.purchases[0].status").value("IN_PROGRESS"));
		advance(owner, session, UUID.randomUUID(), version + 2).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.stats.intelligence").value(79))
				.andExpect(jsonPath("$.state.history[4].title").value("Обучение завершено"));
		mvc.perform(get(session + "/finances").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.location.city").value("Тбилиси"))
				.andExpect(jsonPath("$.purchases[0].status").value("COMPLETED"));
	}

	private long advanceTo(Cookie owner, String session, int targetAge) throws Exception {
		long version = 1;
		for (int age = 1; age <= targetAge; age++) {
			advance(owner, session, UUID.randomUUID(), version++).andExpect(status().isOk());
			if (age == 3) {
				action(owner, session + "/decisions/first-workshop/answers", UUID.randomUUID(),
						"{\"expectedVersion\":" + version++ + ",\"optionId\":\"build\"}").andExpect(status().isOk());
			}
		}
		return version;
	}

	private String purchase(long version, String category, String item, String petName) {
		var request = json.createObjectNode().put("expectedVersion", version).put("categoryId", category).put("itemId", item);
		if (petName != null) {
			request.put("petName", petName);
		}
		return json.writeValueAsString(request);
	}

	private Cookie guest() throws Exception {
		String setCookie = mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains("HttpOnly", "SameSite=Lax");
		return MockCookie.parse(setCookie);
	}

	private ResultActions create(Cookie owner, UUID key, String setup) throws Exception {
		return action(owner, SESSIONS, key, setup);
	}

	private ResultActions advance(Cookie owner, String session, UUID key, long expectedVersion) throws Exception {
		return action(owner, session + "/advance", key, "{\"expectedVersion\":" + expectedVersion + "}");
	}

	private ResultActions action(Cookie owner, String path, UUID key, String body) throws Exception {
		return mvc.perform(post(path).cookie(owner).header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void editsLocalStatsWithVersionAndPreservesTheOriginalCreationRequest() throws Exception {
		var owner = guest();
		var other = guest();
		UUID startKey = UUID.randomUUID();
		String session = create(owner, startKey, SETUP).andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		String edit = """
				{"expectedVersion":1,"changes":{"name":" Лина ","stats":{"health":0,"intelligence":80,"appearance":90}}}
				""";
		UUID editKey = UUID.randomUUID();
		editCharacter(other, session, editKey, edit).andExpect(status().isNotFound());
		editCharacter(owner, session, editKey, edit.replace("\"health\":0", "\"health\":101"))
				.andExpect(status().isBadRequest());
		editCharacter(owner, session, editKey, edit.replace("\"health\"", "\"aura\""))
				.andExpect(status().isBadRequest());
		editCharacter(owner, session, editKey, edit).andExpect(status().isOk())
				.andExpect(jsonPath("$.character.name").value("Лина"))
				.andExpect(jsonPath("$.state.stats.health").value(0))
				.andExpect(jsonPath("$.state.stats.intelligence").value(80))
				.andExpect(jsonPath("$.state.stats.appearance").value(90))
				.andExpect(jsonPath("$.version").value(2));
		editCharacter(owner, session, editKey, edit).andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2));
		editCharacter(owner, session, UUID.randomUUID(), edit).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		create(owner, startKey, SETUP).andExpect(status().isOk())
				.andExpect(jsonPath("$.character.name").value("Лина"))
				.andExpect(jsonPath("$.version").value(2));
		advance(owner, session, UUID.randomUUID(), 2).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.stats.health").value(0));
	}

	private ResultActions editCharacter(Cookie owner, String session, UUID key, String body) throws Exception {
		return mvc.perform(patch(session + "/character").cookie(owner).header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}
}
