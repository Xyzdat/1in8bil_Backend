package com.life.life_backend.economy;

import com.life.life_backend.engine.LifeState;
import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.life.life_backend.economy.LocalBudget.money;

@Component
public class SpendingService {
	private static final String PROVIDER_CATEGORY = "provider";
	private final SpendingCatalog catalog;

	SpendingService(SpendingCatalog catalog) {
		this.catalog = catalog;
	}

	public FinanceView finances(LocalContext context) {
		var budget = context.budget();
		var categories = catalog.sections().stream().map(section -> category(section, context)).toList();
        return new FinanceView(context.version(), context.state().age(), FinanceView.Currency.RUB,
                com.life.life_backend.engine.adult.AdultView.finances(context.adult(),budget),
				budget.entries().reversed().stream().map(entry->entry.view(budget.purchases())).toList(),
				budget.purchases().reversed().stream().map(purchase -> purchase.view(budget.month())).toList(),
				categories, context.location());
	}

	public FinanceView providerFinances(long version, LifeState state, ProviderEconomy snapshot) {
		var summary = snapshot == null
				? new FinanceView.Summary(null, null, null, null, null, null, null, null, null, null)
				: new FinanceView.Summary(snapshot.income(), null, null, null, null, null, snapshot.netWorth(),
						snapshot.occupation(), null, snapshot.age());
		var categories = snapshot == null || snapshot.options() == null || snapshot.options().isEmpty()
				? List.<FinanceView.Category>of() : List.of(providerCategory());
		return new FinanceView(version, state.age(), FinanceView.Currency.PPP_USD, summary, null, null, categories, null);
	}

	public FinanceView.Shop shop(LocalContext context, String categoryId) {
		var section = section(categoryId);
		return new FinanceView.Shop(context.version(), context.state().age(), FinanceView.Currency.RUB,
				category(section, context), section.heading(), section.description(), section.confirm(),
				money(context.budget().available()), context.state().stats(),
				section.id().equals("pets") ? catalog.petNames() : List.of(),
				section.itemsAt(context.location()).stream().map(item -> offer(section, item, context)).toList());
	}

	public FinanceView.Shop providerShop(long version, LifeState state, ProviderEconomy snapshot, String categoryId) {
		if (!PROVIDER_CATEGORY.equals(categoryId) || snapshot == null || snapshot.options() == null) {
			throw unknownCategory();
		}
		var offers = snapshot.options().stream().map(option -> new FinanceView.Offer(option.id(), option.name(),
				option.description(), null, option.price(), null, null, null, null, null, false,
				Boolean.FALSE.equals(option.available()) && option.reason() != null ? option.reason()
						: "Оплата через 1in8b пока недоступна.", null)).toList();
		return new FinanceView.Shop(version, state.age(), FinanceView.Currency.PPP_USD, providerCategory(),
				"Варианты 1in8b Engine", "Предложения из последнего сохранённого состояния.",
				null, null, state.stats(), List.of(), offers);
	}

	public Receipt purchase(LocalContext context, UUID requestKey, String categoryId, String itemId, String petName) {
		var section = section(categoryId);
		var item = section.itemsAt(context.location()).stream().filter(candidate -> candidate.id().equals(itemId))
				.findFirst().orElseThrow(() -> new ApiFailure(HttpStatus.NOT_FOUND, "SPENDING_ITEM_NOT_FOUND",
						"The spending item is not available in this category or location."));
		var blocked = restriction(section, item, context);
		if (blocked != null) {
			throw new ApiFailure(HttpStatus.CONFLICT, blocked.code(), blocked.message());
		}
		if (section.id().equals("pets") ? petName == null || petName.isBlank() : petName != null) {
			throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_PET_NAME", "A pet name is required only for pets.");
		}
		var purchase = new LocalBudget.Purchase(requestKey, context.budget().month(), section.id(), item.id(),
				item.title(), item.icon(), item.price() * 100, item.yearlyCost() == null ? 0 : item.yearlyCost(),
				item.durationMonths(), petName, item.location());
		return new Receipt(context.budget().book(purchase), purchase, item.effects());
	}

	private SpendingCatalog.Section section(String id) {
		return catalog.section(id).orElseThrow(this::unknownCategory);
	}

	private FinanceView.Category category(SpendingCatalog.Section section, LocalContext context) {
		var actions = section.id().equals("relationships")
				? section.items().stream().map(item -> offer(section, item, context)).toList() : List.<FinanceView.Offer>of();
		var prices = section.itemsAt(context.location()).stream().mapToLong(SpendingCatalog.Item::price).min();
		BigDecimal priceFrom = prices.isPresent() ? BigDecimal.valueOf(prices.getAsLong()) : null;
		return new FinanceView.Category(section.id(), section.groupId(), section.groupTitle(), section.title(),
				section.actionTitle(), section.summary(), section.icon(), section.minAge(), priceFrom, actions);
	}

	private FinanceView.Category providerCategory() {
		return new FinanceView.Category(PROVIDER_CATEGORY, PROVIDER_CATEGORY, "Предложения 1in8b", "Траты 1in8b",
				"Посмотреть предложения", null, null, null, null, List.of());
	}

	private FinanceView.Offer offer(SpendingCatalog.Section section, SpendingCatalog.Item item, LocalContext context) {
		var blocked = restriction(section, item, context);
		long remaining = context.budget().available() - item.price() * 100;
		return new FinanceView.Offer(item.id(), item.title(), item.description(), item.icon(),
				BigDecimal.valueOf(item.price()), item.yearlyCost() == null ? null : BigDecimal.valueOf(item.yearlyCost()),
				item.durationMonths(), item.minAge(), item.effects(), item.location(), blocked == null,
				blocked == null ? null : blocked.message(), remaining < 0 ? null : money(remaining));
	}

	private Restriction restriction(SpendingCatalog.Section section, SpendingCatalog.Item item, LocalContext context) {
		if (context.state().status() == LifeState.Status.COMPLETE) {
			return new Restriction("SESSION_COMPLETE", "Жизнь завершена.");
		}
		if (context.state().age() < item.minAge()) {
			return new Restriction("SPENDING_AGE_RESTRICTED", "Доступно с " + item.minAge() + " лет.");
		}
		if (List.of("property", "education", "hobby").contains(section.id())
				&& context.budget().purchases().stream().anyMatch(purchase -> purchase.categoryId().equals(section.id())
						&& purchase.soldMonth()==null && purchase.itemId().equals(item.id()))) {
			return new Restriction("SPENDING_ALREADY_OWNED", "Уже приобретено.");
		}
		if (item.location() != null && context.location().countryCode().equals(item.location().countryCode())
				&& (section.id().equals("country") || item.location().city().equalsIgnoreCase(context.location().city()))) {
			return new Restriction("SPENDING_CURRENT_LOCATION", "Ты уже живёшь здесь.");
		}
		if (context.budget().available() < item.price() * 100) {
			return new Restriction("SPENDING_INSUFFICIENT_FUNDS", "Недостаточно средств.");
		}
		return null;
	}

	private ApiFailure unknownCategory() {
		return new ApiFailure(HttpStatus.NOT_FOUND, "SPENDING_CATEGORY_NOT_FOUND", "The spending category is not available.");
	}

	private record Restriction(String code, String message) {
	}

	public record LocalContext(long version, LifeState state, LocalBudget budget, FinanceView.Location location,
            com.life.life_backend.engine.adult.AdultState adult) {
        public LocalContext(long version,LifeState state,LocalBudget budget,FinanceView.Location location) {this(version,state,budget,location,null);}
	}

	public record Receipt(LocalBudget budget, LocalBudget.Purchase purchase, FinanceView.Effects effects) {
	}
}
