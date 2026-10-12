package local.navee.offlinelock.tests;

import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Temporary transparent touch diagnostics. Install and restore on the main thread. */
final class WindowTouchTrace {
    private final Window window;
    private final Window.Callback original, proxy;
    private final List<String> events = new ArrayList<>();

    WindowTouchTrace(Window window) {
        this.window = window;
        original = window.getCallback();
        proxy = (Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class}, (object, method, arguments) -> {
                    if (method.getName().equals("dispatchTouchEvent") && arguments != null
                            && arguments.length == 1 && arguments[0] instanceof MotionEvent) {
                        MotionEvent event = (MotionEvent)arguments[0];
                        synchronized (events) {
                            if (events.size() < 12) {
                                View focus = window.getCurrentFocus();
                                events.add("action=" + event.getActionMasked() + ", raw=[" + event.getRawX()
                                        + "," + event.getRawY() + "], local=[" + event.getX() + "," + event.getY()
                                        + "], focus=" + (focus == null ? "null" : focus.getClass().getSimpleName()
                                        + ":" + focus.getContentDescription()));
                            }
                        }
                    }
                    try {
                        Object result = method.invoke(original, arguments);
                        if (method.getName().equals("dispatchTouchEvent") && arguments != null
                                && arguments.length == 1 && arguments[0] instanceof MotionEvent) {
                            synchronized (events) {
                                View focus = window.getCurrentFocus();
                                if (events.size() < 24) events.add("after action=" + ((MotionEvent)arguments[0]).getActionMasked()
                                        + ", focus=" + (focus == null ? "null" : focus.getClass().getSimpleName()
                                        + ":" + focus.getContentDescription()) + ", consumed=" + result);
                            }
                        }
                        return result;
                    }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        window.setCallback(proxy);
    }
    void restore() { if (window.getCallback() == proxy) window.setCallback(original); }
    String describe() {
        synchronized (events) { return "delegate=" + original.getClass().getName() + ", deliveredTouches=" + events; }
    }
}
