package com.android.acerem.xemgp.tree;

import com.android.acerem.xemgp.data.FamilyData;
import com.android.acerem.xemgp.data.Member;
import java.text.Normalizer;
import java.util.*;

/** Relationship graph and layout inputs ported from viewer/tree.js. */
public final class FamilyGraph {
    public static final int CARD_W=204, CARD_H=112, UNIT_GAP=58, SIBLING_GAP=18, ROW_GAP=132, OUTER=100;
    public final FamilyData data; public final Map<String,Member> byId=new LinkedHashMap<>(); public final Map<String,List<String>> parents=new HashMap<>(), children=new HashMap<>(); public final Map<String,Set<String>> siblings=new HashMap<>(); public final Map<String,Integer> generations=new HashMap<>(); public final Map<String,Node> nodes=new LinkedHashMap<>(); public final List<Edge> edges=new ArrayList<>(); public final List<Branch> branches=new ArrayList<>(); public int maxGeneration,width,height;
    public static final class Node { public final Member member; public final int generation; public float x,y; Node(Member m,int g){member=m;generation=g;} public float cx(){return x+CARD_W/2f;} public float cy(){return y+CARD_H/2f;} }
    public static final class Edge { public final String type,from,to; Edge(String t,String f,String tt){type=t;from=f;to=tt;} }
    public static final class Branch { public final String id; public final List<String> parentIds=new ArrayList<>(),childIds=new ArrayList<>(); Branch(String key,List<String> parents){id=key;parentIds.addAll(parents);} }
    public FamilyGraph(FamilyData d){data=d; for(Member m:d.members){byId.put(m.id,m);children.put(m.id,new ArrayList<>());siblings.put(m.id,new LinkedHashSet<>());} buildRelations(); resolveGenerations(); layout();}
    private void buildRelations(){ for(Member m:data.members){List<String> ps=parents.computeIfAbsent(m.id,k->new ArrayList<>()); if(valid(m.fatherId)){ps.add(m.fatherId);children.get(m.fatherId).add(m.id);} if(valid(m.motherId)){ps.add(m.motherId);children.get(m.motherId).add(m.id);} } for(Member m:data.members){for(String id:m.siblingIds) addSibling(m.id,id); for(String id:children.get(m.id)) if(isBiological(byId.get(id))){} } for(List<String> list:children.values()){List<String> bio=new ArrayList<>(); for(String id:list)if(isBiological(byId.get(id)))bio.add(id); for(int i=0;i<bio.size();i++)for(int j=i+1;j<bio.size();j++)addSibling(bio.get(i),bio.get(j));} }
    private boolean valid(String id){return id!=null&&byId.containsKey(id);} private boolean isBiological(Member m){return m==null||!"inlaw".equalsIgnoreCase(m.familyRole)&&!"in-law".equalsIgnoreCase(m.familyRole)&&!"con-dau".equalsIgnoreCase(m.familyRole)&&!"con-re".equalsIgnoreCase(m.familyRole);}
    private void addSibling(String a,String b){if(!valid(a)||!valid(b)||a.equals(b)||!isBiological(byId.get(a))||!isBiological(byId.get(b)))return; siblings.get(a).add(b);siblings.get(b).add(a);}
    private void resolveGenerations(){for(Member m:data.members)generation(m.id,new HashSet<>()); maxGeneration=0;for(int g:generations.values())maxGeneration=Math.max(maxGeneration,g);}
    private int generation(String id,Set<String> visiting){if(generations.containsKey(id))return generations.get(id); Member m=byId.get(id); if(m==null)return 0; if(m.generation>0){int g=m.generation-1;generations.put(id,g);return g;} if(!visiting.add(id))return 0; int g=0;for(String p:parents.getOrDefault(id,Collections.emptyList()))g=Math.max(g,generation(p,visiting)+1);visiting.remove(id);generations.put(id,g);return g;}
    private int order(Member m){return m!=null&&m.siblingOrder>0?m.siblingOrder:Integer.MAX_VALUE;}
    private String groupKey(Member m){List<String> p=parents.getOrDefault(m.id,Collections.emptyList()); return p.isEmpty()?m.id:p.get(0);}
    private void layout(){
        Map<Integer,List<Member>> rows=new TreeMap<>();
        for(Member m:data.members) rows.computeIfAbsent(generations.getOrDefault(m.id,0),k->new ArrayList<>()).add(m);
        Map<String,Integer> siblingGroups=new HashMap<>(); int group=0;
        for(Member member:data.members) if(!siblingGroups.containsKey(member.id)){Deque<String> q=new ArrayDeque<>();q.add(member.id);siblingGroups.put(member.id,group);while(!q.isEmpty()){for(String id:siblings.getOrDefault(q.remove(),Collections.emptySet()))if(!siblingGroups.containsKey(id)){siblingGroups.put(id,group);q.add(id);}}group++;}
        Map<Integer,Float> rowWidths=new HashMap<>();float maxRow=0;
        for(Map.Entry<Integer,List<Member>> entry:rows.entrySet()){
            List<Member> row=entry.getValue();Set<String> used=new HashSet<>();List<List<Member>> units=new ArrayList<>();
            row.sort((a,b)->{int c=Integer.compare(siblingGroups.getOrDefault(a.id,Integer.MAX_VALUE),siblingGroups.getOrDefault(b.id,Integer.MAX_VALUE));if(c!=0)return c;c=Integer.compare(order(a),order(b));return c!=0?c:a.fullName.compareToIgnoreCase(b.fullName);});
            for(Member m:row){if(used.contains(m.id))continue;List<Member> unit=new ArrayList<>();unit.add(m);used.add(m.id);for(String spouse:m.spouseIds){Member other=byId.get(spouse);if(other!=null&&!used.contains(spouse)&&generations.getOrDefault(spouse,0).equals(generations.getOrDefault(m.id,0))){unit.add(other);used.add(spouse);break;}}units.add(unit);}
            float w=0;for(List<Member> unit:units)w+=unit.size()*CARD_W+(unit.size()-1)*14+UNIT_GAP;w=Math.max(0,w-UNIT_GAP);rowWidths.put(entry.getKey(),w);maxRow=Math.max(maxRow,w);
            row.clear();for(List<Member> unit:units)row.addAll(unit);
        }
        width=(int)Math.max(940,maxRow+OUTER*2);float y=OUTER;
        for(int generation=0;generation<=maxGeneration;generation++){List<Member> row=rows.getOrDefault(generation,Collections.emptyList());float x=OUTER+(maxRow-rowWidths.getOrDefault(generation,0f))/2f;for(Member m:row){Node n=new Node(m,generation);n.x=x;n.y=y;nodes.put(m.id,n);x+=CARD_W+14;}y+=CARD_H+ROW_GAP;}
        height=(int)y;buildEdges();buildBranches();
    }
    private void buildEdges(){for(Member m:data.members){Node child=nodes.get(m.id);for(String p:parents.getOrDefault(m.id,Collections.emptyList())){Node parent=nodes.get(p);if(parent!=null)edges.add(new Edge("parent",p,m.id));}for(String s:m.spouseIds){Node spouse=nodes.get(s);if(spouse!=null&&m.id.compareTo(s)<0&&generations.get(m.id).equals(generations.get(s)))edges.add(new Edge("spouse",m.id,s));}for(String s:siblings.getOrDefault(m.id,Collections.emptySet()))if(m.id.compareTo(s)<0&&nodes.containsKey(s)&&generations.get(m.id).equals(generations.get(s)))edges.add(new Edge("sibling",m.id,s));}}
    private void buildBranches(){Map<String,Branch> grouped=new LinkedHashMap<>();for(Member child:data.members){List<String> ps=new ArrayList<>(parents.getOrDefault(child.id,Collections.emptyList()));ps.removeIf(id->!valid(id));if(ps.isEmpty())continue;Collections.sort(ps);StringBuilder key=new StringBuilder("branch:");for(String id:ps)key.append(id).append("|");String branchId=key.toString();Branch branch=grouped.get(branchId);if(branch==null){branch=new Branch(branchId,ps);grouped.put(branchId,branch);}if(!branch.childIds.contains(child.id))branch.childIds.add(child.id);}branches.clear();branches.addAll(grouped.values());branches.sort(Comparator.comparing(b->b.id));}
    public Set<String> relationLine(String id,boolean ancestors,boolean descendants){Set<String> result=new LinkedHashSet<>();if(id==null)return result;Deque<String> q=new ArrayDeque<>();q.add(id);while(!q.isEmpty()){String cur=q.remove();List<String> ps=ancestors?parents.getOrDefault(cur,new ArrayList<String>()):new ArrayList<String>();for(String p:ps)if(result.add(p))q.add(p);List<String> cs=descendants?children.getOrDefault(cur,new ArrayList<String>()):new ArrayList<String>();for(String child:cs)if(result.add(child))q.add(child);}result.add(id);return result;}
    public Set<String> familyIds(){String family=normalize(data.familyName);int marker=family.lastIndexOf(" ho ");String familyPart=family.startsWith("ho ")?family.substring(3):(marker>=0?family.substring(marker+4):family);String surname=familyPart.trim().split("\\s+")[0];Set<String> ids=new HashSet<>();for(Member m:data.members){String n=normalize(m.fullName);if(!surname.isEmpty()&&n.startsWith(surname+" "))ids.add(m.id);}return ids;}
    public static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}+","").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT).trim();}
    public static String imageFilename(Member m){if(m==null||m.fullName.trim().isEmpty()||m.birthYear().isEmpty())return null;String n=normalize(m.fullName).replaceAll("[^a-z0-9]","");return n.isEmpty()?null:n+m.birthYear()+".webp";}
}
