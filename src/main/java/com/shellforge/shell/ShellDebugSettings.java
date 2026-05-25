package com.shellforge.shell;

/**
 * Server-wide debug toggle for shell blueprint placement logging.
 */
public final class ShellDebugSettings {
    private static boolean enabled;

    private ShellDebugSettings() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
