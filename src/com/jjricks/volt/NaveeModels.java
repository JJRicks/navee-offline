package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * NAVEE's scooter line-up, for the owner to say which scooter a report is about. Scooters report
 * only a numeric Model ID, so the name the owner picks (or types) travels with the report.
 */
final class NaveeModels {
    static final class Series {
        final String name, about; final List<String> models;
        Series(String name, String about, String... models) { this.name = name; this.about = about; this.models = Collections.unmodifiableList(Arrays.asList(models)); }
    }
    static final List<Series> SERIES = Collections.unmodifiableList(Arrays.asList(
            new Series("UT", "High performance", "UT3 Max", "UT5 Max", "UT5 Ultra X"),
            new Series("NT", "All-terrain", "NT5 Max", "NT5 Ultra X"),
            new Series("XT", "Off-road", "XT5 Max", "XT5 Pro", "XT5 Ultra"),
            new Series("N", "SUV-style and older", "N40", "N65", "N65i", "N65i II"),
            new Series("ST", "Premium commuting", "ST3", "ST3 Pro", "ST5 Pro", "ST5 Max"),
            new Series("GT", "Commuting", "GT3", "GT3 Pro", "GT3 Max", "GT5 Pro", "GT5 Max"),
            new Series("G", "Lightweight commuting", "G5", "G5 Pro", "G5 Max"),
            new Series("S", "Suspension-focused", "S40", "S60", "S65", "S65C"),
            new Series("V", "City and budget", "V3 Pro", "V25", "V25 Pro", "V25i Pro", "V25i Pro II", "V40", "V40 Pro", "V40i", "V40i Pro",
                    "V40i Pro II", "V40N", "V45i", "V50", "V50i Pro", "V50i Pro II"),
            new Series("EasyRide", "Entry-level", "E20 (EasyRide 20)", "E25 Pro (EasyRide 25 Pro)"),
            new Series("K", "Kids", "K100", "K100 Pro", "K100 Max", "K100 Plus")));
    static final int MAX_TYPED = 40;
    private NaveeModels() {}

    static List<String> all() {
        List<String> out = new ArrayList<>();
        for (Series s : SERIES) out.addAll(s.models);
        return out;
    }

    /** Lower case without spaces, hyphens or brackets, so "ut5max", "UT5 MAX" and "ut-5 max" all match. */
    static String fold(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_()]+", "");
    }

    /** Models whose name, or whose series' name or description, contains the query; all models for an empty one. */
    static List<String> search(String query) {
        String q = fold(query);
        List<String> out = new ArrayList<>();
        for (Series s : SERIES) for (String m : s.models)
            if (q.isEmpty() || fold(m).contains(q) || fold(s.about).contains(q) || fold(s.name + "series").startsWith(q)) out.add(m);
        return out;
    }

    /** The listed model this text names exactly (ignoring case and spacing), or null. */
    static String exact(String text) {
        String q = fold(text);
        if (q.isEmpty()) return null;
        for (String m : all()) if (fold(m).equals(q) || fold(m.replaceAll("\\s*\\(.*\\)", "")).equals(q)) return m;
        return null;
    }

    static Series seriesOf(String model) {
        for (Series s : SERIES) if (s.models.contains(model)) return s;
        return null;
    }

    /** A write-in name, trimmed to printable text and a short length; "" when nothing usable is left. */
    static String typed(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_TYPED ? clean.substring(0, MAX_TYPED).trim() : clean;
    }

    /** What the owner said this scooter is: a listed model, a name they typed, or not sure. */
    static final class Choice {
        enum Source { LIST, TYPED, UNSURE }
        final Source source; final String name;
        private Choice(Source source, String name) { this.source = source; this.name = name; }
        static Choice listed(String model) { if (seriesOf(model) == null) throw new IllegalArgumentException("Not a listed model"); return new Choice(Source.LIST, model); }
        /** A typed name that matches a listed model counts as picking it. */
        static Choice typedName(String text) {
            String match = exact(text); if (match != null) return new Choice(Source.LIST, match);
            String clean = typed(text); return clean.isEmpty() ? unsure() : new Choice(Source.TYPED, clean);
        }
        static Choice unsure() { return new Choice(Source.UNSURE, ""); }
        boolean known() { return source != Source.UNSURE; }
    }
}
