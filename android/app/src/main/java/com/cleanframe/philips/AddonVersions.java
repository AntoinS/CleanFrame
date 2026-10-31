package com.cleanframe.philips;

import java.math.BigInteger;
import org.json.JSONObject;

/** Catalog versions are release triples; native updates use Android's monotonic version code. */
final class AddonVersions {
    static int compare(String left, String right) {
        if (!left.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !right.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Invalid release version.");
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }
    static boolean newer(JSONObject candidate, JSONObject current) {
        if (candidate == null || current == null || !candidate.optString("id").equals(current.optString("id")) || !candidate.optString("kind").equals(current.optString("kind"))) return false;
        if (candidate.optString("kind").equals("native")) return candidate.optLong("versionCode") > current.optLong("versionCode");
        try { return compare(candidate.optString("version"), current.optString("version")) > 0; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
