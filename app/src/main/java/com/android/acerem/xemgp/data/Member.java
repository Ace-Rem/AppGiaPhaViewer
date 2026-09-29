package com.android.acerem.xemgp.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Member {
    public final String id, fullName, gender, birthDate, deathDate, birthPlace, occupation, note, fatherId, motherId, familyRole;
    public final int generation, siblingOrder;
    public final List<String> spouseIds = new ArrayList<>();
    public final List<String> siblingIds = new ArrayList<>();

    public Member(JSONObject o) {
        id=o.optString("id"); fullName=o.optString("fullName"); gender=o.optString("gender");
        birthDate=o.optString("birthDate", ""); deathDate=o.optString("deathDate", "");
        birthPlace=o.optString("birthPlace", ""); occupation=o.optString("occupation", ""); note=o.optString("note", "");
        fatherId=nullable(o, "fatherId"); motherId=nullable(o, "motherId"); familyRole=o.optString("familyRole", "");
        generation=o.has("generation") && !o.isNull("generation") ? o.optInt("generation", 0) : 0;
        siblingOrder=o.has("siblingOrder") && !o.isNull("siblingOrder") ? o.optInt("siblingOrder", 0) : 0;
        copyIds(o.optJSONArray("spouseIds"), spouseIds); copyIds(o.optJSONArray("siblingIds"), siblingIds);
    }
    private static String nullable(JSONObject o,String k){return o.isNull(k)?null:(o.has(k)?o.optString(k,null):null);}
    private static void copyIds(JSONArray a,List<String> out){if(a==null)return;for(int i=0;i<a.length();i++){String v=a.optString(i,null);if(v!=null&&!v.isEmpty()&&!out.contains(v))out.add(v);}}
    public String birthYear(){return birthDate != null && birthDate.matches("^\\d{4}.*") ? birthDate.substring(0,4) : "";}
    public String lifeDates(){String b=birthYear(),d=deathDate!=null&&deathDate.matches("^\\d{4}.*")?deathDate.substring(0,4):"";if(b.isEmpty()&&d.isEmpty())return "Chưa rõ năm sinh";if(!b.isEmpty()&&!d.isEmpty())return b+" — "+d;return b+" — nay";}
    public String initials(){String[] p=fullName.trim().split("\\s+");String out="";for(int i=Math.max(0,p.length-2);i<p.length;i++)if(!p[i].isEmpty())out+=p[i].substring(0,1).toUpperCase();return out.isEmpty()?"?":out;}
}
