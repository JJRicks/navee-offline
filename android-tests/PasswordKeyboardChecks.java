package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real keyboard checks shared by the guarded, disposable-emulator backup journeys. */
final class PasswordKeyboardChecks {
    private PasswordKeyboardChecks() {}

    static void verify(Instrumentation runner, Activity activity, EditText input) {
        verify(runner,activity,input,false);
    }
    static void verify(Instrumentation runner, Activity activity, EditText input, boolean keepKeyboard) {
        require(Build.VERSION.SDK_INT >= 30, "Password keyboard validation needs Android 11+");
        require(input != null, "Missing password editor");
        View decor = activity.getWindow().getDecorView();
        await(runner, () -> decor.hasWindowFocus() && input.isAttachedToWindow(), "Password window did not gain focus");
        onMain(runner,() -> {
            input.setText("");
            input.requestRectangleOnScreen(new Rect(0, 0, input.getWidth(), input.getHeight()), true);
        });
        runner.waitForIdleSync();
        awaitStableBounds(runner,input,decor);
        AtomicReference<Rect> bounds = new AtomicReference<>();
        onMain(runner,() -> {
            Rect rect = new Rect();
            require(input.getLocalVisibleRect(rect) && !rect.isEmpty(), "Password is not reachable before tapping: " + input.getHint());
            int[] position = new int[2]; input.getLocationOnScreen(position);
            rect.offset(position[0],position[1]);
            if(imeVisible(decor)){
                int[] decorPosition=new int[2];decor.getLocationOnScreen(decorPosition);
                rect.bottom=Math.min(rect.bottom,decorPosition[1]+decor.getHeight()-decor.getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom);
            }
            require(!rect.isEmpty(),"Password cannot be tapped above the keyboard: "+input.getHint());bounds.set(rect);
        });
        Rect target = bounds.get(); long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, target.centerX(), target.centerY(), 0);
        try { runner.sendPointerSync(down); runner.sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
        await(runner, input::hasFocus, "Tapping the password did not focus it");
        onMain(runner,() -> activity.getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
        await(runner, () -> imeVisible(decor), "Enable the emulator software keyboard for password validation");
        awaitStableBounds(runner,input,decor);
        await(runner, () -> visibleAboveKeyboard(input, decor), "Focused password is obscured by the keyboard");
        String sample = "Synthetic123";
        for (char character : sample.toCharArray()) {
            runner.sendStringSync(String.valueOf(character));
            try { await(runner, () -> input.hasFocus() && visibleAboveKeyboard(input, decor), "Password moved behind the keyboard while typing"); }
            catch(AssertionError failure){
                AtomicReference<String> details=new AtomicReference<>();
                onMain(runner,()->{
                    int[] position=new int[2];input.getLocationOnScreen(position);Rect visible=new Rect();input.getGlobalVisibleRect(visible);
                    WindowInsets insets=decor.getRootWindowInsets();
                    details.set("field="+input.getHint()+", focus="+input.hasFocus()+", y="+position[1]+", height="+input.getHeight()+", visible="+visible+", decorHeight="+decor.getHeight()+", ime="+(insets==null?"none":insets.getInsets(WindowInsets.Type.ime())));
                });
                throw new AssertionError(failure.getMessage()+": "+details.get());
            }
        }
        onMain(runner,() -> {
            require(sample.contentEquals(input.getText()), "Typing lost or changed the synthetic password");
            require(!input.isSaveEnabled(), "Password is allowed in saved Activity state");
            input.setText("");
            if(!keepKeyboard){input.clearFocus();activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);}
        });
        if(!keepKeyboard)await(runner, () -> !imeVisible(decor), "Password keyboard did not dismiss");
    }

    private static boolean imeVisible(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }
    /** Input injection must wait for the IME/window animation, including a picker return. */
    private static void awaitStableBounds(Instrumentation runner,EditText input,View decor){
        final String[] previous={null};final long[] stableSince={SystemClock.elapsedRealtime()};
        await(runner,()->{
            int[] position=new int[2];input.getLocationOnScreen(position);WindowInsets insets=decor.getRootWindowInsets();
            String current=position[0]+":"+position[1]+":"+input.getWidth()+":"+input.getHeight()+":"+decor.getHeight()+":"+(insets==null?0:insets.getInsets(WindowInsets.Type.ime()).bottom);
            boolean stable=current.equals(previous[0])&&decor.hasWindowFocus()&&!decor.isLayoutRequested()&&input.isLaidOut();
            if(!stable)stableSince[0]=SystemClock.elapsedRealtime();previous[0]=current;
            return stable&&SystemClock.elapsedRealtime()-stableSince[0]>=300;
        },"Password bounds did not settle after the keyboard/window transition");
    }
    private static boolean visibleAboveKeyboard(EditText input, View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) return false;
        int[] inputPosition = new int[2], decorPosition = new int[2];
        input.getLocationOnScreen(inputPosition); decor.getLocationOnScreen(decorPosition);
        int keyboardTop = decorPosition[1] + decor.getHeight() - insets.getInsets(WindowInsets.Type.ime()).bottom;
        Rect visible = new Rect();
        return input.getGlobalVisibleRect(visible) && visible.height() == input.getHeight()
                && inputPosition[1] >= decorPosition[1] + insets.getInsets(WindowInsets.Type.systemBars()).top
                && inputPosition[1] + input.getHeight() <= keyboardTop;
    }
    private static void await(Instrumentation runner, BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            onMain(runner,() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) { runner.waitForIdleSync(); return; }
            SystemClock.sleep(40);
        }
        throw new AssertionError(message);
    }
    private static void onMain(Instrumentation runner,Runnable action){
        AtomicReference<Throwable> error=new AtomicReference<>();
        runner.runOnMainSync(()->{try{action.run();}catch(Throwable failure){error.set(failure);}});
        if(error.get() instanceof AssertionError)throw (AssertionError)error.get();
        if(error.get()!=null)throw new RuntimeException(error.get());
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
