package com.cleanframe.philips;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Scanner;
import java.util.Iterator;

/** Only visual values from the packaged design document; no account or connection state. */
final class DesignOverrides {
    private DesignOverrides() { }
    static void applyQr(View root, View qr, TextView title, TextView detail, TextView pairing, TextView back) {
        root.post(() -> {
            try (Scanner scanner = new Scanner(root.getContext().getAssets().open("home/design.json"), "UTF-8")) {
                JSONObject design = new JSONObject(scanner.useDelimiter("\\A").next());
                if (design.optInt("version") != 1) return;
                JSONArray rules = design.getJSONArray("rules");
                float scale = root.getWidth() / 1920f;
                apply(root, resolve(rules,"qrRoot","section"),scale);
                apply(qr, resolve(rules,"qrCode","div"),scale);
                apply(title, resolve(rules,"qrTitle","h2"),scale);
                apply(detail, resolve(rules,"qrDetail","p"),scale);
                apply(pairing, resolve(rules,"qrPairing","p"),scale);
                apply(back, resolve(rules,"qrBack","button"),scale);
            } catch (Exception ignored) { /* Empty/invalid optional design retains the existing native screen. */ }
        });
    }
    static JSONObject resolve(JSONArray rules, String id, String type) throws Exception {
        JSONObject out = new JSONObject();
        // Same precedence as the web resolver: templates first, element values last.
        for (int weight=0;weight<=15;weight++) for (int i=0;i<rules.length();i++) {
            JSONObject rule=rules.getJSONObject(i);String view=rule.getString("view"),target=rule.getString("target");
            if (!(view.equals("*")||view.equals("qr")) || !rule.getString("layout").equals("*")) continue;
            if (!(target.equals(id)||target.equals("type:"+type))) continue;
            String state=rule.getString("state");if (!(state.equals("*")||state.equals("compact"))) continue;
            int rank=(target.startsWith("type:")?0:8)+(view.equals("*")?0:4)+(state.equals("*")?0:1);
            if(rank!=weight)continue;
            JSONObject style=rule.getJSONObject("style");Iterator<String> keys=style.keys();while(keys.hasNext()){String key=keys.next();out.put(key,style.get(key));}
        }
        return out;
    }
    private static int color(String css) {
        if(css.length()==9)css="#"+css.substring(7,9)+css.substring(1,7);
        return Color.parseColor(css);
    }
    private static void apply(View view, JSONObject style, float scale) throws Exception {
        if(style.length()==0)return;
        ViewGroup.LayoutParams params=view.getLayoutParams();
        if(style.has("width"))params.width=Math.round((float)style.getDouble("width")*scale);
        if(style.has("height"))params.height=Math.round((float)style.getDouble("height")*scale);
        if(style.has("spacing")&&params instanceof ViewGroup.MarginLayoutParams){int margin=Math.round((float)style.getDouble("spacing")*scale);((ViewGroup.MarginLayoutParams)params).setMargins(margin,margin,margin,margin);}
        if(params instanceof ViewGroup.MarginLayoutParams){
            ViewGroup.MarginLayoutParams m=(ViewGroup.MarginLayoutParams)params;
            m.setMargins(pixels(style,"marginLeft",m.leftMargin,scale),pixels(style,"marginTop",m.topMargin,scale),pixels(style,"marginRight",m.rightMargin,scale),pixels(style,"marginBottom",m.bottomMargin,scale));
        }
        view.setLayoutParams(params);
        if(style.has("offsetX"))view.setTranslationX((float)style.getDouble("offsetX")*scale);
        if(style.has("offsetY"))view.setTranslationY((float)style.getDouble("offsetY")*scale);
        if(style.has("opacity"))view.setAlpha((float)style.getDouble("opacity"));
        if(style.has("padding")){int p=Math.round((float)style.getDouble("padding")*scale);view.setPadding(p,p,p,p);}
        view.setPadding(pixels(style,"paddingLeft",view.getPaddingLeft(),scale),pixels(style,"paddingTop",view.getPaddingTop(),scale),pixels(style,"paddingRight",view.getPaddingRight(),scale),pixels(style,"paddingBottom",view.getPaddingBottom(),scale));
        if(style.has("background")||style.has("radius")){
            GradientDrawable background=new GradientDrawable();background.setColor(style.has("background")?color(style.getString("background")):view instanceof android.widget.Button?Color.rgb(49,49,49):view instanceof android.widget.ImageView?Color.rgb(39,39,39):view instanceof android.widget.LinearLayout?Color.rgb(24,24,24):Color.TRANSPARENT);
            background.setCornerRadius((float)style.optDouble("radius",0)*scale);view.setBackground(background);
        }
        if(view instanceof TextView){TextView text=(TextView)view;
            if(style.has("fontFamily")) {
                String family=style.getString("fontFamily");
                String font=family.equals("Study Geist")?"home/assets/fonts/geist/Geist-Variable.ttf":family.equals("Study Newsreader")?"home/assets/fonts/newsreader/Newsreader-Variable.ttf":null;
                if(font!=null)text.setTypeface(Typeface.createFromAsset(view.getContext().getAssets(),font));
            }
            if(style.has("fontSize"))text.setTextSize(TypedValue.COMPLEX_UNIT_PX,(float)style.getDouble("fontSize")*scale);
            if(style.has("fontWeight"))text.setTypeface(Typeface.create(text.getTypeface(),style.getInt("fontWeight"),false));
            if(style.has("color"))text.setTextColor(color(style.getString("color")));
            if(style.has("lineHeight"))text.setLineHeight(Math.round((float)style.getDouble("lineHeight")*scale));
            if(style.has("letterSpacing"))text.setLetterSpacing((float)style.getDouble("letterSpacing")*scale/text.getTextSize());
        }
    }
    private static int pixels(JSONObject style,String key,int fallback,float scale){
        return style.has(key)?Math.round((float)style.optDouble(key)*scale):fallback;
    }
}
