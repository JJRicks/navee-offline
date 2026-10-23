package com.jjricks.volt;

/**
 * Volt's offer, after connecting to a model it has no compatibility report for, to run the
 * read-only check and email the redacted report. Decisions only; MainActivity shows the offer.
 */
final class ReportInvite {
    static final String ADDRESS = "volt-reports@jjricks.com";
    /** app_settings key prefix, one answer per Model ID: "never", "sent" or "later:<wall ms>". */
    static final String PREFIX = "report_invite_";
    /** Not now asks again after three days. */
    static final long LATER_MS = 3L * 24 * 60 * 60 * 1000;
    private ReportInvite() {}

    /** The advertised Model ID to ask about, or "" when Volt already has a report or cannot check this scooter. */
    static String model(DeviceDescriptor d) {
        if (d == null || d.simulated || d.pidSource() != DeviceDescriptor.PidSource.ADVERTISED || d.conflict()
                || ModelRegistry.reported(d)) return "";
        ModelRegistry.Layer layer = ModelRegistry.resolve(d).layer;
        return layer == ModelRegistry.Layer.OEM_GROUP || layer == ModelRegistry.Layer.SHARED ? d.rulePid() : "";
    }

    /** Whether a stored answer still lets Volt ask; an unreadable answer never nags. */
    static boolean due(String stored, long nowMs) {
        if (stored == null || stored.isEmpty()) return true;
        if (!stored.startsWith("later:")) return false;
        try { return nowMs - Long.parseLong(stored.substring(6)) >= LATER_MS; }
        catch (NumberFormatException unreadable) { return false; }
    }

    static String later(long nowMs) { return "later:" + nowMs; }
    static String subject(String model) { return "Volt compatibility report · Model " + model; }
    /** Scooters report only a numeric Model ID, so the email asks the owner for the name they know it by. */
    static final String NAME_PROMPT = "Scooter model, as the NAVEE app or the scooter's label shows it (optional): ";

    /** The whole email: a short note, then the report exactly as the compatibility preview shows it. */
    static String body(CompatibilityReport report) { return body(report, null, null, ""); }

    /** The email: the model the owner picked (or a prompt to type it), the report, then anything else the owner added. */
    static String body(CompatibilityReport report, NaveeModels.Choice model, DriveModeSurvey modes, String modelId) {
        return OwnerNotes.firstLine(model) + "\n\nThis came from Volt's read-only compatibility check. It is everything Volt sends; nothing is attached.\n\n"
                + report.summary() + "\n" + report.json() + OwnerNotes.section(modelId, model, modes);
    }
}
