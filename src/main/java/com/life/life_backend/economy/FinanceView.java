package com.life.life_backend.economy;

import com.life.life_backend.engine.LifeState;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FinanceView(long version, int age, Currency currency, Summary summary, List<Transaction> ledger,
		List<PurchasedItem> purchases, List<Category> categories, Location location) {
	public enum Currency { RUB, PPP_USD }

	public record Summary(BigDecimal income, BigDecimal expenses, Integer periodMonths, BigDecimal savings,
			BigDecimal debt, BigDecimal availableBalance, BigDecimal netWorth, String occupation,
			Integer depositRateBasisPoints, Integer asOfAge) {
	}

	public record Transaction(String id, int month, String kind, String title, BigDecimal income, BigDecimal expense, String flow) {
		public Transaction(String id,int month,String kind,String title,BigDecimal income,BigDecimal expense) {this(id,month,kind,title,income,expense,null);}
	}

	public record PurchasedItem(UUID id, int month, String categoryId, String itemId, String title, String icon,
			BigDecimal price, BigDecimal yearlyCost, Integer durationMonths, String petName, String status,
			Location location) {
	}

	public record Category(String id, String groupId, String groupTitle, String title, String actionTitle,
			String description, String icon, Integer minAge, BigDecimal priceFrom,
			List<Offer> actions) {
	}

	public record Location(String countryCode, String country, String city) {
	}

	public record Shop(long version, int age, Currency currency, Category category, String heading,
			String description, String confirmLabel, BigDecimal availableBalance, LifeState.Stats stats,
			List<String> suggestedPetNames, List<Offer> offers) {
	}

	public record Offer(String id, String title, String description, String icon, BigDecimal price,
			BigDecimal yearlyCost, Integer durationMonths, Integer minAge, Effects effects, Location location,
			boolean purchasable, String unavailableReason, BigDecimal balanceAfterPurchase) {
	}

	public record Effects(Integer health, Integer happiness, Integer intelligence, Integer relationships,
			Integer appearance) {
		public LifeState.Stats apply(LifeState.Stats stats) {
			return new LifeState.Stats(update(stats.health(), health), update(stats.happiness(), happiness),
					update(stats.intelligence(), intelligence), update(stats.relationships(), relationships),
					stats.appearance() == null ? null : update(stats.appearance(), appearance), stats.aura());
		}

		private int update(int current, Integer delta) {
			return delta == null ? current : Math.clamp(current + delta, 0, 100);
		}
	}
}
