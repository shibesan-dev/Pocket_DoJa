package opendoja.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Renders the per-key artwork from {@code assets/controls.svg} for the on-screen controls.
 *
 * <p>The SVG is the design source: every on-screen key has its own group ({@code key-soft1},
 * {@code key-1}, {@code dpad-up}, ...). The artwork is rasterized once per key (normal and pressed
 * variants) and then blitted into the rect computed by {@link JamSurfaceView}, so the buttons keep
 * their current layout and hit regions while using the skinned appearance.</p>
 */
public final class ControlsSkin {
    private static final String ASSET = "controls.svg";
    private static final float SUPER_SAMPLE = 3f;
    private static final float BITMAP_PADDING = 1.5f;
    private static final String[] GROUP_IDS = {
            "key-soft1", "key-soft2", "key-menu", "key-clear",
            "key-1", "key-2", "key-3", "key-4", "key-5", "key-6",
            "key-7", "key-8", "key-9", "key-star", "key-0", "key-hash",
            "dpad-up", "dpad-left", "dpad-center", "dpad-right", "dpad-down",
    };

    private static final Pattern STYLE_BLOCK = Pattern.compile("\\.([A-Za-z0-9_-]+)\\s*\\{([^}]*)\\}");
    private static final Pattern PATH_TOKEN = Pattern.compile(
            "[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface SANS_BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private final Map<String, Group> groups = new HashMap<>();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedOverlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fittedRect = new RectF();

    private ControlsSkin() {
    }

    public static ControlsSkin load(Context context) {
        try (InputStream input = context.getAssets().open(ASSET)) {
            ControlsSkin skin = new ControlsSkin();
            skin.parse(input);
            if (skin.groups.isEmpty()) {
                return null;
            }
            return skin;
        } catch (Throwable throwable) {
            opendoja.host.OpenDoJaLog.warn(ControlsSkin.class,
                    "controls.svg unavailable, using fallback keypad", throwable);
            return null;
        }
    }

    /**
     * Draws one skinned key into {@code destination}. Returns false when the key has no artwork,
     * so the caller can fall back to the plain rectangle renderer.
     */
    public boolean draw(Canvas canvas, String groupId, RectF destination, boolean pressed) {
        return draw(canvas, groupId, destination, pressed, 255, false);
    }

    public boolean draw(Canvas canvas, String groupId, RectF destination, boolean pressed,
                        int alpha) {
        return draw(canvas, groupId, destination, pressed, alpha, false);
    }

    /**
     * Draws one key. When {@code stretch} is set the face fills the destination rect (wider keys
     * for the portrait numeric pad); labels are always drawn as text at the final position, so
     * they stay undistorted.
     */
    public boolean draw(Canvas canvas, String groupId, RectF destination, boolean pressed,
                        int alpha, boolean stretch) {
        Group group = groups.get(groupId);
        if (group == null || destination.width() <= 0f || destination.height() <= 0f) {
            return false;
        }
        Bitmap bitmap = group.bitmap(pressed);
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        if (stretch) {
            fittedRect.set(destination);
        } else {
            // Scale the artwork uniformly so the SVG's rounded faces are never stretched by a
            // non-matching key cell aspect ratio.
            float scale = Math.min(destination.width() / bitmap.getWidth(),
                    destination.height() / bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            float left = destination.centerX() - width / 2f;
            float top = destination.centerY() - height / 2f;
            fittedRect.set(left, top, left + width, top + height);
        }
        int effectiveAlpha = Math.max(0, Math.min(255, alpha));
        bitmapPaint.setAlpha(effectiveAlpha);
        canvas.drawBitmap(bitmap, null, fittedRect, bitmapPaint);
        bitmapPaint.setAlpha(255);
        drawLabels(canvas, group, fittedRect, effectiveAlpha);
        if (pressed && groupId.startsWith("dpad-")) {
            int overlayAlpha = (int) (0x33 * (effectiveAlpha / 255f));
            pressedOverlay.setColor((overlayAlpha << 24) | 0x00FFFFFF);
            float radius = Math.min(fittedRect.width(), fittedRect.height()) * 0.16f;
            canvas.drawRoundRect(fittedRect, radius, radius, pressedOverlay);
        }
        return true;
    }

    private void drawLabels(Canvas canvas, Group group, RectF destination, int alpha) {
        if (group.labels.isEmpty()) {
            return;
        }
        float scaleX = destination.width() / group.bounds.width();
        float scaleY = destination.height() / group.bounds.height();
        for (Shape label : group.labels) {
            labelPaint.reset();
            labelPaint.setAntiAlias(true);
            labelPaint.setStyle(Paint.Style.FILL);
            int color = label.fillColor;
            int labelAlpha = (Color.alpha(color) * alpha) / 255;
            labelPaint.setColor((labelAlpha << 24) | (color & 0x00FFFFFF));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(label.bold ? SANS_BOLD : SANS);
            labelPaint.setTextSize(label.textSize * scaleY);
            float x = destination.left + (label.cx - group.bounds.left) * scaleX;
            float y = destination.top + (label.cy - group.bounds.top) * scaleY;
            Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            canvas.drawText(label.text, x, y - (metrics.ascent + metrics.descent) / 2f, labelPaint);
        }
    }

    private void parse(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document document = factory.newDocumentBuilder().parse(input);
        Map<String, Map<String, String>> css = parseCss(document);
        Map<String, Gradient> gradients = parseGradients(document);
        for (String id : GROUP_IDS) {
            Element element = findById(document, id);
            if (element == null) {
                continue;
            }
            Group group = parseGroup(element, css);
            if (group != null) {
                group.gradients = gradients;
                groups.put(id, group);
            }
        }
    }

    private static Element findById(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && id.equals(((Element) node).getAttribute("id"))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static Map<String, Map<String, String>> parseCss(Document document) {
        Map<String, Map<String, String>> blocks = new HashMap<>();
        NodeList styles = document.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            String text = styles.item(i).getTextContent();
            if (text == null) {
                continue;
            }
            Matcher matcher = STYLE_BLOCK.matcher(text);
            while (matcher.find()) {
                blocks.put(matcher.group(1), parseDeclarations(matcher.group(2)));
            }
        }
        return blocks;
    }

    private static Map<String, String> parseDeclarations(String text) {
        Map<String, String> properties = new HashMap<>();
        for (String declaration : text.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            properties.put(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    declaration.substring(colon + 1).trim());
        }
        return properties;
    }

    private static Map<String, Gradient> parseGradients(Document document) {
        Map<String, Gradient> gradients = new HashMap<>();
        NodeList nodes = document.getElementsByTagName("linearGradient");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("id");
            if (id == null || id.isEmpty()) {
                continue;
            }
            Gradient gradient = new Gradient();
            gradient.x1 = unit(element.getAttribute("x1"), 0f);
            gradient.y1 = unit(element.getAttribute("y1"), 0f);
            gradient.x2 = unit(element.getAttribute("x2"), 1f);
            gradient.y2 = unit(element.getAttribute("y2"), 0f);
            List<Integer> colors = new ArrayList<>();
            List<Float> positions = new ArrayList<>();
            NodeList stops = element.getElementsByTagName("stop");
            for (int s = 0; s < stops.getLength(); s++) {
                Element stop = (Element) stops.item(s);
                float offset = unit(stop.getAttribute("offset"), 0f);
                float opacity = unit(stop.getAttribute("stop-opacity"), 1f);
                colors.add(parseColor(stop.getAttribute("stop-color"), opacity));
                positions.add(offset);
            }
            if (colors.size() < 2) {
                continue;
            }
            gradient.colors = new int[colors.size()];
            for (int c = 0; c < colors.size(); c++) {
                gradient.colors[c] = colors.get(c);
            }
            gradient.positions = new float[positions.size()];
            for (int p = 0; p < positions.size(); p++) {
                gradient.positions[p] = positions.get(p);
            }
            gradients.put(id, gradient);
        }
        return gradients;
    }

    private static Group parseGroup(Element element, Map<String, Map<String, String>> css) {
        RectF bounds = null;
        List<Shape> shapes = new ArrayList<>();
        List<Shape> labels = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) {
                continue;
            }
            Element shapeElement = (Element) child;
            String tag = shapeElement.getTagName();
            Map<String, String> style = resolveStyle(shapeElement, css);
            Shape shape = new Shape();
            if ("rect".equals(tag)) {
                shape.type = Shape.RECT;
                shape.rect.set(
                        number(shapeElement.getAttribute("x"), 0f),
                        number(shapeElement.getAttribute("y"), 0f),
                        number(shapeElement.getAttribute("width"), 0f),
                        number(shapeElement.getAttribute("height"), 0f));
                shape.rect.right += shape.rect.left;
                shape.rect.bottom += shape.rect.top;
                shape.radius = number(shapeElement.getAttribute("rx"), 0f);
                if (bounds == null) {
                    bounds = new RectF(shape.rect);
                }
            } else if ("path".equals(tag)) {
                shape.type = Shape.PATH;
                shape.path = parsePath(shapeElement.getAttribute("d"));
            } else if ("circle".equals(tag)) {
                shape.type = Shape.CIRCLE;
                shape.cx = number(shapeElement.getAttribute("cx"), 0f);
                shape.cy = number(shapeElement.getAttribute("cy"), 0f);
                shape.radius = number(shapeElement.getAttribute("r"), 0f);
            } else if ("text".equals(tag)) {
                shape.type = Shape.TEXT;
                shape.text = shapeElement.getTextContent() == null
                        ? "" : shapeElement.getTextContent().trim();
                shape.cx = number(shapeElement.getAttribute("x"), 0f);
                shape.cy = number(shapeElement.getAttribute("y"), 0f);
            } else {
                continue;
            }
            applyStyle(shape, style);
            if (shape.type == Shape.TEXT) {
                labels.add(shape);
            } else {
                shapes.add(shape);
            }
        }
        if (bounds == null || shapes.isEmpty()) {
            return null;
        }
        Group group = new Group();
        group.bounds.set(bounds);
        group.bounds.inset(-BITMAP_PADDING, -BITMAP_PADDING);
        group.faceShapes.addAll(shapes);
        group.labels.addAll(labels);
        return group;
    }

    private static Map<String, String> resolveStyle(Element element,
                                                    Map<String, Map<String, String>> css) {
        Map<String, String> style = new HashMap<>();
        String classes = element.getAttribute("class");
        if (classes != null) {
            for (String name : classes.trim().split("\\s+")) {
                if (name.isEmpty()) {
                    continue;
                }
                Map<String, String> block = css.get(name);
                if (block != null) {
                    style.putAll(block);
                }
            }
        }
        for (String attribute : new String[]{"fill", "stroke", "stroke-width", "stroke-opacity",
                "font-size", "font-weight", "text-anchor"}) {
            String value = element.getAttribute(attribute);
            if (value != null && !value.isEmpty()) {
                style.put(attribute, value);
            }
        }
        return style;
    }

    private static void applyStyle(Shape shape, Map<String, String> style) {
        String fill = style.get("fill");
        if (fill != null) {
            if (fill.startsWith("url(")) {
                int start = fill.indexOf('#');
                int end = fill.indexOf(')', start + 1);
                if (start >= 0 && end > start) {
                    shape.gradientId = fill.substring(start + 1, end);
                    shape.hasFill = true;
                }
            } else if (!"none".equalsIgnoreCase(fill)) {
                shape.fillColor = parseColor(fill, 1f);
                shape.hasFill = true;
            }
        }
        String stroke = style.get("stroke");
        if (stroke != null && !"none".equalsIgnoreCase(stroke)) {
            float opacity = unit(style.get("stroke-opacity"), 1f);
            shape.strokeColor = parseColor(stroke, opacity);
            shape.hasStroke = true;
            shape.strokeWidth = number(style.get("stroke-width"), 1f);
        }
        String fontSize = style.get("font-size");
        if (fontSize != null) {
            shape.textSize = number(fontSize, 12f);
        }
        String weight = style.get("font-weight");
        if (weight != null) {
            try {
                shape.bold = Integer.parseInt(weight.trim()) >= 600;
            } catch (NumberFormatException ignored) {
                shape.bold = "bold".equalsIgnoreCase(weight.trim());
            }
        }
    }

    private static final class Group {
        final RectF bounds = new RectF();
        final List<Shape> faceShapes = new ArrayList<>();
        final List<Shape> labels = new ArrayList<>();
        Map<String, Gradient> gradients = new HashMap<>();
        Bitmap normal;
        Bitmap pressed;

        Bitmap bitmap(boolean pressedVariant) {
            if (pressedVariant) {
                if (pressed == null) {
                    pressed = render(gradients, faceShapes, bounds, true);
                }
                return pressed;
            }
            if (normal == null) {
                normal = render(gradients, faceShapes, bounds, false);
            }
            return normal;
        }
    }

    private static final class Shape {
        static final int RECT = 0;
        static final int PATH = 1;
        static final int CIRCLE = 2;
        static final int TEXT = 3;

        int type;
        final RectF rect = new RectF();
        Path path;
        float radius;
        float cx;
        float cy;
        String text;
        float textSize = 12f;
        boolean bold;
        int fillColor = Color.TRANSPARENT;
        boolean hasFill;
        String gradientId;
        int strokeColor = Color.TRANSPARENT;
        boolean hasStroke;
        float strokeWidth = 1f;
    }

    private static final class Gradient {
        float x1;
        float y1;
        float x2 = 1f;
        float y2;
        int[] colors;
        float[] positions;

        Shader shader(RectF bounds) {
            return new LinearGradient(
                    bounds.left + x1 * bounds.width(),
                    bounds.top + y1 * bounds.height(),
                    bounds.left + x2 * bounds.width(),
                    bounds.top + y2 * bounds.height(),
                    colors, positions, Shader.TileMode.CLAMP);
        }
    }

    private static Bitmap render(Map<String, Gradient> gradients, List<Shape> shapes,
                                 RectF bounds, boolean pressedVariant) {
        int width = Math.max(1, Math.round(bounds.width() * SUPER_SAMPLE));
        int height = Math.max(1, Math.round(bounds.height() * SUPER_SAMPLE));
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable throwable) {
            return null;
        }
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(width / bounds.width(), height / bounds.height());
        canvas.translate(-bounds.left, -bounds.top);
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (Shape shape : shapes) {
            if (shape.hasFill) {
                fillPaint.reset();
                fillPaint.setAntiAlias(true);
                fillPaint.setStyle(Paint.Style.FILL);
                String gradientId = shape.gradientId;
                if (pressedVariant && "keyFace".equals(gradientId)) {
                    gradientId = "keyFacePressed";
                }
                Gradient gradient = gradientId == null ? null : gradients.get(gradientId);
                if (gradient != null) {
                    RectF shaderBounds = shape.type == Shape.RECT ? shape.rect : bounds;
                    fillPaint.setShader(gradient.shader(shaderBounds));
                } else {
                    fillPaint.setColor(shape.fillColor);
                }
                drawShape(canvas, shape, fillPaint);
            }
            if (shape.hasStroke) {
                strokePaint.reset();
                strokePaint.setAntiAlias(true);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(shape.strokeWidth);
                strokePaint.setStrokeCap(Paint.Cap.BUTT);
                strokePaint.setStrokeJoin(Paint.Join.MITER);
                strokePaint.setColor(shape.strokeColor);
                drawShape(canvas, shape, strokePaint);
            }
        }
        return bitmap;
    }

    private static void drawShape(Canvas canvas, Shape shape, Paint paint) {
        switch (shape.type) {
            case Shape.RECT:
                canvas.drawRoundRect(shape.rect, shape.radius, shape.radius, paint);
                break;
            case Shape.PATH:
                if (shape.path != null) {
                    canvas.drawPath(shape.path, paint);
                }
                break;
            case Shape.CIRCLE:
                canvas.drawCircle(shape.cx, shape.cy, shape.radius, paint);
                break;
            case Shape.TEXT:
                paint.setTextSize(shape.textSize);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create("sans-serif",
                        shape.bold ? Typeface.BOLD : Typeface.NORMAL));
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float baseline = shape.cy - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(shape.text, shape.cx, baseline, paint);
                break;
            default:
                break;
        }
    }

    private static Path parsePath(String data) {
        Path path = new Path();
        if (data == null || data.isEmpty()) {
            return path;
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PATH_TOKEN.matcher(data);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        char command = 0;
        float x = 0f;
        float y = 0f;
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                index++;
                if (command == 'Z' || command == 'z') {
                    path.close();
                }
                continue;
            }
            switch (command) {
                case 'M':
                case 'm': {
                    if (index + 1 >= tokens.size()) {
                        index = tokens.size();
                        break;
                    }
                    float nx = number(tokens.get(index++), 0f);
                    float ny = number(tokens.get(index++), 0f);
                    if (command == 'm') {
                        nx += x;
                        ny += y;
                    }
                    x = nx;
                    y = ny;
                    path.moveTo(x, y);
                    command = command == 'm' ? 'l' : 'L';
                    break;
                }
                case 'L':
                case 'l': {
                    if (index + 1 >= tokens.size()) {
                        index = tokens.size();
                        break;
                    }
                    float nx = number(tokens.get(index++), 0f);
                    float ny = number(tokens.get(index++), 0f);
                    if (command == 'l') {
                        nx += x;
                        ny += y;
                    }
                    x = nx;
                    y = ny;
                    path.lineTo(x, y);
                    break;
                }
                case 'H':
                case 'h': {
                    if (index >= tokens.size()) {
                        index = tokens.size();
                        break;
                    }
                    float nx = number(tokens.get(index++), 0f);
                    if (command == 'h') {
                        nx += x;
                    }
                    x = nx;
                    path.lineTo(x, y);
                    break;
                }
                case 'V':
                case 'v': {
                    if (index >= tokens.size()) {
                        index = tokens.size();
                        break;
                    }
                    float ny = number(tokens.get(index++), 0f);
                    if (command == 'v') {
                        ny += y;
                    }
                    y = ny;
                    path.lineTo(x, y);
                    break;
                }
                case 'Q':
                case 'q': {
                    if (index + 3 >= tokens.size()) {
                        index = tokens.size();
                        break;
                    }
                    float cx = number(tokens.get(index++), 0f);
                    float cy = number(tokens.get(index++), 0f);
                    float nx = number(tokens.get(index++), 0f);
                    float ny = number(tokens.get(index++), 0f);
                    if (command == 'q') {
                        cx += x;
                        cy += y;
                        nx += x;
                        ny += y;
                    }
                    path.quadTo(cx, cy, nx, ny);
                    x = nx;
                    y = ny;
                    break;
                }
                default:
                    index++;
                    break;
            }
        }
        return path;
    }

    private static float unit(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        boolean percent = trimmed.endsWith("%");
        if (percent) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            float number = Float.parseFloat(trimmed);
            return percent ? number / 100f : number;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float number(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("px")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseColor(String value, float opacity) {
        if (value == null) {
            return Color.TRANSPARENT;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) {
            return Color.TRANSPARENT;
        }
        try {
            int color;
            if (trimmed.startsWith("#")) {
                String hex = trimmed.substring(1);
                if (hex.length() == 3) {
                    hex = new String(new char[]{
                            hex.charAt(0), hex.charAt(0),
                            hex.charAt(1), hex.charAt(1),
                            hex.charAt(2), hex.charAt(2)});
                }
                long valueBits = Long.parseLong(hex, 16);
                color = (int) (0xFF000000L | valueBits);
            } else {
                color = Color.parseColor(trimmed);
            }
            if (opacity < 1f) {
                int alpha = (int) (Color.alpha(color) * Math.max(0f, opacity));
                color = (color & 0x00FFFFFF) | (alpha << 24);
            }
            return color;
        } catch (Throwable ignored) {
            return Color.TRANSPARENT;
        }
    }
}
