package com.jjricks.volt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The owner names each drive mode their scooter reports, one press of the scooter's own mode button
 * at a time. Volt sends nothing; it only watches the mode number in the scooter's settings read.
 * Model numbering differs (the UT5 MAX reports 2 Eco, 3 Sport, 11 Walk), so no mapping is assumed.
 */
final class DriveModeSurvey {
    enum Label {
        WALK("Walk"), ECO("Eco"), DRIVE("Drive"), SPORT("Sport"), UNSURE("Not sure");
        final String text;
        Label(String text) { this.text = text; }
    }
    private final LinkedHashMap<Integer, Label> named = new LinkedHashMap<>();
    private Integer asking, last;
    private boolean closed;

    /** A fresh reading of the reported mode; true when the owner should now name it. */
    synchronized boolean observe(int code) {
        if (closed) return false;
        if (asking != null) { asking = code; return true; } // follow the live mode while asking
        if (last != null && code == last) return false;     // still waiting for the next press
        if (named.containsKey(code)) {
            // Back at the first mode after naming others: the scooter has shown every mode it cycles through.
            if (code == named.keySet().iterator().next() && named.size() >= 2) closed = true;
            last = code;
            return false;
        }
        asking = code;
        return true;
    }

    /** Names the mode being asked about; a label already used (other than Not sure) is refused. */
    synchronized boolean name(Label label) {
        if (asking == null || label == null || used(label)) return false;
        named.put(asking, label); last = asking; asking = null;
        return true;
    }

    synchronized Integer asking() { return asking; }
    synchronized boolean closed() { return closed; }
    synchronized int count() { return named.size(); }
    synchronized boolean used(Label label) { return label != Label.UNSURE && named.containsValue(label); }

    /** "Eco → Drive → Sport", in the order the scooter showed them. */
    synchronized String summary() {
        StringBuilder out = new StringBuilder();
        for (Label label : named.values()) out.append(out.length() == 0 ? "" : " → ").append(label.text);
        return out.toString();
    }

    /** Each reported mode number with its name, in the order the scooter showed them (OwnerNotes writes them out). */
    synchronized Map<Integer, Label> named() { return new LinkedHashMap<>(named); }
}
