package com.android.acerem.xemgp.tree;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.android.acerem.xemgp.data.ImageRepository;
import com.android.acerem.xemgp.data.Member;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Hardware-accelerated native canvas for pan/zoom/tap/double-tap tree interaction. */
public final class TreeCanvasView extends View {
    public interface Listener { void onMemberSelected(Member member, boolean openProfile); }

    private FamilyGraph graph;
    private Listener listener;
    private float scale = 1f, tx = 0, ty = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gesture;
    private final ScaleGestureDetector pinch;
    private final ImageRepository imageRepository;
    private String selected;
    private Set<String> focusIds, filterIds;
    private final Map<String, Bitmap> bitmaps = new LinkedHashMap<String, Bitmap>(24, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> entry) { return size() > 24; }
    };
    private final Set<String> loadingImages = new HashSet<>();
    private final Set<String> failedImages = new HashSet<>();
    private int imageGeneration;

    private static final float FAMILY_BRANCH_STROKE_WIDTH = 3.2f;
    private static final float FAMILY_LANE_GAP = FamilyGraph.CARD_W * .18f;
    private int bg = Color.rgb(23, 23, 23), surface = Color.rgb(27, 27, 27), textColor = Color.rgb(251, 250, 243);
    private int muted = Color.rgb(162, 170, 160), accent = Color.rgb(167, 197, 163), line = Color.rgb(91, 121, 103), copper = Color.rgb(228, 33, 33);

    public TreeCanvasView(Context context) {
        super(context);
        setFocusable(true);
        imageRepository = ImageRepository.get(context);
        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) { String id = hit(event.getX(), event.getY()); if (id != null && listener != null) listener.onMemberSelected(graph.byId.get(id), true); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { String id = hit(event.getX(), event.getY()); if (id != null && listener != null) listener.onMemberSelected(graph.byId.get(id), false); return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent second, float dx, float dy) { tx -= dx; ty -= dy; invalidate(); return true; }
            @Override public boolean onFling(MotionEvent first, MotionEvent second, float vx, float vy) { return true; }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float old = scale;
                scale = Math.max(.25f, Math.min(2.8f, scale * detector.getScaleFactor()));
                float fx = detector.getFocusX(), fy = detector.getFocusY();
                tx = fx - (fx - tx) * (scale / old);
                ty = fy - (fy - ty) * (scale / old);
                invalidate();
                return true;
            }
        });
    }

    private final ImageRepository.UpdateListener imageUpdateListener = () -> {
        failedImages.clear();
        invalidate();
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        imageRepository.addUpdateListener(imageUpdateListener);
    }

    @Override protected void onDetachedFromWindow() {
        imageRepository.removeUpdateListener(imageUpdateListener);
        super.onDetachedFromWindow();
    }

    public void setListener(Listener value) { listener = value; }
    public void setGraph(FamilyGraph value) { graph = value; imageGeneration++; bitmaps.clear(); loadingImages.clear(); failedImages.clear(); fit(); }
    public void setSelected(String id) { selected = id; invalidate(); }
    public void setFocusIds(Set<String> ids) { focusIds = ids; invalidate(); }
    public void setFilterIds(Set<String> ids) { filterIds = ids; invalidate(); }
    public void zoom(float factor) { float cx = getWidth() / 2f, cy = getHeight() / 2f, old = scale; scale = Math.max(.25f, Math.min(2.8f, scale * factor)); tx = cx - (cx - tx) * (scale / old); ty = cy - (cy - ty) * (scale / old); invalidate(); }
    public void fit() { if (graph == null || getWidth() == 0 || getHeight() == 0) return; float sx = (getWidth() - 32f) / Math.max(graph.width, 1), sy = (getHeight() - 32f) / Math.max(graph.height, 1); scale = Math.max(.25f, Math.min(1f, Math.min(sx, sy))); tx = (getWidth() - graph.width * scale) / 2f; ty = (getHeight() - graph.height * scale) / 2f; invalidate(); }
    public void focusMember(String id) { FamilyGraph.Node node = graph == null ? null : graph.nodes.get(id); if (node == null) return; scale = Math.max(scale, .72f); tx = getWidth() / 2f - node.cx() * scale; ty = getHeight() / 2f - node.cy() * scale; selected = id; invalidate(); }
    public void focusGeneration(int generation) { if (graph == null) return; float sum = 0; int count = 0; for (FamilyGraph.Node node : graph.nodes.values()) if (node.generation == generation) { sum += node.cx(); count++; } if (count > 0) { scale = Math.max(scale, .55f); tx = getWidth() / 2f - sum / count * scale; ty = getHeight() / 2f - (FamilyGraph.OUTER + generation * (FamilyGraph.CARD_H + FamilyGraph.ROW_GAP) + FamilyGraph.CARD_H / 2f) * scale; invalidate(); } }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { if (graph != null && width > 0 && height > 0) post(this::fit); }

    @Override protected void onDraw(Canvas canvas) {
        bg = com.android.acerem.xemgp.util.Ui.BG; surface = com.android.acerem.xemgp.util.Ui.SURFACE; textColor = com.android.acerem.xemgp.util.Ui.STRONG; muted = com.android.acerem.xemgp.util.Ui.MUTED; accent = com.android.acerem.xemgp.util.Ui.ACCENT; line = com.android.acerem.xemgp.util.Ui.LINE; copper = com.android.acerem.xemgp.util.Ui.COPPER;
        super.onDraw(canvas); canvas.drawColor(bg); if (graph == null) return;
        canvas.save(); canvas.translate(tx, ty); canvas.scale(scale, scale); drawEdges(canvas); for (FamilyGraph.Node node : graph.nodes.values()) drawNode(canvas, node); canvas.restore();
    }

    private void drawEdges(Canvas canvas) {
        drawFamilyEdges(canvas); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f);
        for (FamilyGraph.Edge edge : graph.edges) { if (edge.type.equals("parent")) continue; FamilyGraph.Node a = graph.nodes.get(edge.from), b = graph.nodes.get(edge.to); if (a == null || b == null) continue; if (filterIds != null && (!filterIds.contains(edge.from) || !filterIds.contains(edge.to))) continue; boolean dim = !matches(edge.from) && !matches(edge.to); paint.setColor(edge.type.equals("spouse") ? Color.rgb(198, 145, 105) : Color.rgb(126, 164, 145)); paint.setAlpha(dim ? 45 : 210); Path path = new Path(); path.moveTo(a.cx(), a.cy()); path.lineTo(b.cx(), b.cy()); canvas.drawPath(path, paint); }
        paint.setAlpha(255);
    }

    private void drawFamilyEdges(Canvas canvas) {
        if (graph.branches.isEmpty()) return;
        Map<Integer, List<FamilyGraph.Branch>> byGeneration = new TreeMap<>();
        for (FamilyGraph.Branch branch : graph.branches) { List<FamilyGraph.Node> parents = branchNodes(branch.parentIds), children = branchNodes(branch.childIds); if (parents.isEmpty() || children.isEmpty()) continue; if (filterIds != null) { boolean visible = true; for (FamilyGraph.Node node : parents) if (!filterIds.contains(node.member.id)) visible = false; if (!visible) continue; children.removeIf(node -> !filterIds.contains(node.member.id)); if (children.isEmpty()) continue; } byGeneration.computeIfAbsent(children.get(0).generation, key -> new java.util.ArrayList<>()).add(branch); }
        for (List<FamilyGraph.Branch> branches : byGeneration.values()) { branches.sort((a, b) -> { int compare = Float.compare(branchTarget(a), branchTarget(b)); return compare != 0 ? compare : a.id.compareTo(b.id); }); List<Float> usedLanes = new java.util.ArrayList<>(); int count = branches.size(); for (int index = 0; index < count; index++) { FamilyGraph.Branch branch = branches.get(index); List<FamilyGraph.Node> parents = branchNodes(branch.parentIds), children = branchNodes(branch.childIds); if (filterIds != null) children.removeIf(node -> !filterIds.contains(node.member.id)); if (parents.isEmpty() || children.isEmpty()) continue; float parentBottom = 0, childTop = Float.MAX_VALUE, parentMin = Float.MAX_VALUE, parentMax = -Float.MAX_VALUE, childMin = Float.MAX_VALUE, childMax = -Float.MAX_VALUE; for (FamilyGraph.Node node : parents) { parentBottom = Math.max(parentBottom, node.y + FamilyGraph.CARD_H); parentMin = Math.min(parentMin, node.cx()); parentMax = Math.max(parentMax, node.cx()); } for (FamilyGraph.Node node : children) { childTop = Math.min(childTop, node.y); childMin = Math.min(childMin, node.cx()); childMax = Math.max(childMax, node.cx()); } float gap = Math.max(24f, childTop - parentBottom), junctionY = parentBottom + gap * (index + 1f) / (count + 1f), entryY = parentBottom + gap * (index + .5f) / (count + 1f), laneX = uniqueLane(branchTarget(branch), usedLanes); usedLanes.add(laneX); paint.setColor(branchColor(branch.id)); paint.setAlpha(focusIds != null && !branchMatches(branch) ? 48 : 220); paint.setStrokeWidth(FAMILY_BRANCH_STROKE_WIDTH); paint.setStyle(Paint.Style.STROKE); Path path = new Path(); for (FamilyGraph.Node node : parents) { path.moveTo(node.cx(), node.y + FamilyGraph.CARD_H); path.lineTo(node.cx(), entryY); } path.moveTo(parentMin, entryY); path.lineTo(parentMax, entryY); path.lineTo(laneX, entryY); path.lineTo(laneX, junctionY); float min = Math.min(childMin, laneX), max = Math.max(childMax, laneX); path.moveTo(min, junctionY); path.lineTo(max, junctionY); for (FamilyGraph.Node node : children) { path.moveTo(node.cx(), junctionY); path.lineTo(node.cx(), node.y); } canvas.drawPath(path, paint); } }
        paint.setAlpha(255);
    }

    private List<FamilyGraph.Node> branchNodes(List<String> ids) { List<FamilyGraph.Node> result = new java.util.ArrayList<>(); for (String id : ids) { FamilyGraph.Node node = graph.nodes.get(id); if (node != null) result.add(node); } return result; }
    private float branchTarget(FamilyGraph.Branch branch) { float total = 0; int count = 0; for (String id : branch.childIds) { FamilyGraph.Node node = graph.nodes.get(id); if (node != null) { total += node.cx(); count++; } } return count == 0 ? 0 : total / count; }
    private float uniqueLane(float target, List<Float> used) { float lane = target, gap = FAMILY_LANE_GAP; for (int pass = 0; pass < used.size() + 1; pass++) { boolean collision = false; for (float other : used) if (Math.abs(lane - other) < gap) { collision = true; break; } if (!collision) return lane; lane += gap; } lane = target; for (int pass = 0; pass < used.size() + 1; pass++) { boolean collision = false; for (float other : used) if (Math.abs(lane - other) < gap) { collision = true; break; } if (!collision) return lane; lane -= gap; } return lane; }
    private boolean branchMatches(FamilyGraph.Branch branch) { for (String id : branch.parentIds) if (matches(id)) return true; for (String id : branch.childIds) if (matches(id)) return true; return false; }
    private int branchColor(String id) { int[] dark = {Color.rgb(143,168,216),Color.rgb(190,143,143),Color.rgb(211,174,91),Color.rgb(145,181,157),Color.rgb(180,151,198),Color.rgb(208,148,112)}; int[] light = {Color.rgb(76,105,151),Color.rgb(155,82,82),Color.rgb(153,112,28),Color.rgb(66,119,83),Color.rgb(116,79,139),Color.rgb(163,91,54)}; int index = (id.hashCode() & 0x7fffffff) % (com.android.acerem.xemgp.util.Ui.isDark(getContext()) ? dark.length : light.length); return com.android.acerem.xemgp.util.Ui.isDark(getContext()) ? dark[index] : light[index]; }

    private void drawNode(Canvas canvas, FamilyGraph.Node node) {
        if (filterIds != null && !filterIds.contains(node.member.id)) return;
        boolean dim = (focusIds != null && !focusIds.contains(node.member.id)) || (filterIds != null && !filterIds.contains(node.member.id)); boolean selectedNode = node.member.id.equals(selected); float alpha = dim ? 65 : 255;
        paint.setStyle(Paint.Style.FILL); paint.setColor(surface); paint.setAlpha((int) alpha); canvas.drawRoundRect(node.x, node.y, node.x + FamilyGraph.CARD_W, node.y + FamilyGraph.CARD_H, 16, 16, paint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(selectedNode ? 3 : 1); paint.setColor(selectedNode ? accent : Color.rgb(58, 58, 58)); canvas.drawRoundRect(node.x, node.y, node.x + FamilyGraph.CARD_W, node.y + FamilyGraph.CARD_H, 16, 16, paint); paint.setAlpha((int) alpha); paint.setStyle(Paint.Style.FILL); paint.setColor(hasDeathDate(node.member) ? copper : Color.rgb(126, 193, 81)); canvas.drawCircle(node.x + 187, node.y + 95, 3, paint);
        String filename = imageRepository.filenameFor(node.member); Bitmap portrait = filename == null ? null : bitmaps.get(filename); if (filename != null && portrait == null && isNodeOnScreen(node) && !loadingImages.contains(filename) && !failedImages.contains(filename)) requestImage(filename); paint.setColor(portrait == null ? com.android.acerem.xemgp.util.Ui.ACCENT_SOFT : Color.rgb(48, 48, 48)); canvas.drawCircle(node.x + 30, node.y + 31, 21, paint); if (portrait != null) drawCenterCrop(canvas, portrait, new RectF(node.x + 9, node.y + 10, node.x + 51, node.y + 52), paint);
        text.setTypeface(Typeface.create("sans", Typeface.BOLD)); text.setTextSize(12); text.setColor(textColor); text.setAlpha((int) alpha); String[] words = node.member.fullName.trim().split("\\s+"); String line1 = node.member.fullName, line2 = ""; if (line1.length() > 22 && words.length > 2) { int pivot = words.length / 2; line1 = join(words, 0, pivot); line2 = join(words, pivot, words.length); } if (portrait == null) { text.setColor(accent); canvas.drawText(initials(node.member.fullName), node.x + 20, node.y + 35, text); } canvas.drawText(line1, node.x + 64, node.y + 31, text); if (!line2.isEmpty()) canvas.drawText(line2, node.x + 64, node.y + 46, text); text.setTypeface(Typeface.create("sans", Typeface.NORMAL)); text.setTextSize(10); text.setColor(muted); canvas.drawText(node.member.lifeDates(), node.x + 64, node.y + (line2.isEmpty() ? 51 : 65), text); String role = node.member.occupation == null || node.member.occupation.isEmpty() ? (node.member.gender.equalsIgnoreCase("female") ? "Thành viên nữ" : "Thành viên gia đình") : node.member.occupation; if (role.length() > 23) role = role.substring(0, 22) + "…"; canvas.drawText(role, node.x + 64, node.y + (line2.isEmpty() ? 70 : 85), text);
        if (node.member.siblingOrder > 0) { paint.setColor(accent); paint.setAlpha((int) alpha); canvas.drawCircle(node.x + 186, node.y + 18, 9, paint); text.setColor(bg); text.setTextSize(9); text.setTypeface(Typeface.DEFAULT_BOLD); canvas.drawText(String.valueOf(node.member.siblingOrder), node.x + 183, node.y + 21, text); } paint.setAlpha(255);
    }

    private boolean isNodeOnScreen(FamilyGraph.Node node) { float left = node.x * scale + tx, top = node.y * scale + ty, right = (node.x + FamilyGraph.CARD_W) * scale + tx, bottom = (node.y + FamilyGraph.CARD_H) * scale + ty; return right >= 0 && left <= getWidth() && bottom >= 0 && top <= getHeight(); }
    private void requestImage(String filename) { loadingImages.add(filename); int generation = imageGeneration; imageRepository.loadLocal(filename, 96, 96, bitmap -> { loadingImages.remove(filename); if (bitmap == null) failedImages.add(filename); else bitmaps.put(filename, bitmap); if (generation == imageGeneration) invalidate(); }); }
    private static void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination, Paint bitmapPaint) { int width = bitmap.getWidth(), height = bitmap.getHeight(); if (width <= 0 || height <= 0) return; Rect source; if (width > height) { int left = (width - height) / 2; source = new Rect(left, 0, left + height, height); } else { int top = (height - width) / 2; source = new Rect(0, top, width, top + width); } canvas.drawBitmap(bitmap, source, destination, bitmapPaint); }
    private static boolean hasDeathDate(Member member) { String date = member.deathDate; return date != null && !date.trim().isEmpty() && !"null".equalsIgnoreCase(date.trim()); }
    private boolean matches(String id) { return (focusIds == null || focusIds.contains(id)) && (filterIds == null || filterIds.contains(id)); }
    private String hit(float sx, float sy) { if (graph == null) return null; float x = (sx - tx) / scale, y = (sy - ty) / scale; for (FamilyGraph.Node node : graph.nodes.values()) if (x >= node.x && x <= node.x + FamilyGraph.CARD_W && y >= node.y && y <= node.y + FamilyGraph.CARD_H) return node.member.id; return null; }
    @Override public boolean onTouchEvent(MotionEvent event) { pinch.onTouchEvent(event); gesture.onTouchEvent(event); return true; }
    private static String initials(String name) { String[] parts = name.trim().split("\\s+"); String result = ""; for (int i = Math.max(0, parts.length - 2); i < parts.length; i++) result += parts[i].substring(0, 1).toUpperCase(java.util.Locale.ROOT); return result.isEmpty() ? "?" : result; }
    private static String join(String[] values, int from, int to) { StringBuilder result = new StringBuilder(); for (int i = from; i < to; i++) { if (result.length() > 0) result.append(' '); result.append(values[i]); } return result.toString(); }
}
