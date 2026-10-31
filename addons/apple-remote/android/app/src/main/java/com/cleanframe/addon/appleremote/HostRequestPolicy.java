package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import java.util.Iterator;
import java.util.Set;

/** JNI inputs can originate in a paired network client. No URLs, shells or generic methods. */
final class HostRequestPolicy {
    static JSONObject parse(String raw) throws Exception {
        if (raw == null || raw.length() > 32768) throw new IllegalArgumentException("invalid_request");
        JSONObject value = new JSONObject(raw);
        String op = value.getString("op");
        Set<String> fields;
        switch (op) {
            case "state": fields = Set.of("op"); break;
            case "key": fields = Set.of("op", "action"); key(value.getString("action")); break;
            case "edit":
                fields = Set.of("op", "generation", "revision", "text", "selectionStart", "selectionEnd", "submit");
                if (!(value.opt("generation") instanceof String)
                    || !value.getString("generation").matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || !(value.opt("text") instanceof String) || value.getString("text").length() > 4096
                    || !(value.opt("submit") instanceof Boolean)) throw new IllegalArgumentException("invalid_request");
                integral(value, "revision", 0, 9007199254740991L);
                integral(value, "selectionStart", 0, value.getString("text").length());
                integral(value, "selectionEnd", 0, value.getString("text").length());
                break;
            default: throw new IllegalArgumentException("invalid_request");
        }
        Iterator<String> names = value.keys();
        while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException("invalid_request");
        return value;
    }
    private static void integral(JSONObject value, String name, long minimum, long maximum) throws Exception {
        Object number = value.opt(name);
        if (!(number instanceof Number)) throw new IllegalArgumentException("invalid_request");
        double d = ((Number) number).doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < minimum || d > maximum) throw new IllegalArgumentException("invalid_request");
    }
    static PhilipsRemote.Key key(String action) {
        switch (action) {
            case "up": return PhilipsRemote.Key.UP;
            case "down": return PhilipsRemote.Key.DOWN;
            case "left": return PhilipsRemote.Key.LEFT;
            case "right": return PhilipsRemote.Key.RIGHT;
            case "select": return PhilipsRemote.Key.OK;
            case "menu": return PhilipsRemote.Key.BACK;
            case "home": return PhilipsRemote.Key.HOME;
            case "play_pause": return PhilipsRemote.Key.PLAY_PAUSE;
            case "volume_up": return PhilipsRemote.Key.VOLUME_UP;
            case "volume_down": return PhilipsRemote.Key.VOLUME_DOWN;
            case "mute": return PhilipsRemote.Key.MUTE;
            default: throw new IllegalArgumentException("unsupported_action");
        }
    }
}
