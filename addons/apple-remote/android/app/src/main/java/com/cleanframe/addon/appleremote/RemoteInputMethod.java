package com.cleanframe.addon.appleremote;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** User-selected Android IME. Never enables/selects itself or exports an edit Binder. */
public final class RemoteInputMethod extends InputMethodService {
    public interface Observer { void onFieldChanged(RemoteInputPolicy.Snapshot snapshot); }
    public interface Callback { void onComplete(RemoteInputPolicy.Result result, RemoteInputPolicy.Snapshot snapshot); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static RemoteInputMethod active;
    private static Observer observer;
    private static volatile RemoteInputPolicy.Snapshot latest = new RemoteInputPolicy().snapshot();
    private final RemoteInputPolicy policy = new RemoteInputPolicy();
    private boolean inputViewActive, fieldAllowed;
    private TextView notice;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (active != RemoteInputMethod.this || !inputViewActive || observer == null) return;
            refresh(); MAIN.postDelayed(this, 250);
        }
    };

    /** Callbacks run on main thread. The owner must detach when its service stops. */
    public static void attachObserver(Observer value) {
        MAIN.post(() -> { observer = value; RemoteInputPolicy.Snapshot before = latest;
            if (active != null) { active.refresh(); active.schedule(); }
            if (observer != null && before == latest) observer.onFieldChanged(latest); });
    }
    public static void detachObserver(Observer value) {
        MAIN.post(() -> { if (observer == value) observer = null; if (active != null) active.schedule(); });
    }
    public static RemoteInputPolicy.Snapshot getSnapshot() { return latest; }
    public static void applyEdit(String generation, long revision, String text, int start, int end,
                                 boolean submit, Callback callback) {
        applyEdit(generation, revision, text, start, end, submit, () -> true, callback);
    }
    /** allowed must be a fast, thread-safe receiver-enabled/cancellation check.
     * Queued work expires after two seconds; each InputConnection operation rechecks.
     */
    public static void applyEdit(String generation, long revision, String text, int start, int end,
                                 boolean submit, BooleanSupplier allowed, Callback callback) {
        long deadline = SystemClock.uptimeMillis() + 2000;
        BooleanSupplier live = () -> SystemClock.uptimeMillis() < deadline && allowed != null && allowed.getAsBoolean();
        MAIN.post(() -> {
            RemoteInputMethod ime = active;
            RemoteInputPolicy.Result result = RemoteInputPolicy.Result.UNAVAILABLE;
            if (live.getAsBoolean() && ime != null && ime.inputViewActive && ime.fieldAllowed) {
                InputConnection connection = ime.getCurrentInputConnection();
                if (connection != null) result = ime.policy.apply(generation, revision, text, start, end, submit, ime.target(connection, live));
                else ime.policy.unavailable("connection_lost");
                ime.publish();
            }
            if (callback != null) callback.onComplete(result, latest);
        });
    }

    @Override public void onCreate() { super.onCreate(); active = this; publish(); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        active = this; inputViewActive = false;
        fieldAllowed = info != null && (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL
                && !RemoteInputPolicy.isPassword(info.inputType);
        int action = info == null ? 0 : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (info != null && info.actionLabel != null && info.actionId > 1) action = info.actionId;
        else if (info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) action = 0;
        policy.start(info == null ? InputType.TYPE_NULL : info.inputType, action);
        if (!fieldAllowed && info != null && !RemoteInputPolicy.isPassword(info.inputType)) policy.unavailable("unsupported_field");
        publish(); schedule();
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting); inputViewActive = true; refresh(); schedule();
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        inputViewActive = false; policy.unavailable("hidden"); publish(); schedule();
        super.onFinishInputView(finishingInput);
    }
    @Override public void onFinishInput() {
        inputViewActive = false; fieldAllowed = false; policy.finish(); publish(); schedule(); super.onFinishInput();
    }
    @Override public void onUnbindInput() {
        inputViewActive = false; fieldAllowed = false; policy.finish(); publish(); schedule(); super.onUnbindInput();
    }
    @Override public void onWindowHidden() {
        inputViewActive = false; policy.unavailable("hidden"); publish(); schedule(); super.onWindowHidden();
    }
    @Override public void onDestroy() {
        MAIN.removeCallbacks(poll); policy.finish();
        if (active == this) { publish(); active = null; }
        super.onDestroy();
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { super.onEvaluateInputViewShown(); return true; }
    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd); refresh();
    }
    private void schedule() {
        MAIN.removeCallbacks(poll);
        if (inputViewActive && observer != null) MAIN.postDelayed(poll, 250);
    }
    private void refresh() {
        if (inputViewActive && fieldAllowed) {
            try { policy.observe(read(getCurrentInputConnection())); }
            catch (RuntimeException unavailable) { policy.unavailable("connection_lost"); }
        }
        publish();
    }
    private RemoteInputPolicy.Field read(InputConnection connection) {
        if (connection == null || !fieldAllowed || !inputViewActive) return null;
        // The public extracted-text contract marks full text with partialStart=-1.
        // A surrounding-text window can be shorter than requested, so never treat
        // it as the complete document for a destructive replacement.
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = RemoteInputPolicy.MAX_TEXT + 1;
        request.hintMaxLines = RemoteInputPolicy.MAX_TEXT + 1;
        ExtractedText extracted = connection.getExtractedText(request, 0);
        return extracted == null ? null : RemoteInputPolicy.extracted(extracted.text, extracted.startOffset,
                extracted.partialStartOffset, extracted.selectionStart, extracted.selectionEnd);
    }
    private RemoteInputPolicy.Target target(InputConnection connection, BooleanSupplier live) {
        return new RemoteInputPolicy.Target() {
            @Override public RemoteInputPolicy.Field read() {
                if (!live.getAsBoolean() || connection != getCurrentInputConnection()) return null;
                RemoteInputPolicy.Field field = RemoteInputMethod.this.read(connection);
                return live.getAsBoolean() ? field : null;
            }
            @Override public boolean begin() { return live.getAsBoolean() && connection.beginBatchEdit(); }
            @Override public boolean replace(int oldLength, String text) {
                if (!live.getAsBoolean()) return false;
                connection.finishComposingText();
                return live.getAsBoolean() && connection.setSelection(0, oldLength)
                        && live.getAsBoolean() && connection.commitText(text, 1);
            }
            @Override public boolean select(int start, int end) { return live.getAsBoolean() && connection.setSelection(start, end); }
            @Override public void end() { connection.endBatchEdit(); }
            @Override public boolean submit(int action) { return live.getAsBoolean() && connection.performEditorAction(action); }
        };
    }

    private void publish() {
        RemoteInputPolicy.Snapshot next = policy.snapshot();
        boolean changed = latest != next; latest = next;
        if (notice != null) notice.setText(next.ready && !next.supportsRemotePosition()
                ? tr("Zum Schreiben den Cursor ans Textende setzen", "Move the cursor to the end to type")
                : next.ready ? tr("Mit der iPhone Tastatur schreiben", "Use iPhone keyboard")
                : "password".equals(next.reason) ? tr("Passwörter bitte mit einer anderen Tastatur eingeben", "Use another keyboard for passwords")
                : tr("Dieses Feld unterstützt die iPhone Tastatur derzeit nicht", "This field is not available to the iPhone keyboard"));
        if (changed && observer != null) observer.onFieldChanged(next);
    }
    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(32), dp(16), dp(32), dp(16)); root.setBackgroundColor(Color.rgb(24, 26, 29));
        notice = new TextView(this); notice.setTextColor(Color.rgb(244, 244, 241)); notice.setTextSize(16);
        root.addView(notice, new LinearLayout.LayoutParams(0, -2, 1));
        Button change = new Button(this); change.setText(tr("Andere Tastatur", "Switch keyboard")); change.setAllCaps(false);
        change.setOnClickListener(v -> {
            try { if (switchToPreviousInputMethod()) return; } catch (RuntimeException unavailable) { /* Offer the system picker. */ }
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) manager.showInputMethodPicker();
        });
        root.addView(change, new LinearLayout.LayoutParams(-2, dp(48))); publish(); return root;
    }
    private String tr(String de, String en) { return Locale.getDefault().getLanguage().equals("de") ? de : en; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
