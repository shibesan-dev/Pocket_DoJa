package com.nttdocomo.ui;

import opendoja.compat.awt.Color;
import opendoja.compat.awt.FontMetrics;
import opendoja.compat.awt.Graphics2D;
import opendoja.compat.awt.image.BufferedImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-side flow layout and painter for high-level {@link Panel} components.
 *
 * <p>DoJa panels are laid out by the handset, not by the application. This
 * class provides the equivalent behaviour for the emulator: a simple HTML-like
 * flow (components stay on a line until a {@code br} or until they no longer
 * fit), a scrolling viewport, and focus highlighting.</p>
 */
public final class PanelRenderer {
    private static final int PADDING_X = 2;
    private static final int PADDING_Y = 2;
    private static final int COMPONENT_GAP = 2;

    private static final int COLOR_TITLE_BG = 0xFFE8E8E8;
    private static final int COLOR_TITLE_TEXT = 0xFF000000;
    private static final int COLOR_BUTTON_FACE = 0xFFE4E4E4;
    private static final int COLOR_BUTTON_BORDER = 0xFF707070;
    private static final int COLOR_DISABLED_TEXT = 0xFF909090;
    private static final int COLOR_FOCUS = 0xFF000000;
    private static final int COLOR_LIST_BORDER = 0xFF808080;
    private static final int COLOR_LIST_CURSOR = 0xFFD0D8E8;
    private static final int COLOR_SELECTED = 0xFF4060A0;
    private static final int COLOR_SCROLL_TRACK = 0xFFD8D8D8;
    private static final int COLOR_SCROLL_THUMB = 0xFF808080;

    private PanelRenderer() {
    }

    /**
     * Lays the panel out for an incoming input event. Bounds must be current
     * before focus traversal starts.
     */
    static void layoutForInput(Panel panel) {
        synchronized (panel) {
            layout(panel, panel.getWidth(), panel.getHeight());
        }
    }

    /**
     * Lays the panel out and returns a fresh snapshot suitable for presenting
     * through the normal frame pipeline.
     */
    public static BufferedImage snapshot(Panel panel, int width, int height) {
        synchronized (panel) {
            Layout layout = layout(panel, width, height);
            panel.hostEnsureInitialFocus();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(opendoja.compat.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        opendoja.compat.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                paintPanel(panel, layout, width, height, graphics);
            } finally {
                graphics.dispose();
            }
            return image;
        }
    }

    private static Layout layout(Panel panel, int width, int height) {
        Font titleFont = Font.getDefaultFont();
        String title = panel.hostTitle();
        int titleHeight = title.isEmpty() ? 0 : titleFont.getHeight() + 6;
        int viewportHeight = Math.max(0, height - titleHeight);
        int contentWidth = Math.max(1, width - PADDING_X * 2);
        int defaultLineHeight = Math.max(1, titleFont.getHeight() + 2);

        Flow flow = new Flow(PADDING_X, PADDING_Y, width - PADDING_X);
        for (Panel.FlowEntry entry : panel.hostFlow()) {
            Component component = entry.component();
            if (!component.visible()) {
                continue;
            }
            if (entry.breaks() > 0) {
                // The first br simply starts the next line; any extra br adds a
                // blank line. When no line is open yet every br is blank space.
                int blankLines = flow.hasLineContent() ? entry.breaks() - 1 : entry.breaks();
                flow.breakLine(blankLines * defaultLineHeight);
            }
            Font font = component.font();
            if (component instanceof Label label) {
                layoutLabel(label, font, contentWidth, flow);
            } else if (component instanceof ListBox listBox) {
                layoutListBox(listBox, font, contentWidth, defaultLineHeight, flow);
            } else if (component instanceof TextBox textBox) {
                layoutTextBox(textBox, font, contentWidth, defaultLineHeight, flow);
            } else if (component instanceof Button button) {
                layoutButton(button, font, contentWidth, flow);
            } else if (component instanceof AnchorButton anchorButton) {
                layoutAnchorButton(anchorButton, font, contentWidth, flow);
            } else {
                layoutGeneric(component, font, contentWidth, defaultLineHeight, flow);
            }
        }

        int contentHeight = flow.contentBottom;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        int scrollOffset = panel.hostScrollOffset();
        if (scrollOffset < 0) {
            scrollOffset = 0;
        } else if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        panel.hostSetScrollOffset(scrollOffset);
        panel.hostSetContentHeight(contentHeight);
        panel.hostSetViewportHeight(viewportHeight);
        return new Layout(titleHeight, viewportHeight, contentHeight, scrollOffset);
    }

    private static void layoutLabel(Label label, Font font, int contentWidth, Flow flow) {
        List<String> lines = wrap(font, label.hostText(), Math.max(1, contentWidth - 4));
        int lineHeight = Math.max(1, font.getHeight() + 1);
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, font.stringWidth(line));
        }
        int boxWidth = Math.min(contentWidth, textWidth + 4);
        int boxHeight = Math.max(1, lines.size()) * lineHeight;
        flow.place(label, boxWidth, boxHeight);
    }

    private static void layoutListBox(ListBox listBox, Font font, int contentWidth,
                                      int defaultLineHeight, Flow flow) {
        int rows = Math.max(1, Math.min(listBox.hostRows(), Math.max(1, listBox.hostItemCount())));
        int itemHeight = Math.max(defaultLineHeight, font.getHeight() + 3);
        flow.newLineIfNeeded();
        int boxHeight = rows * itemHeight + 2;
        flow.placeBlock(listBox, contentWidth, boxHeight);
    }

    private static void layoutTextBox(TextBox textBox, Font font, int contentWidth,
                                      int defaultLineHeight, Flow flow) {
        int columns = Math.max(1, textBox.hostColumns());
        int boxWidth = Math.min(contentWidth, Math.max(20, font.stringWidth("W") * columns + 8));
        int rows = Math.max(1, textBox.hostRows());
        int boxHeight = rows * defaultLineHeight + 4;
        flow.newLineIfNeeded();
        flow.placeBlock(textBox, boxWidth, boxHeight);
    }

    private static void layoutButton(Button button, Font font, int contentWidth, Flow flow) {
        int boxWidth = Math.min(contentWidth, Math.max(24, font.stringWidth(button.hostLabel()) + 16));
        int boxHeight = Math.max(12, font.getHeight() + 8);
        flow.place(button, boxWidth, boxHeight);
    }

    private static void layoutAnchorButton(AnchorButton button, Font font, int contentWidth, Flow flow) {
        int boxWidth = Math.min(contentWidth, Math.max(24, font.stringWidth(button.hostText()) + 16));
        int boxHeight = Math.max(12, font.getHeight() + 8);
        flow.place(button, boxWidth, boxHeight);
    }

    private static void layoutGeneric(Component component, Font font, int contentWidth,
                                      int defaultLineHeight, Flow flow) {
        int boxWidth = component.getWidth() > 0 ? Math.min(contentWidth, component.getWidth()) : contentWidth;
        int boxHeight = component.getHeight() > 0 ? component.getHeight() : defaultLineHeight;
        flow.place(component, boxWidth, boxHeight);
    }

    private static void paintPanel(Panel panel, Layout layout, int width, int height, Graphics2D graphics) {
        graphics.setColor(color(panel.backgroundColor(), 0xFFFFFFFF));
        graphics.fillRect(0, 0, width, height);

        String title = panel.hostTitle();
        if (!title.isEmpty()) {
            Font titleFont = Font.getDefaultFont();
            graphics.setColor(new Color(COLOR_TITLE_BG, true));
            graphics.fillRect(0, 0, width, layout.titleHeight);
            graphics.setColor(new Color(COLOR_TITLE_TEXT, true));
            graphics.setFont(titleFont.awtFont());
            FontMetrics metrics = graphics.getFontMetrics();
            int baseline = Math.max(metrics.getAscent(), (layout.titleHeight + metrics.getAscent()) / 2);
            int textWidth = titleFont.stringWidth(title);
            titleFont.drawHostString(graphics, title, Math.max(2, (width - textWidth) / 2),
                    baseline, COLOR_TITLE_TEXT);
        }

        Graphics2D content = (Graphics2D) graphics.create();
        try {
            content.clipRect(0, layout.titleHeight, width, Math.max(1, layout.viewportHeight));
            content.translate(0, layout.titleHeight - layout.scrollOffset);
            for (Component component : panel.hostComponents()) {
                if (component.visible()) {
                    paintComponent(component, content);
                }
            }
        } finally {
            content.dispose();
        }

        paintScrollBar(layout, width, height, graphics);
    }

    private static void paintComponent(Component component, Graphics2D graphics) {
        int x = component.getX();
        int y = component.getY();
        int width = Math.max(1, component.getWidth());
        int height = Math.max(1, component.getHeight());

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clipRect(x, y, width, height);
            if (component instanceof Label label) {
                paintLabel(label, x, y, width, height, g);
            } else if (component instanceof ListBox listBox) {
                paintListBox(listBox, x, y, width, height, g);
            } else if (component instanceof TextBox textBox) {
                paintTextBox(textBox, x, y, width, height, g);
            } else if (component instanceof Button button) {
                paintButton(button.hostLabel(), button.hostEnabled(), component, x, y, width, height, g);
            } else if (component instanceof AnchorButton anchorButton) {
                paintButton(anchorButton.hostText(), anchorButton.hostEnabled(), component,
                        x, y, width, height, g);
            } else {
                paintGeneric(component, x, y, width, height, g);
            }
        } finally {
            g.dispose();
        }
    }

    private static void paintLabel(Label label, int x, int y, int width, int height, Graphics2D g) {
        fillBackground(label, x, y, width, height, g);
        Font font = label.font();
        int lineHeight = Math.max(1, font.getHeight() + 1);
        List<String> lines = wrap(font, label.hostText(), Math.max(1, width - 4));
        int baseline = y + font.getAscent() + 1;
        for (String line : lines) {
            int textWidth = font.stringWidth(line);
            int textX = switch (label.hostAlignment()) {
                case Label.RIGHT -> x + width - textWidth - 2;
                case Label.CENTER -> x + Math.max(2, (width - textWidth) / 2);
                default -> x + 2;
            };
            if (baseline > y + height) {
                break;
            }
            font.drawHostString(g, line, textX, baseline, label.foregroundColor());
            baseline += lineHeight;
        }
    }

    private static void paintListBox(ListBox listBox, int x, int y, int width, int height, Graphics2D g) {
        fillBackground(listBox, x, y, width, height, g);
        g.setColor(new Color(COLOR_LIST_BORDER, true));
        g.drawRect(x, y, width - 1, height - 1);

        Font font = listBox.font();
        int itemHeight = Math.max(1, font.getHeight() + 3);
        int rowCount = Math.max(1, (height - 2) / itemHeight);
        int itemCount = listBox.hostItemCount();
        int cursor = listBox.hostCursor();
        for (int row = 0; row < rowCount; row++) {
            int index = row;
            if (index >= itemCount) {
                break;
            }
            int rowY = y + 1 + row * itemHeight;
            if (listBox.focused() && index == cursor) {
                g.setColor(new Color(COLOR_LIST_CURSOR, true));
                g.fillRect(x + 1, rowY, width - 2, itemHeight);
            }
            int markerWidth = markerWidth(listBox.hostType());
            if (markerWidth > 0) {
                paintSelectionMarker(listBox.hostType(), listBox.hostSelected(index),
                        x + 4, rowY + Math.max(1, (itemHeight - 10) / 2), g);
            }
            String item = listBox.hostItem(index);
            int textX = x + markerWidth;
            int baseline = rowY + font.getAscent() + 1;
            int textColor = listBox.hostEnabled() ? listBox.foregroundColor() : COLOR_DISABLED_TEXT;
            font.drawHostString(g, item, textX, baseline, textColor);
        }

        if (listBox.focused()) {
            g.setColor(new Color(COLOR_FOCUS, true));
            g.drawRect(x, y, width - 1, height - 1);
            g.drawRect(x + 1, y + 1, width - 3, height - 3);
        }
    }

    private static void paintSelectionMarker(int type, boolean selected, int x, int y, Graphics2D g) {
        g.setColor(new Color(COLOR_LIST_BORDER, true));
        if (type == ListBox.RADIO_BUTTON) {
            g.drawArc(x, y, 10, 10, 0, 360);
            if (selected) {
                g.setColor(new Color(COLOR_SELECTED, true));
                g.fillArc(x + 3, y + 3, 5, 5, 0, 360);
            }
            return;
        }
        g.drawRect(x, y, 10, 10);
        if (selected) {
            g.setColor(new Color(COLOR_SELECTED, true));
            g.drawLine(x + 2, y + 5, x + 4, y + 8);
            g.drawLine(x + 4, y + 8, x + 8, y + 2);
        }
    }

    private static int markerWidth(int type) {
        return switch (type) {
            case ListBox.CHECK_BOX, ListBox.RADIO_BUTTON -> 20;
            case ListBox.NUMBERED_LIST -> 22;
            default -> 6;
        };
    }

    private static void paintTextBox(TextBox textBox, int x, int y, int width, int height, Graphics2D g) {
        fillBackground(textBox, x, y, width, height, g);
        g.setColor(new Color(COLOR_LIST_BORDER, true));
        g.drawRect(x, y, width - 1, height - 1);
        Font font = textBox.font();
        int baseline = y + font.getAscent() + 2;
        int textColor = textBox.hostEnabled() ? textBox.foregroundColor() : COLOR_DISABLED_TEXT;
        font.drawHostString(g, textBox.hostDisplayText(), x + 4, baseline, textColor);
        if (textBox.focused()) {
            g.setColor(new Color(COLOR_FOCUS, true));
            g.drawRect(x, y, width - 1, height - 1);
        }
    }

    private static void paintButton(String label, boolean enabled, Component component,
                                    int x, int y, int width, int height, Graphics2D g) {
        fillBackground(component, x, y, width, height, g);
        if (component.backgroundColor() == Graphics.getColorOfName(Graphics.WHITE)) {
            g.setColor(new Color(COLOR_BUTTON_FACE, true));
            g.fillRect(x + 1, y + 1, width - 2, height - 2);
        }
        g.setColor(new Color(COLOR_BUTTON_BORDER, true));
        g.drawRect(x, y, width - 1, height - 1);
        Font font = component.font();
        int textWidth = font.stringWidth(label);
        int textX = x + Math.max(3, (width - textWidth) / 2);
        int baseline = y + Math.max(font.getAscent() + 2, (height + font.getAscent()) / 2);
        int textColor = enabled ? component.foregroundColor() : COLOR_DISABLED_TEXT;
        font.drawHostString(g, label, textX, baseline, textColor);
        if (component.focused()) {
            g.setColor(new Color(COLOR_FOCUS, true));
            g.drawRect(x, y, width - 1, height - 1);
            g.drawRect(x + 1, y + 1, width - 3, height - 3);
        }
    }

    private static void paintGeneric(Component component, int x, int y, int width, int height, Graphics2D g) {
        fillBackground(component, x, y, width, height, g);
        g.setColor(new Color(COLOR_LIST_BORDER, true));
        g.drawRect(x, y, width - 1, height - 1);
        if (component.focused()) {
            g.setColor(new Color(COLOR_FOCUS, true));
            g.drawRect(x, y, width - 1, height - 1);
        }
    }

    private static void fillBackground(Component component, int x, int y, int width, int height, Graphics2D g) {
        g.setColor(color(component.backgroundColor(), 0xFFFFFFFF));
        g.fillRect(x, y, width, height);
    }

    private static void paintScrollBar(Layout layout, int width, int height, Graphics2D g) {
        if (layout.contentHeight <= layout.viewportHeight || layout.viewportHeight <= 0) {
            return;
        }
        int trackX = width - 4;
        int trackY = layout.titleHeight;
        int trackHeight = Math.max(1, layout.viewportHeight - 1);
        g.setColor(new Color(COLOR_SCROLL_TRACK, true));
        g.fillRect(trackX, trackY, 3, trackHeight);
        int thumbHeight = Math.max(8, trackHeight * layout.viewportHeight / layout.contentHeight);
        int maxScroll = Math.max(1, layout.contentHeight - layout.viewportHeight);
        int thumbY = trackY + (trackHeight - thumbHeight) * layout.scrollOffset / maxScroll;
        g.setColor(new Color(COLOR_SCROLL_THUMB, true));
        g.fillRect(trackX, thumbY, 3, thumbHeight);
    }

    private static List<String> wrap(Font font, String raw, int maxWidth) {
        String text = raw == null ? "" : Font.metricString(raw);
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            lines.add("");
            return lines;
        }
        int paragraphStart = 0;
        while (paragraphStart <= text.length()) {
            int newline = text.indexOf('\n', paragraphStart);
            String paragraph = newline < 0 ? text.substring(paragraphStart)
                    : text.substring(paragraphStart, newline);
            if (paragraph.endsWith("\r")) {
                paragraph = paragraph.substring(0, paragraph.length() - 1);
            }
            wrapParagraph(font, paragraph, maxWidth, lines);
            if (newline < 0) {
                break;
            }
            paragraphStart = newline + 1;
            if (paragraphStart == text.length()) {
                lines.add("");
                break;
            }
        }
        return lines;
    }

    private static void wrapParagraph(Font font, String paragraph, int maxWidth, List<String> lines) {
        if (paragraph.isEmpty()) {
            lines.add("");
            return;
        }
        int start = 0;
        while (start < paragraph.length()) {
            int end = start;
            int lastBreak = -1;
            while (end < paragraph.length()) {
                int codePoint = paragraph.codePointAt(end);
                int next = end + Character.charCount(codePoint);
                if (next > start && font.stringWidth(paragraph.substring(start, next)) > maxWidth) {
                    break;
                }
                if (Character.isWhitespace(codePoint)) {
                    lastBreak = next;
                }
                end = next;
            }
            if (end >= paragraph.length()) {
                lines.add(paragraph.substring(start));
                return;
            }
            if (lastBreak > start) {
                end = lastBreak;
            } else if (end == start) {
                end = start + Character.charCount(paragraph.codePointAt(start));
            }
            lines.add(stripTrailingWhitespace(paragraph.substring(start, end)));
            start = end;
            while (start < paragraph.length() && paragraph.charAt(start) == ' ') {
                start++;
            }
        }
    }

    private static String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static Color color(int argb, int fallback) {
        return new Color(argb == 0 ? fallback : argb, true);
    }

    private record Layout(int titleHeight, int viewportHeight, int contentHeight, int scrollOffset) {
    }

    private static final class Flow {
        private final int contentRight;
        private int x;
        private int y;
        private int lineBottom;
        private int contentBottom;
        private boolean lineHasContent;

        private Flow(int x, int y, int contentRight) {
            this.x = x;
            this.y = y;
            this.contentRight = contentRight;
            this.lineBottom = y;
            this.contentBottom = y;
        }

        private void breakLine(int advance) {
            if (lineHasContent) {
                y = lineBottom;
                x = PADDING_X;
                lineHasContent = false;
            }
            if (advance > 0) {
                y += advance;
            }
            lineBottom = y;
        }

        private boolean hasLineContent() {
            return lineHasContent;
        }

        private void newLineIfNeeded() {
            if (lineHasContent) {
                breakLine(0);
            }
        }

        private void place(Component component, int width, int height) {
            if (lineHasContent && x + width > contentRight) {
                breakLine(0);
            }
            component.setLocation(x, y);
            component.setSize(width, height);
            x += width + COMPONENT_GAP;
            lineBottom = Math.max(lineBottom, y + height);
            contentBottom = Math.max(contentBottom, y + height);
            lineHasContent = true;
        }

        private void placeBlock(Component component, int width, int height) {
            newLineIfNeeded();
            component.setLocation(PADDING_X, y);
            component.setSize(width, height);
            lineBottom = y + height;
            contentBottom = Math.max(contentBottom, lineBottom);
            lineHasContent = true;
            x = PADDING_X;
        }
    }
}
