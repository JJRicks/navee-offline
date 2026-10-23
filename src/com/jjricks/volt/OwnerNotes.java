package com.jjricks.volt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the owner told Volt about their scooter (its model and drive-mode names), added to a report they choose to email. */
final class OwnerNotes {
    static final String HEADING = "The owner's answers:";
    private OwnerNotes() {}

    static boolean any(NaveeModels.Choice model, DriveModeSurvey modes) {
        return model != null && model.known() || modes != null && modes.count() > 0;
    }

    /** The email's first line: the model the owner picked, or a prompt to type it when they didn't. */
    static String firstLine(NaveeModels.Choice model) {
        return model != null && model.known() ? "Scooter model, from the owner: " + model.name : ReportInvite.NAME_PROMPT;
    }

    /** One block for both answers, keyed to the advertised Model ID when the scan saw one. */
    static String json(String modelId, NaveeModels.Choice model, DriveModeSurvey modes) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("format", "volt-owner-notes-v1");
        tree.put("pid", modelId == null || modelId.isEmpty() ? "unknown" : modelId);
        if (model != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", model.source.name().toLowerCase(java.util.Locale.ROOT));
            if (model.known()) m.put("name", model.name);
            tree.put("model", m);
        }
        if (modes != null && modes.count() > 0) {
            List<Object> named = new ArrayList<>();
            for (Map.Entry<Integer, DriveModeSurvey.Label> e : modes.named().entrySet()) {
                Map<String, Object> mode = new LinkedHashMap<>();
                mode.put("code", (long) e.getKey()); mode.put("label", e.getValue().name().toLowerCase(java.util.Locale.ROOT));
                named.add(mode);
            }
            tree.put("drive_modes", named);
            tree.put("drive_mode_cycle_closed", modes.closed());
        }
        return CompatibilityReport.json(tree);
    }

    /** The email so far, then one blank line, "The owner's answers:", "Drive modes: …" and the block; unchanged when the owner added nothing. */
    static String appendTo(String email, String modelId, NaveeModels.Choice model, DriveModeSurvey modes) {
        if (!any(model, modes)) return email;
        return email.replaceAll("\\s+$", "") + "\n\n" + HEADING + "\n" + (modes != null && modes.count() > 0 ? "Drive modes: " + modes.summary() + "\n" : "")
                + json(modelId, model, modes);
    }
}
