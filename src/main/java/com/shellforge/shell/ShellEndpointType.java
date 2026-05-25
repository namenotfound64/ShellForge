package com.shellforge.shell;

import java.util.Locale;

/** Endpoint kinds discovered during {@code /shellforge copy} and stored on bindings. */
public enum ShellEndpointType {
    STRESS,
    REDSTONE,
    FLUID;

    public static ShellEndpointType byName(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
