package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HubChoicesTest {
    @Test public void artMinutesAreOneOfTheOfferedOrTheDefault() {
        assertEquals(1, HubChoices.artMinutes(null));
        assertEquals(5, HubChoices.artMinutes("5"));
        assertEquals(20, HubChoices.artMinutes(" 20 "));
        assertEquals(1, HubChoices.artMinutes("7"));
        assertEquals(1, HubChoices.artMinutes("soon"));
    }

    @Test public void settingsButtonOpensTheMenuUnlessGoogleTvIsChosen() {
        assertEquals("hub", HubChoices.button(null));
        assertEquals("hub", HubChoices.button("anything"));
        assertEquals("system", HubChoices.button("system"));
    }

    @Test public void theOtherPlaceIsAlwaysOneHoldAway() {
        assertEquals(HubChoices.OPEN_HUB, HubChoices.settingsTarget("hub", false));
        assertEquals(HubChoices.OPEN_LAUNCHER, HubChoices.settingsTarget("hub", true));
        assertEquals(HubChoices.OPEN_SYSTEM, HubChoices.settingsTarget("system", false));
        assertEquals(HubChoices.OPEN_HUB, HubChoices.settingsTarget("system", true));
    }

    @Test public void leftAndRightWrapAroundTheChoices() {
        assertEquals(1, HubChoices.step(0, 3, 1));
        assertEquals(0, HubChoices.step(2, 3, 1));
        assertEquals(2, HubChoices.step(0, 3, -1));
        assertEquals(0, HubChoices.step(0, 0, 1));
    }
}
