package com.cleanframe.addon.appleremote;

import android.text.InputType;
import java.util.Objects;
import java.util.UUID;

/** In-memory field tickets and edit policy. No Android runtime calls or text logging. */
public final class RemoteInputPolicy {
    public static final int MAX_TEXT = 4096; // UTF-16 units, matching Android selection offsets.
    public enum Result { APPLIED, STALE, UNAVAILABLE, INVALID_TEXT, UNSUPPORTED_ACTION, FAILED }

    public static final class Snapshot {
        public final String generation, text, reason;
        public final long revision;
        public final int selectionStart, selectionEnd, editorAction;
        public final boolean ready;
        private Snapshot(String generation, long revision, String text, int start, int end,
                         int action, boolean ready, String reason) {
            this.generation = generation; this.revision = revision; this.text = text;
            selectionStart = start; selectionEnd = end; editorAction = action;
            this.ready = ready; this.reason = reason;
        }
        public boolean supportsRemotePosition() {
            return selectionStart == text.length() && selectionEnd == text.length();
        }
    }
    /** A complete field, not a truncated surrounding-text window. */
    public static final class Field {
        public final String text;
        public final int start, end;
        public Field(String text, int start, int end) { this.text = text; this.start = start; this.end = end; }
    }
    interface Target {
        Field read();
        boolean begin();
        boolean replace(int oldLength, String text);
        boolean select(int start, int end);
        void end();
        boolean submit(int action);
    }

    private String generation = "";
    private long revision;
    private int action;
    private boolean sensitive;
    private Snapshot current = new Snapshot("", 0, "", 0, 0, 0, false, "no_field");
    public Snapshot snapshot() { return current; }

    static Field extracted(CharSequence text, int offset, int partialStart, int start, int end) {
        if (text == null || offset != 0 || partialStart != -1 || text.length() > MAX_TEXT) return null;
        String full = text.toString();
        return validText(full, start, end) ? new Field(full, start, end) : null;
    }

    public void start(int inputType, int editorAction) {
        generation = UUID.randomUUID().toString(); revision = 0; action = editorAction;
        sensitive = isPassword(inputType);
        unavailable(sensitive ? "password" : "waiting_for_field");
    }
    public void finish() {
        generation = ""; action = 0; sensitive = false;
        unavailable("no_field");
    }
    public void unavailable(String reason) {
        String safeReason = sensitive ? "password" : reason;
        if (!current.ready && current.reason.equals(safeReason) && current.generation.equals(generation)) return;
        current = new Snapshot(generation, ++revision, "", 0, 0, action, false, safeReason);
    }
    public void observe(Field field) {
        if (generation.isEmpty() || sensitive) { unavailable(sensitive ? "password" : "no_field"); return; }
        if (field == null) { unavailable("unsupported_field"); return; }
        if (!validText(field.text, field.start, field.end)) {
            unavailable(field.text != null && field.text.length() > MAX_TEXT ? "too_long" : "invalid_field"); return;
        }
        if (current.ready && current.text.equals(field.text) && current.selectionStart == field.start
                && current.selectionEnd == field.end) return;
        current = new Snapshot(generation, ++revision, field.text, field.start, field.end, action, true, "ready");
    }

    Result apply(String expectedGeneration, long expectedRevision, String text, int start, int end,
                 boolean submit, Target target) {
        if (!validText(text, start, end)) return Result.INVALID_TEXT;
        if (!current.ready || sensitive || target == null) return Result.UNAVAILABLE;
        // Refresh immediately before validating the ticket: app edits need not have
        // emitted a selection callback yet. Never write against a cached field alone.
        try { observe(target.read()); } catch (RuntimeException unavailable) { unavailable("connection_lost"); }
        if (!current.ready) return Result.UNAVAILABLE;
        if (!Objects.equals(generation, expectedGeneration) || current.revision != expectedRevision) return Result.STALE;
        if (submit && action <= 1) return Result.UNSUPPORTED_ACTION;
        int oldLength = current.text.length();
        boolean changed = !current.text.equals(text);
        boolean selectionChanged = current.selectionStart != start || current.selectionEnd != end;
        // Consume the ticket even on partial/failing edits. InputConnection is not
        // transactional; an app may accept text but reject selection or submission.
        current = new Snapshot(generation, ++revision, current.text, current.selectionStart,
                current.selectionEnd, action, true, "ready");
        try {
            if (changed || selectionChanged) {
                if (!target.begin()) { unavailable("edit_failed"); return Result.FAILED; }
                boolean applied;
                try { applied = (!changed || target.replace(oldLength, text)) && target.select(start, end); }
                finally { target.end(); }
                if (!applied) { unavailable("edit_failed"); return Result.FAILED; }
            }
            if (submit && !target.submit(action)) { unavailable("action_failed"); return Result.FAILED; }
            observe(target.read());
            return Result.APPLIED;
        } catch (RuntimeException unavailable) {
            unavailable("connection_lost"); return Result.FAILED;
        }
    }

    public static boolean validText(String text, int start, int end) {
        if (text == null || text.length() > MAX_TEXT || start < 0 || end < 0
                || start > text.length() || end > text.length()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return boundary(text, start) && boundary(text, end);
    }
    private static boolean boundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !(Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
    public static boolean isPassword(int inputType) {
        int type = inputType & InputType.TYPE_MASK_CLASS, variation = inputType & InputType.TYPE_MASK_VARIATION;
        return (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                || (type == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
    }
}
