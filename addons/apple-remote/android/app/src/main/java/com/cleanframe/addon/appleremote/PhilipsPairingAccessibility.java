package com.cleanframe.addon.appleremote;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;

/** Reads only our short-lived pairing message in Philips' own window; no input injection. */
public final class PhilipsPairingAccessibility extends AccessibilityService {
    private static volatile boolean connected;
    private static final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    static long generation() { return generation.get(); }
    static boolean connected() { return connected; }
    @Override protected void onServiceConnected() {
        generation.incrementAndGet(); connected = true;
        RemoteService.pairingAccessReady();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !PhilipsPairCapture.PACKAGE.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                || !RemoteService.awaitingPhilipsCode()) return;
        // Reading the actual displayed node also establishes that the OEM Activity exists.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.add(root);
        int visited = 0;
        try {
            while (!nodes.isEmpty() && visited++ < 128) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    if (!PhilipsPairCapture.PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) continue;
                    if (node.isVisibleToUser() && RemoteService.receivePhilipsCode(node.getPackageName().toString(), node.getText())) break;
                    for (int i = 0; i < Math.min(node.getChildCount(), 32) && nodes.size() < 128; i++) {
                        AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!nodes.isEmpty()) nodes.removeFirst().recycle(); }
    }
    @Override public void onInterrupt() { }
    private void disconnected() { connected = false; generation.incrementAndGet(); RemoteService.pairingAccessLost(); }
    @Override public boolean onUnbind(android.content.Intent intent) { disconnected(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnected(); super.onDestroy(); }
}
