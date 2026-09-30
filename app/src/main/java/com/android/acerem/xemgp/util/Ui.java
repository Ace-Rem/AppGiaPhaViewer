package com.android.acerem.xemgp.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Shared native palette, matching the five Web Viewer color themes. */
public final class Ui {
    public static int BG, SURFACE, RAISED, TEXT, STRONG, MUTED, ACCENT, COPPER, LINE, ACCENT_SOFT;
    private static final String PREFS="family-ui", PALETTE="palette", DARK="dark";
    private Ui() {}
    public static void configure(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREFS,0);
        String palette=p.getString(PALETTE,"blue"); boolean dark=p.getBoolean(DARK,true);
        int accent; switch(palette){case "red":accent=Color.rgb(231,63,30);break;case "yellow":accent=Color.rgb(255,200,30);break;case "green":accent=Color.rgb(126,193,81);break;case "orange":accent=Color.rgb(255,127,80);break;default:accent=Color.rgb(118,146,255);}
        ACCENT=accent; COPPER=Color.rgb(223,21,21);
        if(dark){BG=Color.rgb(23,23,23);SURFACE=Color.rgb(27,27,27);RAISED=Color.rgb(37,37,37);TEXT=Color.rgb(231,233,223);STRONG=Color.rgb(251,250,243);MUTED=Color.rgb(162,170,160);LINE=Color.rgb(58,58,58);}
        else {BG=Color.rgb(250,249,246);SURFACE=Color.rgb(255,254,252);RAISED=Color.WHITE;TEXT=Color.rgb(41,48,44);STRONG=Color.rgb(28,38,34);MUTED=Color.rgb(123,129,121);LINE=Color.rgb(218,218,214);}
        ACCENT_SOFT=dark?Color.rgb(48,48,48):blend(BG,ACCENT,.14f);
    }
    public static boolean isDark(Context c){return c.getSharedPreferences(PREFS,0).getBoolean(DARK,true);}
    public static String palette(Context c){return c.getSharedPreferences(PREFS,0).getString(PALETTE,"blue");}
    public static void setTheme(Context c,String palette,boolean dark){c.getSharedPreferences(PREFS,0).edit().putString(PALETTE,palette).putBoolean(DARK,dark).apply();configure(c);}
    public static void applyBars(Activity a){a.getWindow().setStatusBarColor(BG);a.getWindow().setNavigationBarColor(BG);int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE;if(!isDark(a))flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;a.getWindow().getDecorView().setSystemUiVisibility(flags);}
    public static TextView text(Context c,String value,float size,int color){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    public static TextView heading(Context c,String value,float size){TextView v=text(c,value,size,STRONG);v.setTypeface(Typeface.create("sans",Typeface.BOLD));return v;}
    public static GradientDrawable bg(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    public static GradientDrawable outline(int fill,int stroke,float radius){GradientDrawable d=bg(fill,radius);d.setStroke(1,stroke);return d;}
    public static Button button(Context c,String label,boolean primary){Button b=new Button(c);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?BG:TEXT);b.setTypeface(Typeface.DEFAULT_BOLD);b.setMinHeight(dp(c,50));b.setPadding(dp(c,18),0,dp(c,18),0);b.setBackground(primary?bg(ACCENT,dp(c,14)):outline(RAISED,LINE,dp(c,14)));return b;}
    private static int blend(int from,int to,float amount){int r=(int)(Color.red(from)+(Color.red(to)-Color.red(from))*amount);int g=(int)(Color.green(from)+(Color.green(to)-Color.green(from))*amount);int b=(int)(Color.blue(from)+(Color.blue(to)-Color.blue(from))*amount);return Color.rgb(r,g,b);}
    public static int dp(Context c,int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    public static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    public static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(android.view.Gravity.CENTER_VERTICAL);return l;}
    public static void margin(View v,int l,int t,int r,int b){if(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)v.getLayoutParams();p.setMargins(l,t,r,b);v.setLayoutParams(p);}}
}



