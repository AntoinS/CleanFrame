package com.cleanframe.philips;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DesignOverridesTest {
 @Test public void nativeQrUsesTemplateThenElementWithoutOtherViews() throws Exception {
  JSONArray rules=new JSONArray("[{\"view\":\"qr\",\"layout\":\"*\",\"state\":\"*\",\"target\":\"type:p\",\"style\":{\"fontSize\":32,\"color\":\"#ffffff\"}},{\"view\":\"qr\",\"layout\":\"*\",\"state\":\"compact\",\"target\":\"qrDetail\",\"style\":{\"fontSize\":36}},{\"view\":\"home\",\"layout\":\"*\",\"state\":\"*\",\"target\":\"qrDetail\",\"style\":{\"fontSize\":99}}]");
  JSONObject style=DesignOverrides.resolve(rules,"qrDetail","p");assertEquals(36,style.getInt("fontSize"));assertEquals("#ffffff",style.getString("color"));
 }
}
