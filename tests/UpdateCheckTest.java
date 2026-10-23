package com.jjricks.volt;

/** Version comparison, the release reply Volt accepts, and the only pages it will open. Never touches the network. */
public final class UpdateCheckTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        UpdateCheck.fetcher = (url, agent) -> { throw new AssertionError("Tests must not fetch " + url); };
        versions(); replies(); pages();
        System.out.println("UpdateCheck tests passed: " + assertions + " assertions");
    }

    private static String reply(String tag, String url, String body, boolean draft, boolean prerelease) {
        return "{\"url\":\"https://api.github.com/repos/JJRicks/volt/releases/1\",\"html_url\":\"" + url + "\",\"id\":1,"
                + "\"author\":{\"login\":\"someone\",\"id\":2,\"site_admin\":false},\"tag_name\":\"" + tag + "\",\"name\":\"Volt\","
                + "\"draft\":" + draft + ",\"prerelease\":" + prerelease + ",\"created_at\":\"2026-10-22T00:00:00Z\","
                + "\"assets\":[{\"name\":\"volt.apk\",\"size\":1149203,\"browser_download_url\":\"https://github.com/JJRicks/volt/releases/download/x/volt.apk\"}],"
                + "\"body\":" + BackupJson.quote(body) + "}";
    }

    private static void versions() {
        check(java.util.Arrays.equals(UpdateCheck.version("v1.5.1"), new int[]{1, 5, 1}), "Tag with v");
        check(java.util.Arrays.equals(UpdateCheck.version("1.5.0-n65i-test"), new int[]{1, 5, 0}), "A test build counts as its base version");
        check(UpdateCheck.version("") == null && UpdateCheck.version("v") == null && UpdateCheck.version("beta") == null
                && UpdateCheck.version("1..5") == null && UpdateCheck.version(null) == null, "Unreadable versions");
        check(UpdateCheck.compare(UpdateCheck.version("1.5.1"), UpdateCheck.version("1.5.0")) > 0
                && UpdateCheck.compare(UpdateCheck.version("1.5"), UpdateCheck.version("1.5.0")) == 0
                && UpdateCheck.compare(UpdateCheck.version("1.10.0"), UpdateCheck.version("1.9.9")) > 0
                && UpdateCheck.compare(UpdateCheck.version("2.0"), UpdateCheck.version("1.99.99")) > 0, "Numeric comparison");
        check(UpdateCheck.userAgent("1.5.0").equals("Volt/1.5.0 (Android; https://github.com/JJRicks/volt)"), "Same User-Agent as map tiles");
    }

    private static void replies() throws Exception {
        String page = "https://github.com/JJRicks/volt/releases/tag/v1.5.1";
        UpdateCheck.Result newer = UpdateCheck.evaluate("1.5.0", reply("v1.5.1", page, "**Volt 1.5.1**\r\n- N65i support", false, false));
        check(newer.outcome == UpdateCheck.Outcome.AVAILABLE && newer.latest.equals("1.5.1") && newer.url.equals(page)
                && newer.notes.equals("Volt 1.5.1\n• N65i support"), "A newer release is offered with plain notes");
        check(UpdateCheck.plain("## What's new\n\n- **Back up** & restore\n  * nested `code`\n\n\n\nKnown __limits__").equals(
                "What's new\n\n• Back up & restore\n  • nested code\n\nKnown limits"), "Markdown becomes plain text");
        check(UpdateCheck.evaluate("1.5.0-n65i-test", reply("v1.5.1", page, "", false, false)).outcome == UpdateCheck.Outcome.AVAILABLE, "A test build sees the next release");
        check(UpdateCheck.evaluate("1.5.0-n65i-test", reply("v1.5.0", page, "", false, false)).outcome == UpdateCheck.Outcome.CURRENT, "A test build is current against its own base");
        check(UpdateCheck.evaluate("1.5.1", reply("v1.5.1", page, "", false, false)).outcome == UpdateCheck.Outcome.CURRENT
                && UpdateCheck.evaluate("1.6.0", reply("v1.5.1", page, "", false, false)).outcome == UpdateCheck.Outcome.CURRENT, "Same or newer installed: current");
        check(UpdateCheck.evaluate("1.5.0", reply("v1.5.1", page, "", true, false)).outcome == UpdateCheck.Outcome.UNREADABLE
                && UpdateCheck.evaluate("1.5.0", reply("v1.5.1", page, "", false, true)).outcome == UpdateCheck.Outcome.UNREADABLE, "Drafts and pre-releases are never offered");
        StringBuilder longNotes = new StringBuilder(); for (int i = 0; i < 400; i++) longNotes.append("0123456789");
        UpdateCheck.Result trimmed = UpdateCheck.evaluate("1.5.0", reply("v1.5.1", page, longNotes.toString(), false, false));
        check(trimmed.notes.length() <= UpdateCheck.MAX_NOTES + 1 && trimmed.notes.endsWith("…"), "Long notes are cut");
        for (String broken : new String[]{"", "[]", "{", "{\"tag_name\":1,\"html_url\":\"" + page + "\"}", "{\"html_url\":\"" + page + "\"}",
                reply("latest", page, "", false, false), reply("v1.5.1", "https://example.com/volt", "", false, false)})
            check(UpdateCheck.evaluate("1.5.0", broken).outcome == UpdateCheck.Outcome.UNREADABLE, "Unreadable reply: " + broken.length() + " chars");
        check(UpdateCheck.evaluate("garbage", reply("v1.5.1", page, "", false, false)).outcome == UpdateCheck.Outcome.UNREADABLE, "An unreadable installed version");
        BackupRegistry.Rule consent = BackupRegistry.rule("app_settings", UpdateCheck.CONSENT);
        check(consent != null && !consent.excluded(), "The consent backs up with app preferences");
    }

    private static void pages() {
        check(UpdateCheck.safePage("https://github.com/JJRicks/volt/releases/tag/v1.5.1") && UpdateCheck.safePage("https://github.com/JJRicks/volt/releases/latest"), "Release pages");
        for (String url : new String[]{"https://github.com/JJRicks/volt/releases", "http://github.com/JJRicks/volt/releases/tag/v1",
                "https://github.com/JJRicks/volt-fake/releases/tag/v1", "https://github.com/Other/volt/releases/tag/v1",
                "https://github.com/JJRicks/volt/releases/tag/v1?redirect=https://evil.example", "https://github.com/JJRicks/volt/releases/../../evil",
                "https://github.com/JJRicks/volt/releases/tag/v1#x", "https://github.com/JJRicks/volt/releases/tag/v 1", "https://github.com/JJRicks/volt/releases/@evil.example", null})
            check(!UpdateCheck.safePage(url), "Refused page: " + url);
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
