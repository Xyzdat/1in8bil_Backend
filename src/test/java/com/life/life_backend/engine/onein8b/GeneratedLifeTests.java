package com.life.life_backend.engine.onein8b;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "LIFE_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {
		"spring.datasource.url=${LIFE_TEST_DB_URL}",
		"spring.datasource.username=${LIFE_TEST_DB_USERNAME:life_test}",
		"spring.datasource.password=${LIFE_TEST_DB_PASSWORD:}",
		"life.engine.onein8b.api-key=fixture-key",
		"life.engine.onein8b.token-secret=fixture-encryption-key"
})
@AutoConfigureMockMvc
class GeneratedLifeTests {
	private static final String GENERATED = "/api/v1/sessions/generated";
	private static final String START = """
			{
			  "state": {"age": 0, "year": 2026, "born_year": 2026, "born_in": "CHN", "country": "CHN",
			            "place": "town", "city": "Пекин", "name": "Тест", "sex": "F",
			            "health": 81, "happiness": 76, "knowledge": 5, "bonds": 90,
			            "mother": {"age": 31, "job": "homemaker", "alive": true, "at_home": true},
			            "father": {"age": 34, "job": "clerk", "alive": true, "at_home": false},
			            "siblings": [{"age": 5, "sex": "F", "older": true, "at_home": true}],
			            "household": {"size": 4, "with": {"mother": true, "father": false, "siblings": 2}},
			            "home_owned": false, "electricity_at_home": true,
			            "who": {"faith":"buddhist", "group":{"id":"test_group","name":"Группа"}, "disability":[]}},
			  "family_at_birth": {"parents":"mother_and_father", "mother_age":30, "father_age":34,
			                      "father_there":false, "older_siblings":2, "family_fifth":3},
			  "record": [[0, "Первые дни дома"]],
			  "token": "fixture-first-cursor"
			}
			""";
	private static final String FIRST_YEAR = """
			{
			  "state": {"age": 1, "year": 2027, "health": 81, "happiness": 76, "knowledge": 12, "bonds": 90,
			            "income_ppp": 1200, "net_worth_ppp": 215.50},
			  "sheet": [{"id":"test","name":"Предложение","does":"Описание","price_ppp":20.50,"can":true}],
			  "ended": false, "events": [[0, "Первый год"]], "decision": null, "token": "fixture-year-one"
			}
			""";
	private static final String SECOND_YEAR = """
			{
			  "state": {"age": 2, "year": 2028, "health": 81, "happiness": 76, "knowledge": 25, "bonds": 90},
			  "ended": false,
			  "events": [[1, "Первые шаги"]],
			  "decision": {"id": "first", "question": "Что выбрать?", "choices": [{"key": "read", "label": "Почитать"}]},
			  "token": "fixture-year-two"
			}
			""";
	private static final String NEXT = """
			{
			  "state": {"age": 3, "year": 2029, "health": 81, "happiness": 76, "knowledge": 28, "bonds": 90},
			  "ended": false, "events": [[2, "Новая история"]], "decision": null, "token": "fixture-second-cursor"
			}
			""";
	private static final String END = """
			{"ended": true, "death": {"age": 4}, "events": [[3, "Последнее событие"]], "token": null}
			""";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper json;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoBean
	private OneInEightBillionClient upstream;

	@Test
	void generatesOnceAndContinuesWithTheEncryptedCursor() throws Exception {
		when(upstream.available()).thenReturn(true);
		when(upstream.newborn(anyLong())).thenReturn(json.readTree(START));
		when(upstream.jobLabels()).thenReturn(Map.of("clerk", "офисный работник"));
		when(upstream.live(anyMap())).thenReturn(json.readTree(FIRST_YEAR), json.readTree(SECOND_YEAR),
				json.readTree(NEXT), json.readTree(END));

		var owner = guest();
		var startKey = UUID.randomUUID();
		var initial = command(GENERATED, owner, startKey, "{}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.provider").value("1in8b"))
				.andExpect(jsonPath("$.character.birthplace.countryCode").value("CN"))
				.andExpect(jsonPath("$.character.birthplace.city").value("Пекин"))
				.andExpect(jsonPath("$.character.background.family").value("Мать, отец отсутствует"))
				.andExpect(jsonPath("$.character.background.siblings").value("2"))
				.andExpect(jsonPath("$.character.background.wealth").value("Доходная группа 3 из 5"))
				.andExpect(jsonPath("$.character.background.religion").value("Буддизм"))
				.andExpect(jsonPath("$.character.background.father").value("офисный работник"))
				.andExpect(jsonPath("$.character.background.mother").value("ведение домашнего хозяйства"))
				.andExpect(jsonPath("$.character.birthDetails.placeType").value("URBAN"))
				.andExpect(jsonPath("$.character.birthDetails.familyType").value("MOTHER_AND_FATHER"))
				.andExpect(jsonPath("$.character.birthDetails.familyIncomeQuintile").value(3))
				.andExpect(jsonPath("$.character.birthDetails.fatherPresent").value(false))
				.andExpect(jsonPath("$.character.birthDetails.mother.age").value(30))
				.andExpect(jsonPath("$.character.birthDetails.father.occupation.code").value("clerk"))
				.andExpect(jsonPath("$.character.birthDetails.father.livesWithChild").value(false))
				.andExpect(jsonPath("$.character.birthDetails.siblings[0].sex").value("FEMALE"))
				.andExpect(jsonPath("$.character.birthDetails.household.size").value(4))
				.andExpect(jsonPath("$.character.birthDetails.homeOwned").value(false))
				.andExpect(jsonPath("$.character.birthDetails.electricityAtHome").value(true))
				.andExpect(jsonPath("$.character.birthDetails.group.name").value("Группа"))
				.andExpect(jsonPath("$.character.birthDetails.disabilityCodes.length()").value(0))
				.andExpect(jsonPath("$.state.age").value(0))
				.andExpect(jsonPath("$.state.stats.appearance").value(nullValue()))
				.andExpect(jsonPath("$.state.history.length()").value(2))
				.andExpect(jsonPath("$.state.history[1].age").value(0))
				.andExpect(jsonPath("$.state.pendingDecision").value(nullValue()))
				.andReturn().getResponse();
		String session = initial.getHeader(HttpHeaders.LOCATION);
		UUID id = UUID.fromString(session.substring(session.lastIndexOf('/') + 1));
		assertThat(initial.getContentAsString()).doesNotContain("fixture-first-cursor");
		assertThat(jdbc.queryForObject("select engine_token from life_session where id = ?", String.class, id))
				.startsWith("v1.").doesNotContain("fixture-first-cursor");
		Instant expiresAt = cursorExpiry(id);

		command(GENERATED, owner, startKey, "{}").andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		mvc.perform(get(session).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.character.birthDetails.father.occupation.label").value("офисный работник"))
				.andExpect(jsonPath("$.state.age").value(0));
		verify(upstream).newborn(anyLong());
		verify(upstream, never()).live(anyMap());

		String advancePath = session + "/advance";
		command(advancePath, owner, UUID.randomUUID(), """
				{"expectedVersion":1}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.state.age").value(1))
				.andExpect(jsonPath("$.state.pendingDecision").value(nullValue()));
		command(advancePath, owner, UUID.randomUUID(), """
				{"expectedVersion":2}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.state.age").value(2))
				.andExpect(jsonPath("$.state.pendingDecision.options[0].id").value("read"));
		command(advancePath, owner, UUID.randomUUID(), """
				{"expectedVersion":3}
				""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DECISION_REQUIRED"));
		String answerPath = session + "/decisions/first/answers";
		var answerKey = UUID.randomUUID();
		String answer = """
				{"expectedVersion":3,"optionId":"read"}
				""";
		command(answerPath, owner, answerKey, answer).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.age").value(3))
				.andExpect(jsonPath("$.state.history.length()").value(5))
				.andExpect(jsonPath("$.state.stats.intelligence").value(28));
		command(answerPath, owner, answerKey, answer).andExpect(status().isOk())
				.andExpect(jsonPath("$.state.history.length()").value(5));
		assertThat(cursorExpiry(id)).isEqualTo(expiresAt);

		command(advancePath, owner, UUID.randomUUID(), """
				{"expectedVersion":4}
				""").andExpect(status().isOk())
				.andExpect(jsonPath("$.state.status").value("COMPLETE"))
				.andExpect(jsonPath("$.state.age").value(4))
				.andExpect(jsonPath("$.state.history.length()").value(6));
		assertThat(jdbc.queryForObject("select engine_token from life_session where id = ?", String.class, id)).isNull();
		verify(upstream).live(Map.of("token", "fixture-first-cursor", "until", "year"));
		verify(upstream).live(Map.of("token", "fixture-year-one", "until", "year"));
		verify(upstream).live(Map.of("token", "fixture-year-two", "until", "year", "choose", "read"));
		verify(upstream).live(Map.of("token", "fixture-second-cursor", "until", "year"));
		verify(upstream, times(4)).live(anyMap());
	}

	@Test
	void readsProviderSnapshotWithoutCallingEngineOrInventingARubleWallet() throws Exception {
		when(upstream.available()).thenReturn(true);
		when(upstream.newborn(anyLong())).thenReturn(json.readTree(START));
		when(upstream.live(anyMap())).thenReturn(json.readTree(FIRST_YEAR));
		var owner = guest();
		String session = command(GENERATED, owner, UUID.randomUUID(), "{}").andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		mvc.perform(get(session + "/finances").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("PPP_USD"))
				.andExpect(jsonPath("$.summary.income").value(nullValue()));
		verify(upstream, never()).live(anyMap());
		command(session + "/advance", owner, UUID.randomUUID(), "{\"expectedVersion\":1}")
				.andExpect(status().isOk());
		mvc.perform(get(session + "/finances").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.income").value(1200))
				.andExpect(jsonPath("$.summary.netWorth").value(215.5))
				.andExpect(jsonPath("$.summary.availableBalance").value(nullValue()))
				.andExpect(jsonPath("$.summary.expenses").value(nullValue()))
				.andExpect(jsonPath("$.categories[0].id").value("provider"));
		mvc.perform(get(session + "/spending/provider").cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.offers[0].price").value(20.5))
				.andExpect(jsonPath("$.offers[0].purchasable").value(false));
		command(session + "/purchases", owner, UUID.randomUUID(),
				"{\"expectedVersion\":2,\"categoryId\":\"provider\",\"itemId\":\"test\"}")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPENDING_READ_ONLY"));
		verify(upstream, times(1)).live(anyMap());
	}

	@Test
	void keepsDisplayEditsAcrossExternalStepsWithoutChangingEngineStats() throws Exception {
		when(upstream.available()).thenReturn(true);
		when(upstream.newborn(anyLong())).thenReturn(json.readTree(START));
		when(upstream.live(anyMap())).thenReturn(json.readTree(FIRST_YEAR));
		var owner = guest();
		String session = command(GENERATED, owner, UUID.randomUUID(), "{}").andExpect(status().isCreated())
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		var id = UUID.fromString(session.substring(session.lastIndexOf('/') + 1));
		String cursor = jdbc.queryForObject("select engine_token from life_session where id = ?", String.class, id);
		editCharacter(owner, session, UUID.randomUUID(), """
				{"expectedVersion":1,"changes":{"name":"Лина","stats":{"health":20}}}
				""").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARACTER_FIELDS_READ_ONLY"));
		editCharacter(owner, session, UUID.randomUUID(), """
				{"expectedVersion":1,"changes":{"name":"Лина","stats":{"appearance":84}}}
				""").andExpect(status().isOk())
				.andExpect(jsonPath("$.character.name").value("Лина"))
				.andExpect(jsonPath("$.character.birthDetails.mother.age").value(30))
				.andExpect(jsonPath("$.state.stats.appearance").value(84))
				.andExpect(jsonPath("$.state.stats.health").value(81))
				.andExpect(jsonPath("$.version").value(2));
		assertThat(jdbc.queryForObject("select engine_token from life_session where id = ?", String.class, id))
				.isEqualTo(cursor);
		verify(upstream, never()).live(anyMap());
		command(session + "/advance", owner, UUID.randomUUID(), """
				{"expectedVersion":2}
				""").andExpect(status().isOk())
				.andExpect(jsonPath("$.character.name").value("Лина"))
				.andExpect(jsonPath("$.character.birthDetails.placeType").value("URBAN"))
				.andExpect(jsonPath("$.state.stats.appearance").value(84))
				.andExpect(jsonPath("$.state.stats.intelligence").value(12));
	}

	@Test
	void handlesMissingBirthValuesAndRestoresSavesCreatedBeforeBirthDetails() throws Exception {
		when(upstream.available()).thenReturn(true);
		when(upstream.jobLabels()).thenReturn(Map.of());
		when(upstream.newborn(anyLong())).thenReturn(json.readTree("""
				{
				  "state": {"age":0, "born_year":2026, "born_in":"IND", "place":"village", "name":"Тест", "sex":"F",
				            "health":70, "happiness":60, "knowledge":50, "bonds":55,
				            "mother":{"age":23,"job":"unknown_job"}, "who":{"caste":{"id":"general","name":"Общая категория"}}},
				  "family_at_birth": {"older_siblings":0}, "token":"fixture-cursor"
				}
				"""));
		var owner = guest();
		String session = command(GENERATED, owner, UUID.randomUUID(), "{}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.character.birthplace.city").value(nullValue()))
				.andExpect(jsonPath("$.character.birthDetails.placeType").value("RURAL"))
				.andExpect(jsonPath("$.character.birthDetails.olderSiblingCount").value(0))
				.andExpect(jsonPath("$.character.birthDetails.caste.code").value("general"))
				.andExpect(jsonPath("$.character.birthDetails.mother.age").value(23))
				.andExpect(jsonPath("$.character.birthDetails.mother.occupation.code").value("unknown_job"))
				.andExpect(jsonPath("$.character.birthDetails.mother.occupation.label").value(nullValue()))
				.andExpect(jsonPath("$.character.birthDetails.father").value(nullValue()))
				.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
		var id = UUID.fromString(session.substring(session.lastIndexOf('/') + 1));
		jdbc.update("update life_session set setup = setup - 'birthDetails' where id = ?", id);

		mvc.perform(get(session).cookie(owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.character.birthDetails").value(nullValue()));
		verify(upstream).newborn(anyLong());
		verify(upstream, never()).live(anyMap());
	}

	private ResultActions editCharacter(Cookie owner, String session, UUID key, String body) throws Exception {
		return mvc.perform(patch(session + "/character").cookie(owner).header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private Instant cursorExpiry(UUID id) {
		return jdbc.queryForObject("select engine_token_expires_at from life_session where id = ?",
				(result, row) -> result.getTimestamp(1).toInstant(), id);
	}

	private Cookie guest() throws Exception {
		String cookie = mvc.perform(post("/api/v1/guest").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
		return MockCookie.parse(cookie);
	}

	private ResultActions command(String path, Cookie owner, UUID key, String body) throws Exception {
		return mvc.perform(post(path).cookie(owner).header("Idempotency-Key", key.toString())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}
}
