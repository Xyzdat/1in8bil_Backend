package com.life.life_backend.engine.adult;

/** Contact with the player, independent of affection, trust and marital status. */
public record ContactState(Status status, int trackedSinceMonth, Integer lastContactMonth,
        int statusSinceMonth, Reason reason) {
    public enum Status { IN_TOUCH, RARE_CONTACT, NO_CONTACT }
    public enum Reason { INITIALIZED, INTERACTION, HOUSEHOLD, TIME_APART, PLAYER_CHOICE, RECONNECTED }

    static ContactState start(int month, boolean met) {
        return new ContactState(Status.IN_TOUCH, month, met ? month : null, month,
                met ? Reason.INTERACTION : Reason.INITIALIZED);
    }

    ContactState touched(int month, Reason cause) {
        return new ContactState(Status.IN_TOUCH, trackedSinceMonth, month,
                status == Status.IN_TOUCH ? statusSinceMonth : month, cause);
    }

    ContactState withoutContact(int month) {
        if (status == Status.NO_CONTACT) return this;
        int anchor = lastContactMonth == null ? trackedSinceMonth : lastContactMonth;
        Status next = month - anchor >= ContactSystem.LOST_AFTER_MONTHS ? Status.NO_CONTACT
                : month - anchor >= ContactSystem.RARE_AFTER_MONTHS ? Status.RARE_CONTACT : Status.IN_TOUCH;
        return next == status ? this : new ContactState(next, trackedSinceMonth, lastContactMonth, month, Reason.TIME_APART);
    }
}
