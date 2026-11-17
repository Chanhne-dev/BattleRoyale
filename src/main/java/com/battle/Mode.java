package com.battle;

public enum Mode {
    SOLO("solo", 1, true),
    DUO("duo", 2, false);
    // SQUAD("squad", 4, false) -> chờ phát triển

    private final String id;
    private final int teamSize;
    private final boolean friendlyFire;

    Mode(String id, int teamSize, boolean friendlyFire) {
        this.id = id;
        this.teamSize = teamSize;
        this.friendlyFire = friendlyFire;
    }

    public String id() { return id; }
    public int teamSize() { return teamSize; }
    public boolean friendlyFire() { return friendlyFire; }

    public static Mode fromId(String s) {
        for (Mode m : values()) if (m.id.equalsIgnoreCase(s)) return m;
        return SOLO;
    }
}
