package com.life.life_backend.economy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record LocalBudget(int month, long balance, List<Entry> entries, List<Purchase> purchases) {
	static final int RATE_BASIS_POINTS = 100;

	private static final List<Rate> RATES = List.of(
			new Rate(0, 0, 0, "Личный бюджет"),
			new Rate(6, 2400, 1200, "Карманные деньги и личные расходы"),
			new Rate(10, 7200, 4800, "Карманные деньги и личные расходы"),
			new Rate(14, 24000, 18000, "Карманные деньги и личные расходы"),
			new Rate(16, 60000, 42000, "Подработка и личные расходы"),
			new Rate(18, 96000, 84000, "Поиск своего пути"));

	/** Interval settlement for new local lives; the legacy annual formula stays unchanged. */
	public static LocalBudget atMonth(LocalBudget current, int target) {
		var budget = current == null ? new LocalBudget(0, 0, List.of(), List.of()) : current;
		if (target < budget.month) throw new IllegalArgumentException("Budget cannot move backwards");
		var ledger = new ArrayList<>(budget.entries);
		long balance = budget.balance;
		for (int month = budget.month + 1; month <= target; month++) {
			long interest = Math.max(0, balance) * RATE_BASIS_POINTS / 120000;
			var rate = rateAt((month - 1) / 12);
			if (rate.income() != 0 || rate.expense() != 0) {
				var entry = new Entry("budget-" + month, month, "BUDGET", rate.title(),
						installment(rate.income(), month), installment(rate.expense(), month));
				ledger.add(entry);
				balance += entry.income() - entry.expense();
			}
			for (var purchase : budget.purchases) {
				if (purchase.yearlyCost() > 0 && month > purchase.month()) {
					long expense = installment(purchase.yearlyCost(), month - purchase.month());
					ledger.add(new Entry("upkeep-" + purchase.id() + "-" + month, month, "UPKEEP", purchase.title(), 0, expense));
					balance -= expense;
				}
			}
			if (interest > 0) {
				ledger.add(new Entry("interest-" + month, month, "INTEREST", "Проценты на накопления", interest, 0));
				balance += interest;
			}
		}
		return new LocalBudget(target, balance, List.copyOf(ledger), budget.purchases);
	}

	public static LocalBudget atAge(LocalBudget current, int age) {
		var budget = current == null ? new LocalBudget(0, 0, List.of(), List.of()) : current;
		int target = age * 12;
		if (budget.month == target) {
			return budget;
		}
		var ledger = new ArrayList<>(budget.entries);
		long balance = budget.balance;
		for (int openingMonth = budget.month; openingMonth < target; openingMonth += 12) {
			long interest = Math.max(0, balance) * RATE_BASIS_POINTS / 10000;
			var rate = rateAt(openingMonth / 12);
			for (int month = openingMonth + 1; month <= openingMonth + 12; month++) {
				if (rate.income() != 0 || rate.expense() != 0) {
					var entry = new Entry("budget-" + month, month, "BUDGET", rate.title(),
							installment(rate.income(), month), installment(rate.expense(), month));
					ledger.add(entry);
					balance += entry.income() - entry.expense();
				}
				for (var purchase : budget.purchases) {
					if (purchase.yearlyCost() > 0 && month > purchase.month()) {
						long expense = installment(purchase.yearlyCost(), month - purchase.month());
						ledger.add(new Entry("upkeep-" + purchase.id() + "-" + month, month, "UPKEEP",
								"Содержание: " + purchase.title(), 0, expense));
						balance -= expense;
					}
				}
			}
			if (interest > 0) {
				ledger.add(new Entry("interest-" + (openingMonth + 12), openingMonth + 12, "INTEREST",
						"Проценты на накопления", interest, 0));
				balance += interest;
			}
		}
		return new LocalBudget(target, balance, List.copyOf(ledger), budget.purchases);
	}

	LocalBudget book(Purchase purchase) {
		var updatedPurchases = new ArrayList<>(purchases);
		updatedPurchases.add(purchase);
		var ledger = new ArrayList<>(entries);
		ledger.add(new Entry(purchase.id().toString(), month, "PURCHASE", purchase.title(), 0, purchase.price()));
		return new LocalBudget(month, balance - purchase.price(), List.copyOf(ledger), List.copyOf(updatedPurchases));
	}

	long available() {
		return Math.max(0, balance);
	}

	FinanceView.Summary summary() {
		long income = 0;
		long expense = 0;
		for (var entry : entries.reversed()) {
			if (entry.month() <= month - 12) {
				break;
			}
			income += entry.income();
			expense += entry.expense();
		}
		return new FinanceView.Summary(money(income), money(expense), 12, money(available()), money(Math.max(0, -balance)),
				money(available()), null, rateAt(month / 12).title(), RATE_BASIS_POINTS, month / 12);
	}

	private static Rate rateAt(int age) {
		return RATES.reversed().stream().filter(rate -> age >= rate.fromAge()).findFirst().orElseThrow();
	}

	private static long installment(long rubles, int month) {
		int part = Math.floorMod(month - 1, 12) + 1;
		long cents = rubles * 100;
		return cents * part / 12 - cents * (part - 1) / 12;
	}

	static BigDecimal money(long cents) {
		return BigDecimal.valueOf(cents, 2);
	}

	public record Entry(String id, int month, String kind, String title, long income, long expense) {
		FinanceView.Transaction view() {
			return new FinanceView.Transaction(id, month, kind, title, money(income), money(expense));
		}
	}

	public record Purchase(UUID id, int month, String categoryId, String itemId, String title, String icon,
			long price, long yearlyCost, Integer durationMonths, String petName, FinanceView.Location location) {
		public Integer completionMonth() {
			return durationMonths == null ? null : month + durationMonths;
		}

		FinanceView.PurchasedItem view(int currentMonth) {
			String status = completionMonth() == null ? "PURCHASED"
					: currentMonth >= completionMonth() ? "COMPLETED" : "IN_PROGRESS";
			return new FinanceView.PurchasedItem(id, month, categoryId, itemId, title, icon, money(price),
					yearlyCost == 0 ? null : BigDecimal.valueOf(yearlyCost), durationMonths, petName, status, location);
		}
	}

	private record Rate(int fromAge, long income, long expense, String title) {
	}
}
