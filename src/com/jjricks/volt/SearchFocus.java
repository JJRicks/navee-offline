package com.jjricks.volt;

import android.graphics.Rect;
import android.os.Build;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Keyboard dismissal for opted-in search fields, without changing ordinary form editors. */
public final class SearchFocus {
    private SearchFocus() {}

    /** Works for an Activity or Dialog window; binding does not request focus or show the IME. */
    public static void bind(EditText search, Window window) {
        if (search == null || window == null || window.getCallback() == null) return;
        Window.Callback callback = window.getCallback();
        TouchObserver observer;
        if (callback instanceof TouchObserver) observer = (TouchObserver) callback;
        else {
            observer = new TouchObserver(window, callback);
            window.setCallback(observer);
        }
        if (observer.searches.put(search, Boolean.TRUE) != null) return;
        Binding binding = new Binding(search, observer);
        observer.bindings.add(new WeakReference<>(binding));
        search.addOnAttachStateChangeListener(binding);
        if (search.isAttachedToWindow()) binding.onViewAttachedToWindow(search);
    }

    private static final class Binding
            implements View.OnAttachStateChangeListener,
                    ViewTreeObserver.OnGlobalFocusChangeListener,
                    ViewTreeObserver.OnGlobalLayoutListener,
                    ViewTreeObserver.OnPreDrawListener {
        private final EditText search;
        private final TouchObserver observer;
        private ViewTreeObserver tree;
        private boolean keyboardWasVisible;
        private long focusGeneration;
        private int largestHeight, lastWidth;
        private final Rect visibleFrame = new Rect();

        Binding(EditText search, TouchObserver observer) {
            this.search = search;
            this.observer = observer;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            removeObserver();
            keyboardWasVisible = false;
            focusGeneration++;
            largestHeight = lastWidth = 0;
            tree = search.getRootView().getViewTreeObserver();
            tree.addOnGlobalFocusChangeListener(this);
            tree.addOnGlobalLayoutListener(this);
            tree.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            removeObserver();
            keyboardWasVisible = false;
            focusGeneration++;
        }

        private void removeObserver() {
            if (tree != null && tree.isAlive()) {
                tree.removeOnGlobalFocusChangeListener(this);
                tree.removeOnGlobalLayoutListener(this);
                tree.removeOnPreDrawListener(this);
            }
            tree = null;
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            if (oldFocus == search || newFocus == search) {
                // A hide animation may finish after this editor loses focus and is
                // tapped again. Its previous IME visibility belongs to that older
                // focus session, not to the new tap's request to open the keyboard.
                keyboardWasVisible = false;
                focusGeneration++;
            }
        }

        @Override
        public void onGlobalLayout() {
            observeKeyboard();
        }

        @Override
        public boolean onPreDraw() {
            // A floating dialog can receive an IME visibility update without changing its
            // layout. Observe the frames Android already draws; do not schedule more frames.
            observeKeyboard();
            if (observer.imeAnimations != null) observer.imeAnimations.afterPreDraw();
            return true;
        }

        private void observeKeyboard() {
            if (!search.isAttachedToWindow() || !search.hasFocus() || !search.hasWindowFocus()) {
                keyboardWasVisible = false;
                return;
            }
            // Insets can change to an animation's end state before its first frame,
            // or briefly describe a replaced hide/show request. Only settled states
            // may arm or dismiss this focus session. Explicit Back/taps stay immediate.
            if (observer.imeAnimations != null && observer.imeAnimations.running()) return;
            View root = search.getRootView();
            WindowInsets insets = root.getRootWindowInsets();
            boolean visible;
            if (Build.VERSION.SDK_INT >= 30 && insets != null) {
                visible = insets.isVisible(WindowInsets.Type.ime());
            } else {
                // Older Android has no explicit IME visibility API. Prefer its resize inset;
                // the visible-frame fallback also catches keyboards which pan the window.
                int threshold = Math.round(search.getResources().getDisplayMetrics().density * 100);
                visible =
                        insets != null
                                && insets.getSystemWindowInsetBottom()
                                                - insets.getStableInsetBottom()
                                        > threshold;
                if (lastWidth != root.getWidth()) {
                    lastWidth = root.getWidth();
                    largestHeight = root.getHeight();
                }
                largestHeight = Math.max(largestHeight, root.getHeight());
                root.getWindowVisibleDisplayFrame(visibleFrame);
                int[] origin = new int[2];
                root.getLocationOnScreen(origin);
                int obscured = origin[1] + largestHeight - visibleFrame.bottom;
                visible |= obscured > Math.max(threshold, largestHeight * .18f);
            }
            boolean dismissed = keyboardWasVisible && !visible;
            keyboardWasVisible = visible;
            if (dismissed && search.hasFocus()) observer.dismiss(search);
        }
    }

    /** API 30+ window-level tracking; never consumes or modifies an inset. */
    private static final class ImeAnimations extends WindowInsetsAnimation.Callback {
        private final TouchObserver observer;
        private final Set<WindowInsetsAnimation> started =
                Collections.newSetFromMap(new IdentityHashMap<WindowInsetsAnimation, Boolean>());
        private WindowInsetsAnimation prepared, pendingFrameCheck;

        ImeAnimations(TouchObserver observer) {
            super(DISPATCH_MODE_CONTINUE_ON_SUBTREE);
            this.observer = observer;
        }

        boolean running() { return prepared != null || !started.isEmpty(); }

        void reset() {
            prepared = pendingFrameCheck = null;
            started.clear();
        }

        @Override public void onPrepare(WindowInsetsAnimation animation) {
            if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) prepared = animation;
        }

        @Override public WindowInsetsAnimation.Bounds onStart(
                WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) {
            if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                if (prepared == animation) prepared = null;
                started.add(animation);
            }
            return bounds;
        }

        @Override public WindowInsets onProgress(
                WindowInsets insets, List<WindowInsetsAnimation> runningAnimations) {
            return insets;
        }

        @Override public void onEnd(WindowInsetsAnimation animation) {
            if (prepared == animation) prepared = null;
            started.remove(animation);
            if (!running()) observer.observeSettledKeyboard();
        }

        void afterPreDraw() {
            // Android 11 omits onEnd if an animation is canceled before onStart.
            // Retire that preparation only after the traversal has given every
            // pre-draw listener (including Android's onStart dispatch) its turn.
            // This uses a frame boundary, never a guessed keyboard-duration delay.
            if (Build.VERSION.SDK_INT != 30 || prepared == null || pendingFrameCheck == prepared) return;
            Window target = observer.window.get();
            if (target == null) return;
            final WindowInsetsAnimation candidate = prepared;
            pendingFrameCheck = candidate;
            target.getDecorView().post(() -> {
                if (pendingFrameCheck == candidate) pendingFrameCheck = null;
                if (prepared == candidate && !started.contains(candidate)) {
                    prepared = null;
                    if (!running()) observer.observeSettledKeyboard();
                }
            });
        }
    }

    /** Passes all touches through; the first Back only blurs a focused, registered search. */
    private static final class TouchObserver implements Window.Callback {
        private final WeakReference<Window> window;
        private final Window.Callback delegate;
        private final WeakHashMap<EditText, Boolean> searches = new WeakHashMap<>();
        private final List<WeakReference<Binding>> bindings = new ArrayList<>();
        private final ImeAnimations imeAnimations;
        private boolean dismissing;
        private WeakReference<EditText> backSearch;
        private long backDownTime;
        private int backDeviceId;

        TouchObserver(Window window, Window.Callback delegate) {
            this.window = new WeakReference<>(window);
            this.delegate = delegate;
            // Decor receives the whole window's animations even when a dialog's
            // fitting content container stops dispatch to individual editors.
            imeAnimations = Build.VERSION.SDK_INT >= 30 ? new ImeAnimations(this) : null;
            if (imeAnimations != null) window.getDecorView().setWindowInsetsAnimationCallback(imeAnimations);
        }

        void observeSettledKeyboard() {
            for (int i = bindings.size() - 1; i >= 0; i--) {
                Binding binding = bindings.get(i).get();
                if (binding == null) { bindings.remove(i); continue; }
                final long generation = binding.focusGeneration;
                binding.search.post(() -> {
                    if (generation == binding.focusGeneration) binding.observeKeyboard();
                });
            }
        }

        void dismiss(EditText search) {
            if (dismissing || !search.hasFocus()) return;
            Window target = window.get();
            if (target == null) return;
            dismissing = true;
            try {
                InputMethodManager keyboard =
                        search.getContext().getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
                View root = target.getDecorView();
                // A neutral focus target prevents Android from immediately assigning focus
                // back to the first EditText. Restore descendant navigation before dispatch.
                root.setFocusableInTouchMode(true);
                if (root instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) root;
                    int previous = group.getDescendantFocusability();
                    group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    try {
                        search.clearFocus();
                        root.requestFocus();
                    } finally {
                        group.setDescendantFocusability(previous);
                    }
                } else {
                    search.clearFocus();
                    root.requestFocus();
                }
            } finally {
                dismissing = false;
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            Window target = window.get();
            View focus = target == null ? null : target.getCurrentFocus();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && focus instanceof EditText
                    && searches.containsKey(focus)) {
                EditText search = (EditText) focus;
                int[] location = new int[2];
                // IME pan moves a floating window's surface without updating its
                // reported screen origin. Compare both objects in window coordinates.
                search.getLocationInWindow(location);
                float x = event.getX(), y = event.getY();
                if (!search.isShown()
                        || x < location[0]
                        || y < location[1]
                        || x >= location[0] + search.getWidth()
                        || y >= location[1] + search.getHeight()) dismiss(search);
            }
            return delegate.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    backSearch = null;
                    Window target = window.get();
                    View focus = target == null ? null : target.getCurrentFocus();
                    if (!event.isCanceled()
                            && focus instanceof EditText
                            && searches.containsKey(focus)) {
                        backSearch = new WeakReference<>((EditText) focus);
                        backDownTime = event.getDownTime();
                        backDeviceId = event.getDeviceId();
                        return true;
                    }
                } else if (backSearch != null
                        && event.getDownTime() == backDownTime
                        && event.getDeviceId() == backDeviceId) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) return true;
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        EditText search = backSearch.get();
                        backSearch = null;
                        // A canceled press must not perform an action. Consume the paired
                        // release even if the IME already blurred the search, so a Dialog
                        // cannot also dismiss itself from the same Back gesture.
                        if (!event.isCanceled() && search != null && search.isAttachedToWindow()) {
                            dismiss(search);
                        }
                        return true;
                    }
                }
            }
            return delegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return delegate.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attributes) {
            delegate.onWindowAttributesChanged(attributes);
        }

        @Override
        public void onContentChanged() {
            delegate.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean focused) {
            delegate.onWindowFocusChanged(focused);
        }

        @Override
        public void onAttachedToWindow() {
            delegate.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            backSearch = null;
            // A removed window may not receive the old animation's onEnd. Reusing
            // that window must not inherit a permanently active transition.
            if (imeAnimations != null) imeAnimations.reset();
            delegate.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            delegate.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return delegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent event) {
            return delegate.onSearchRequested(event);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            delegate.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            delegate.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(
                List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            delegate.onPointerCaptureChanged(hasCapture);
        }
    }
}
