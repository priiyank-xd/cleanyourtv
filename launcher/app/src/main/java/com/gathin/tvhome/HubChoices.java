package com.gathin.tvhome;

/**
 * The settings menu's choices for the LG-style home (fork only), kept apart from the views so they are unit tested:
 * how often movie art changes, and what the Settings button opens on a press and on a hold.
 */
final class HubChoices {
    /** Minutes between movie-art backdrops the menu offers; the first is the default. */
    static final int[] ART_MINUTES = {1, 5, 20};
    /** What a press of Settings on Home opens: this menu (default) or Google TV's settings. */
    static final String BUTTON_HUB = "hub", BUTTON_SYSTEM = "system";
    /** Targets of the Settings button. */
    static final String OPEN_HUB = "hub", OPEN_SYSTEM = "system", OPEN_LAUNCHER = "launcher";

    private HubChoices() {
    }

    /** The saved interval when it is one the menu offers, else the default. */
    static int artMinutes(String saved) {
        if (saved != null) {
            try {
                int m = Integer.parseInt(saved.trim());
                for (int offered : ART_MINUTES) if (offered == m) return m;
            } catch (NumberFormatException ignored) {
            }
        }
        return ART_MINUTES[0];
    }

    /** "system" when saved so, else "hub". */
    static String button(String saved) {
        return BUTTON_SYSTEM.equals(saved) ? BUTTON_SYSTEM : BUTTON_HUB;
    }

    /**
     * What Settings on Home opens. With the menu as the button's choice, a hold opens the full launcher settings;
     * with Google TV as the choice, a hold opens the menu, so it is always one hold away.
     */
    static String settingsTarget(String button, boolean hold) {
        if (BUTTON_SYSTEM.equals(button)) return hold ? OPEN_HUB : OPEN_SYSTEM;
        return hold ? OPEN_LAUNCHER : OPEN_HUB;
    }

    /** The next of {@code count} choices, Left (-1) or Right (+1), wrapping around. */
    static int step(int index, int count, int dir) {
        if (count <= 0) return 0;
        return ((index + dir) % count + count) % count;
    }
}
