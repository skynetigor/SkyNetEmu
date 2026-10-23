package com.winlator.cmod.telemetry;

/** What the user is told about telemetry, in the onboarding question, the upgrade question and Settings. */
public final class TelemetryText {
    public static final String TITLE = "Help improve SkyNetEmu?";

    public static final String SENT =
            "Sent, without your name or any account:\n"
            + "\u2022 your phone model, processor, GPU, memory and Android version\n"
            + "\u2022 which game (from the list) and which setup you chose\n"
            + "\u2022 how a play session went: how long it took to start, average FPS, temperature, memory, how it ended\n"
            + "\u2022 errors the app ran into, with file paths removed\n"
            + "\u2022 a random ID made on this phone, so sessions of one phone can be told apart";

    public static final String NOT_SENT =
            "Never sent: file or folder names, the names of your games, shortcuts or containers, anything you type, logs, or your IP address.";

    public static final String CHANGE = "Off by default. You can change this any time in Settings > Privacy.";

    private TelemetryText() {}
}
