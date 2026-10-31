package com.cleanframe.philips;

import org.json.JSONArray;
import org.json.JSONObject;

/** Durable ordered targets. Only confirmed installs advance; failure/cancellation stops the batch. */
final class AddonUpdateBatch {
    private final JSONArray targets;
    private int completed;
    private String status;
    AddonUpdateBatch(JSONObject saved) {
        targets = saved == null || saved.optJSONArray("targets") == null ? new JSONArray() : saved.optJSONArray("targets");
        completed = saved == null ? 0 : Math.max(0, Math.min(targets.length(), saved.optInt("completed")));
        status = saved == null ? "idle" : saved.optString("status", "idle");
    }
    static AddonUpdateBatch start(JSONArray targets) throws Exception {
        return new AddonUpdateBatch(new JSONObject().put("targets", new JSONArray(targets.toString())).put("status", targets.length() == 0 ? "complete" : "running"));
    }
    boolean active() { return status.equals("running") && completed < targets.length(); }
    JSONObject current() { return active() ? targets.optJSONObject(completed) : null; }
    boolean awaiting(String id) { return active() && current() != null && current().optString("id").equals(id); }
    void confirm(String id) {
        if (!awaiting(id)) return;
        completed++;
        if (completed == targets.length()) status = "complete";
    }
    void stop(String reason) { if (active()) status = reason; }
    JSONObject state(boolean durable) throws Exception {
        JSONObject out = new JSONObject().put("status", status).put("completed", completed).put("total", targets.length());
        if (active()) out.put("id", current().optString("id")).put("name", current().optString("name"));
        if (durable) out.put("targets", targets);
        return out;
    }
}
