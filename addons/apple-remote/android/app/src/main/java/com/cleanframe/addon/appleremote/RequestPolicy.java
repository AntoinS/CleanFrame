package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import java.util.Iterator;
import java.util.Set;

final class RequestPolicy {
    static JSONObject parseRunningResponse(String raw) throws Exception {
        JSONObject response = parseResponse(raw);
        if (!response.optBoolean("running", false)) throw new IllegalStateException("Receiver is not running.");
        return response;
    }

    static JSONObject parseResponse(String raw) throws Exception {
        if (raw == null || raw.length() > 65536) throw new IllegalArgumentException("Invalid receiver response size.");
        JSONObject response = new JSONObject(raw);
        if (response.has("error")) throw new IllegalStateException("Receiver rejected the request.");
        return response;
    }

    static JSONObject parse(String raw) throws Exception {
        if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Invalid request size.");
        JSONObject request = new JSONObject(raw);
        Object api = request.opt("api");
        if (request.has("api") && (!(api instanceof Number) || ((Number) api).doubleValue() != 1)) throw new IllegalArgumentException("Unsupported add-on interface.");
        String op = request.getString("op");
        Set<String> keys;
        switch (op) {
            case "status": case "setupStatus": case "enable": case "disable": case "philipsPairBegin": case "applePairBegin":
                keys = Set.of("api", "op"); break;
            case "philipsPairFinish":
                keys = Set.of("api", "op", "code");
                if (!request.getString("code").matches("[0-9]{4}")) throw new IllegalArgumentException("Enter the four-digit Philips code.");
                break;
            case "unpair":
                keys = Set.of("api", "op", "id");
                String id = request.getString("id");
                if (id.isEmpty() || id.length() > 256 || id.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid paired device.");
                break;
            default: throw new IllegalArgumentException("Unsupported add-on command.");
        }
        Iterator<String> names = request.keys();
        while (names.hasNext()) if (!keys.contains(names.next())) throw new IllegalArgumentException("Unexpected request field.");
        return request;
    }
}
