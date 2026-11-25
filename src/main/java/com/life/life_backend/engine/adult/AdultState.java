package com.life.life_backend.engine.adult;

import java.util.*;

/** Immutable checkpoint. Money here describes obligations/assets; spendable cash lives only in LocalBudget. */
public record AdultState(String modelVersion, int activatedMonth, int serial, Traits traits,
        Education education, Career career, Partnership partnership, Map<String, Npc> npcs,
        List<Disease> diseases, List<Debt> debts, List<Asset> assets, List<Story> stories,
        List<Memory> memories, Pending pending, String housing, Set<String> flags, Map<String, ContactState> contacts) {
    public static final String VERSION = "adult-1";
    public AdultState {
        npcs = Map.copyOf(npcs); diseases = List.copyOf(diseases); debts = List.copyOf(debts);
        assets = List.copyOf(assets); stories = List.copyOf(stories); memories = List.copyOf(memories);
        flags = Set.copyOf(flags);
        contacts = contacts == null ? Map.of() : Map.copyOf(contacts);
    }
    /** A regular shop purchase is reflected immediately, without a time step or second debit. */
    public AdultState withPurchase(com.life.life_backend.economy.LocalBudget.Purchase purchase) {
        if(!purchase.categoryId().equals("property")||assets.stream().anyMatch(a->purchase.id().toString().equals(a.purchaseId())))return this;
        var updated=new ArrayList<>(assets);
        updated.add(new Asset("asset-"+(serial+1),"PROPERTY",purchase.title(),purchase.price(),purchase.price(),purchase.month(),100,purchase.id().toString()));
        return new AdultState(modelVersion,activatedMonth,serial+1,traits,education,career,partnership,npcs,diseases,debts,updated,stories,memories,pending,housing,flags,contacts);
    }
    public record Traits(int diligence, int sociability, int openness, int resilience) { }
    public record Education(String level, String field, Integer startedMonth, Integer completesMonth, String status) { }
    public record Employment(String id, String occupation, String employer, int beganMonth, Integer endedMonth, String endReason) { }
    public record Career(String status, String occupation, String industry, String employer, String position,
            long monthlyPay, int beganMonth, int experienceMonths, int qualification, int satisfaction,
            int stability, int risk, long monthlyPension, List<Employment> history) {
        public Career { history = List.copyOf(history); }
    }
    public record Partnership(String partnerId, String status, int beganMonth, boolean cohabiting,
            int satisfaction, int conflict, List<String> milestones) {
        public Partnership { milestones = List.copyOf(milestones); }
    }
    public record Npc(String id, Traits traits, String education, String occupation, long monthlyIncome,
            int health, String maritalStatus, String partnerId, String countryIso2, List<String> parentIds,
            long estate, Set<String> milestones) {
        public Npc { parentIds = List.copyOf(parentIds); milestones = Set.copyOf(milestones); }
    }
    public record Disease(String id, String kind, int onsetMonth, int severity, String stage,
            String treatment, int reviewMonth, Integer endedMonth, boolean chronic, int workCapacity) { }
    public record Debt(String id, String kind, long principal, int annualBasisPoints, long monthlyPayment,
            int beganMonth, int missedPayments, String collateralId) { }
    public record Asset(String id, String kind, String title, long purchasePrice, long value,
            int acquiredMonth, int ownershipPercent, String purchaseId) { }
    public record Story(String id, String template, String stage, String status, int beganMonth,
            int dueMonth, Integer endedMonth, List<String> participants, Map<String, String> variables,
            List<String> visitedStages) {
        public Story { participants = List.copyOf(participants); variables = Map.copyOf(variables); visitedStages = List.copyOf(visitedStages); }
    }
    public record Memory(String id, int month, String kind, String personId, String text) { }
    public record Pending(String storyId, String stage, List<String> options) {
        public Pending { options = List.copyOf(options); }
    }
}
