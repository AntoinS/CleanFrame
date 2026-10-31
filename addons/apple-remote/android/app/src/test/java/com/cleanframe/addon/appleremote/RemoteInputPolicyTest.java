package com.cleanframe.addon.appleremote;

import android.text.InputType;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteInputPolicyTest {
    private static final class Target implements RemoteInputPolicy.Target {
        String text = "Alt";
        int start = 3, end = 3, reads, writes, begins, ends, submitted;
        boolean failWrite, failRead, failBegin, failSubmit;
        @Override public RemoteInputPolicy.Field read() {
            reads++; if (failRead) throw new IllegalStateException("private text must not escape");
            return new RemoteInputPolicy.Field(text, start, end);
        }
        @Override public boolean begin() { begins++; return !failBegin; }
        @Override public boolean replace(int length, String value) {
            assertEquals(text.length(), length); writes++; text = value; start = end = value.length(); return !failWrite;
        }
        @Override public boolean select(int a, int b) { start = a; end = b; return true; }
        @Override public void end() { ends++; }
        @Override public boolean submit(int action) { submitted = action; return !failSubmit; }
    }
    private RemoteInputPolicy policy(Target target, int action) {
        RemoteInputPolicy policy = new RemoteInputPolicy(); policy.start(InputType.TYPE_CLASS_TEXT, action);
        policy.observe(target.read()); return policy;
    }
    private RemoteInputPolicy.Result edit(RemoteInputPolicy policy, RemoteInputPolicy.Snapshot ticket,
                                         String text, int start, int end, boolean submit, Target target) {
        return policy.apply(ticket.generation, ticket.revision, text, start, end, submit, target);
    }
    @Test public void freshFieldGenerationAndFinishEraseContent() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3);
        RemoteInputPolicy.Snapshot old = policy.snapshot();
        policy.observe(target.read()); assertSame(old, policy.snapshot());
        policy.start(InputType.TYPE_CLASS_TEXT, 3); policy.observe(target.read());
        assertNotEquals(old.generation, policy.snapshot().generation);
        assertEquals(RemoteInputPolicy.Result.STALE, edit(policy, old, "Neu", 3, 3, false, target));
        assertEquals(0, target.writes);
        policy.finish(); assertFalse(policy.snapshot().ready); assertEquals("", policy.snapshot().text);
        assertEquals("", policy.snapshot().generation);
    }
    @Test public void appChangesAndSelectionChangesRejectOldTicketBeforeAnyWrite() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3);
        RemoteInputPolicy.Snapshot ticket = policy.snapshot(); target.text = "App";
        assertEquals(RemoteInputPolicy.Result.STALE, edit(policy, ticket, "Neu", 3, 3, false, target));
        assertEquals(0, target.begins); assertEquals("App", policy.snapshot().text);
        ticket = policy.snapshot(); target.start = 0;
        assertEquals(RemoteInputPolicy.Result.STALE, edit(policy, ticket, "Neu", 3, 3, false, target));
        assertEquals(0, target.begins);
    }
    @Test public void unicodeReplacementDeletionSelectionAndSearchUseCurrentField() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3);
        assertEquals(RemoteInputPolicy.Result.APPLIED, edit(policy, policy.snapshot(), "Grüße 🎵", 6, 8, false, target));
        assertEquals("Grüße 🎵", target.text); assertEquals(6, target.start); assertEquals(8, target.end);
        assertEquals(RemoteInputPolicy.Result.APPLIED, edit(policy, policy.snapshot(), "Grüße ", 6, 6, false, target));
        assertEquals("Grüße ", target.text);
        assertEquals(RemoteInputPolicy.Result.APPLIED, edit(policy, policy.snapshot(), "Suche ä", 7, 7, true, target));
        assertEquals(3, target.submitted); assertEquals(target.begins, target.ends);
    }
    @Test public void unpairedSurrogatesAndSplitSelectionNeverReachEditor() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3);
        for (String invalid : new String[]{"\uD83C", "\uDFB5", "a\uD83Cb"}) {
            assertEquals(RemoteInputPolicy.Result.INVALID_TEXT, edit(policy, policy.snapshot(), invalid, 0, 0, false, target));
        }
        assertEquals(RemoteInputPolicy.Result.INVALID_TEXT, edit(policy, policy.snapshot(), "🎵", 1, 1, false, target));
        assertEquals(0, target.begins);
    }
    @Test public void unsupportedActionDoesNotPartiallyReplaceText() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 1);
        assertEquals(RemoteInputPolicy.Result.UNSUPPORTED_ACTION, edit(policy, policy.snapshot(), "Neu", 3, 3, true, target));
        assertEquals("Alt", target.text); assertEquals(0, target.writes);
    }
    @Test public void noOpConsumesRevisionAndReplayIsStale() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3);
        RemoteInputPolicy.Snapshot ticket = policy.snapshot();
        assertEquals(RemoteInputPolicy.Result.APPLIED, edit(policy, ticket, "Alt", 3, 3, false, target));
        assertTrue(policy.snapshot().revision > ticket.revision);
        assertEquals(RemoteInputPolicy.Result.STALE, edit(policy, ticket, "Neu", 3, 3, false, target));
        assertEquals(0, target.writes);
    }
    @Test public void failedPartialEditClearsSnapshotAndDoesNotSubmit() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3); target.failWrite = true;
        RemoteInputPolicy.Snapshot ticket = policy.snapshot();
        assertEquals(RemoteInputPolicy.Result.FAILED, edit(policy, ticket, "Neu", 3, 3, true, target));
        assertEquals(target.begins, target.ends); assertEquals(0, target.submitted);
        assertFalse(policy.snapshot().ready); assertEquals("", policy.snapshot().text);
        assertTrue(policy.snapshot().revision > ticket.revision);
    }
    @Test public void passwordsDoNotReadOrShareTheUnderlyingField() {
        int[] types = { InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD };
        for (int type : types) {
            Target target = new Target(); RemoteInputPolicy policy = new RemoteInputPolicy(); policy.start(type, 3);
            policy.observe(new RemoteInputPolicy.Field("secret", 6, 6));
            assertFalse(policy.snapshot().ready); assertEquals("", policy.snapshot().text);
            assertEquals("password", policy.snapshot().reason);
            assertEquals(RemoteInputPolicy.Result.UNAVAILABLE, edit(policy, policy.snapshot(), "x", 1, 1, false, target));
            assertEquals(0, target.reads); assertEquals(0, target.writes);
        }
    }
    @Test public void partialUnsupportedOversizedOrInvalidExtractedTextIsRejected() {
        assertNull(RemoteInputPolicy.extracted("tail", 50, -1, 4, 4));
        assertNull(RemoteInputPolicy.extracted("change", 0, 3, 0, 0));
        assertNull(RemoteInputPolicy.extracted(null, 0, -1, 0, 0));
        assertNull(RemoteInputPolicy.extracted("x".repeat(4097), 0, -1, 0, 0));
        assertNull(RemoteInputPolicy.extracted("🎵", 0, -1, 1, 1));
        assertNotNull(RemoteInputPolicy.extracted("x".repeat(4096), 0, -1, 4096, 4096));
        assertNotNull(RemoteInputPolicy.extracted("", 0, -1, 0, 0));
    }
    @Test public void lostConnectionClearsContentWithoutEchoingExceptionText() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3); target.failRead = true;
        assertEquals(RemoteInputPolicy.Result.UNAVAILABLE, edit(policy, policy.snapshot(), "Neu", 3, 3, false, target));
        assertEquals("connection_lost", policy.snapshot().reason); assertEquals("", policy.snapshot().text);
        assertEquals(0, target.begins);
    }
    @Test public void failedBeginDoesNotWriteAndFailedActionInvalidatesTicket() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3); target.failBegin = true;
        assertEquals(RemoteInputPolicy.Result.FAILED, edit(policy, policy.snapshot(), "Neu", 3, 3, false, target));
        assertEquals(0, target.writes); assertEquals(0, target.ends);
        target.failBegin = false; target.failSubmit = true; policy.observe(target.read());
        assertEquals(RemoteInputPolicy.Result.FAILED, edit(policy, policy.snapshot(), "Alt", 3, 3, true, target));
        assertFalse(policy.snapshot().ready); assertEquals("action_failed", policy.snapshot().reason);
    }
    @Test public void oversizedIncomingTextIsRejectedWithoutReadingEditor() {
        Target target = new Target(); RemoteInputPolicy policy = policy(target, 3); int reads = target.reads;
        assertEquals(RemoteInputPolicy.Result.INVALID_TEXT, edit(policy, policy.snapshot(), "x".repeat(4097), 0, 0, false, target));
        assertEquals(reads, target.reads); assertEquals(0, target.begins);
    }
}
