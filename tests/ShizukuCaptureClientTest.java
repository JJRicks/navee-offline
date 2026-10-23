package com.jjricks.volt;

import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import rikka.shizuku.Shizuku;

/** Exercises the real Android coordinator against deterministic binding and preferences doubles. */
public final class ShizukuCaptureClientTest {
    private static int checks;
    private static final String RECOVERY_TOKEN = repeated('a'), OTHER_TOKEN = repeated('b');

    public static void main(String[] args) throws Exception {
        newAttemptTimeout();
        newAttemptCancel();
        failuresBeforeDispatch();
        recoveryBeforeDispatch();
        dispatchedCallsRemainRecoverable();
        cancellationWaitsForCleanup();
        matchingJournalOnly();
        staleBindingsAndTimeout();
        staleDeathAndResult();
        discardClearsStatus();
        System.out.println("ShizukuCaptureClientTest: " + checks + " checks passed");
    }

    private static void newAttemptTimeout() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        check(f.client.busy && f.client.pendingCleanup(), "new session reserves journal before binding");
        Handler.advanceBy(14999);
        check(f.client.busy && f.client.pendingCleanup(), "binding gets full timeout window");
        Handler.advanceBy(1);
        check(!f.client.busy && !f.client.pendingCleanup(), "timeout before dispatch drops fresh reservation");
        check(Shizuku.unbound.size() == 1, "timed out binding is released");
        f.client.start(false);
        check(f.client.busy && Shizuku.bindings.size() == 2, "timeout permits direct retry without false recovery");
    }

    private static void newAttemptCancel() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        ServiceConnection old = f.binding();
        f.client.cancel();
        check(!f.client.busy && !f.client.pendingCleanup(), "cancel before dispatch drops fresh reservation");
        Remote late = new Remote();
        old.onServiceConnected(null, late);
        check(late.calls == 0, "late connection after cancel never starts capture");
        Handler.advanceBy(15000);
        check(!f.client.busy && !f.client.pendingCleanup(), "cancelled timeout has no further effect");
    }

    private static void failuresBeforeDispatch() throws Exception {
        Fixture bind = new Fixture(false);
        Shizuku.failBind = true;
        bind.client.start(false);
        check(!bind.client.busy && !bind.client.pendingCleanup(), "bind exception removes unused reservation");

        Fixture death = new Fixture(false);
        death.client.start(false);
        Remote remote = new Remote(); remote.failLink = true;
        death.binding().onServiceConnected(null, remote);
        check(remote.calls == 0, "linkToDeath failed before any remote capture invocation");
        check(!death.client.busy && !death.client.pendingCleanup(), "pre-dispatch binder failure removes reservation");
    }

    private static void recoveryBeforeDispatch() throws Exception {
        for (String failure : new String[] { "timeout", "cancel", "bind", "link" }) {
            Fixture f = new Fixture(true);
            Shizuku.failBind = failure.equals("bind");
            f.client.start(true);
            if (failure.equals("timeout")) Handler.advanceBy(15000);
            else if (failure.equals("cancel")) f.client.cancel();
            else if (failure.equals("link")) {
                Remote remote = new Remote(); remote.failLink = true;
                f.binding().onServiceConnected(null, remote);
                check(remote.calls == 0, "recovery link failure stays before dispatch");
            }
            check(!f.client.busy, "failed recovery exits busy state: " + failure);
            f.assertJournal(RECOVERY_TOKEN, "  INFO  ", "preexisting recovery survives " + failure);
        }
    }

    private static void dispatchedCallsRemainRecoverable() throws Exception {
        for (boolean recovery : new boolean[] { false, true }) {
            Fixture f = new Fixture(recovery);
            f.client.start(recovery);
            String token = f.token();
            Remote remote = new Remote(); remote.failCall = true;
            f.binding().onServiceConnected(null, remote);
            check(remote.calls == 1, "remote invocation occurred before simulated transport error");
            check(!f.client.busy, "transport error releases busy state");
            f.assertJournal(token, recovery ? "  INFO  " : null, "possibly dispatched call stays recoverable");
            Handler.advanceBy(15000);
            f.assertJournal(token, recovery ? "  INFO  " : null, "old timeout cannot erase dispatched reservation");
        }

        Fixture f = new Fixture(false);
        f.client.start(false);
        Remote remote = new Remote(); f.binding().onServiceConnected(null, remote);
        check(remote.callback.prepareCleanup(remote.token, "DEBUG"), "remote journals original logging property");
        f.binding().onServiceDisconnected(null);
        f.assertJournal(remote.token, "DEBUG", "disconnect after dispatch preserves recovery record");
    }

    private static void cancellationWaitsForCleanup() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        Remote remote = new Remote(); f.binding().onServiceConnected(null, remote);
        check(remote.callback.prepareCleanup(remote.token, ""), "empty original property is journaled");
        f.client.cancel();
        check(remote.cancels == 1, "post-dispatch cancellation reaches remote helper");
        f.assertJournal(remote.token, "", "cancel does not pretend remote cleanup finished");
        remote.callback.completed(remote.token, OwnerCaptureEngine.CANCELLED, true, null, 0);
        Handler.drain();
        check(!f.client.busy && !f.client.pendingCleanup(), "confirmed clean completion clears journal");
        check(f.client.owner == null, "cancelled capture never exposes owner for import");
    }

    private static void discardClearsStatus() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        Remote remote = new Remote(); f.binding().onServiceConnected(null, remote);
        check(remote.callback.prepareCleanup(remote.token, ""), "capture journals before reading");
        remote.callback.completed(remote.token, OwnerCaptureEngine.TIMED_OUT, true, null, 0);
        Handler.drain();
        check(!f.client.message.isEmpty(), "timed out capture reports a status");
        f.client.discard();
        check(f.client.owner == null && f.client.message.isEmpty(), "discard leaves no stale status for the capture step");
    }

    private static void matchingJournalOnly() throws Exception {
        for (boolean cancel : new boolean[] { false, true }) {
            Fixture f = new Fixture(false);
            f.client.start(false);
            f.preferences.edit().putBoolean("pending", true).putString("token", OTHER_TOKEN)
                    .putString("original", "WARN").commit();
            if (cancel) f.client.cancel(); else Handler.advanceBy(15000);
            f.assertJournal(OTHER_TOKEN, "WARN", "pre-dispatch cleanup cannot erase another session's journal");
        }
    }

    private static void staleBindingsAndTimeout() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        ServiceConnection old = f.binding();
        Handler.advanceBy(1000);
        f.client.cancel();
        f.client.start(false);
        String current = f.token();
        Remote late = new Remote();
        old.onServiceConnected(null, late);
        old.onServiceDisconnected(null);
        check(late.calls == 0, "superseded binding callback cannot dispatch");
        check(f.client.busy, "old disconnection cannot stop new binding");
        f.assertJournal(current, null, "old binding callbacks preserve current journal");
        Handler.advanceBy(14000);
        check(f.client.busy, "old timeout cannot stop newer binding");
        f.assertJournal(current, null, "old timeout preserves current reservation");
        Handler.advanceBy(1000);
        check(!f.client.busy && !f.client.pendingCleanup(), "new binding still times out on its own deadline");
    }

    private static void staleDeathAndResult() throws Exception {
        Fixture f = new Fixture(false);
        f.client.start(false);
        Remote old = new Remote();
        f.binding().onServiceConnected(null, old);
        old.callback.completed(old.token, OwnerCaptureEngine.TIMED_OUT, true, null, 0);
        Handler.drain();
        f.client.start(false);
        String current = f.token();
        old.death.binderDied();
        old.callback.completed(old.token, OwnerCaptureEngine.CANCELLED, true, null, 0);
        Handler.drain();
        check(f.client.busy, "old binder death or completion cannot stop new session");
        f.assertJournal(current, null, "old binder death and completion preserve new journal");
    }

    private static final class Fixture {
        final Preferences preferences = new Preferences();
        final ShizukuCaptureClient client;
        Fixture(boolean recovery) throws Exception {
            Handler.reset(); Shizuku.reset();
            if (recovery) preferences.edit().putBoolean("pending", true).putString("token", RECOVERY_TOKEN)
                    .putString("original", "  INFO  ").commit();
            Constructor<ShizukuCaptureClient> constructor = ShizukuCaptureClient.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            client = constructor.newInstance(new Context() {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
            });
        }
        ServiceConnection binding() { return Shizuku.bindings.get(Shizuku.bindings.size() - 1); }
        String token() { return preferences.getString("token", null); }
        void assertJournal(String token, String original, String context) {
            check(client.pendingCleanup(), context + ": pending");
            equal(token, token(), context + ": token");
            equal(original, preferences.getString("original", null), context + ": original");
        }
    }

    private static final class Remote extends IOwnerCaptureService.Stub {
        boolean failLink, failCall;
        int calls, cancels;
        String token;
        IOwnerCaptureCallback callback;
        IBinder.DeathRecipient death;
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) throws Exception {
            if (failLink) throw new Exception("binder died before dispatch");
            death = recipient;
        }
        @Override public void start(String session, IOwnerCaptureCallback cb) throws Exception {
            calls++; token = session; callback = cb;
            if (failCall) throw new Exception("unknown result after binder dispatch");
        }
        @Override public void recover(String session, String original, IOwnerCaptureCallback cb) throws Exception {
            start(session, cb);
        }
        @Override public void cancel(String session) { cancels++; }
        @Override public void destroy() { }
    }

    private static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key); return value == null ? fallback : (Boolean) value;
        }
        public String getString(String key, String fallback) {
            Object value = values.get(key); return value == null ? fallback : (String) value;
        }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> writes = new HashMap<>();
                boolean clear;
                public Editor putBoolean(String key, boolean value) { writes.put(key, value); return this; }
                public Editor putString(String key, String value) { writes.put(key, value); return this; }
                public Editor remove(String key) { writes.put(key, null); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (clear) values.clear();
                    for (Map.Entry<String, Object> write : writes.entrySet()) {
                        if (write.getValue() == null) values.remove(write.getKey());
                        else values.put(write.getKey(), write.getValue());
                    }
                    return true;
                }
            };
        }
    }

    private static String repeated(char c) { char[] chars = new char[64]; java.util.Arrays.fill(chars, c); return new String(chars); }
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message);
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
