package com.jjricks.volt;

/** The Report an issue draft: a prompt for the owner, then only versions, phone model and the scooter's model name. */
public final class IssueReportTest {
    private static int assertions;
    public static void main(String[] args) {
        check(IssueReport.subject("1.5.1").equals("Volt issue report · Volt 1.5.1"), "Subject names the Volt version");
        String ut5 = IssueReport.body("1.5.1", "16", 36, "Google Pixel 9 ", "UT5 MAX", false);
        check(ut5.equals("What happened, and what did you expect instead?\n\n\n\nVolt 1.5.1 · Android 16 (API 36) · Google Pixel 9\nScooter: UT5 MAX\n"), "Body: " + ut5);
        check(IssueReport.body("1.5.1", "16", 36, "Google Pixel 9", "", false).endsWith("· Google Pixel 9\n")
                && !IssueReport.body("1.5.1", "16", 36, "Google Pixel 9", null, false).contains("Scooter"), "No scooter line without a known model");
        check(IssueReport.body("1.5.1", "16", 36, "x", "Model 39991", true).endsWith("Scooter: Model 39991 (simulated)\n"), "A simulated scooter says so");
        System.out.println("IssueReport tests passed: " + assertions + " assertions");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
