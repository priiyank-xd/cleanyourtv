package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LauncherAppTest {
    @Test public void dropsTheSamePressFromAnotherDeviceOnly() {
        long t = 1_000_000;
        assertFalse(LauncherApp.echo(22, 1, t));          // Bluetooth
        assertTrue(LauncherApp.echo(22, 2, t + 40));      // IR copy of the same press
        assertFalse(LauncherApp.echo(22, 1, t + 300));    // next real press
        assertFalse(LauncherApp.echo(22, 1, t + 350));    // fast repeat on the same device is real
        assertFalse(LauncherApp.echo(21, 2, t + 360));    // another key is real
    }
}
