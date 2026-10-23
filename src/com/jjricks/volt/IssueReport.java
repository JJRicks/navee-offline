package com.jjricks.volt;

/**
 * More › Report an issue: an email draft to Volt's report address for the owner to fill in. It
 * carries only what helps reproduce a problem: Volt's version, the Android version and phone
 * model, and the selected scooter's model name when Volt knows it. Never a serial, MAC, owner
 * key, PIN or name.
 */
final class IssueReport {
    private IssueReport() {}

    static String subject(String appVersion) { return "Volt issue report · Volt " + appVersion; }

    /** scooterModel is ModelRegistry.modelLine for the selected scooter ("" when none is known). */
    static String body(String appVersion, String androidRelease, int sdk, String phone, String scooterModel, boolean simulated) {
        StringBuilder out = new StringBuilder("What happened, and what did you expect instead?\n\n\n\n");
        out.append("Volt ").append(appVersion).append(" · Android ").append(androidRelease).append(" (API ").append(sdk).append(") · ").append(phone.trim());
        if (scooterModel != null && !scooterModel.isEmpty()) out.append("\nScooter: ").append(scooterModel).append(simulated ? " (simulated)" : "");
        return out.append('\n').toString();
    }
}
