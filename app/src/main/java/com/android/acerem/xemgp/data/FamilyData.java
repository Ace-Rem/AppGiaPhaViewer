package com.android.acerem.xemgp.data;

import java.util.ArrayList;import java.util.LinkedHashMap;import java.util.List;import java.util.Map;

public final class FamilyData {
    public final String username, familyName, heroTitle, description, rootPersonId, fingerprint;
    public final int generationOffset;
    public final List<Member> members;
    public final Map<String,Member> byId=new LinkedHashMap<>();
    public FamilyData(String username,String familyName,String heroTitle,String description,String rootPersonId,int generationOffset,List<Member> members,String fingerprint){
        this.username=username;this.familyName=familyName;this.heroTitle=heroTitle;this.description=description;this.rootPersonId=rootPersonId;this.generationOffset=generationOffset;this.members=new ArrayList<>(members);this.fingerprint=fingerprint;for(Member m:members)byId.put(m.id,m);
    }
}
