package com.nttdocomo.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines a display surface for the high-level API.
 * A panel is the parent object used to place components.
 * The title is displayed at the upper end of the panel and does not scroll
 * together with the component area.
 */
public class Panel extends Frame {
    private static final FocusManager SYSTEM_FOCUS_MANAGER = new SystemFocusManager();

    private final List<Component> components = new ArrayList<>();
    private final List<FlowEntry> flow = new ArrayList<>();
    private FocusManager focusManager = SYSTEM_FOCUS_MANAGER;
    private ComponentListener componentListener;
    private Component focusedComponent;
    private KeyListener keyListener;
    private LayoutManager layoutManager;
    private SoftKeyListener softKeyListener;
    private String title;
    private int scrollOffset;
    private int contentHeight;
    private int viewportHeight;

    /**
     * Creates an empty panel.
     */
    public Panel() {
    }

    /**
     * Adds a component to the panel.
     *
     * @param component the component to add
     * @throws NullPointerException if {@code component} is {@code null}
     * @throws UIException if the component has already been added to a panel
     */
    public void add(Component component) {
        if (component == null) {
            throw new NullPointerException("component");
        }
        if (component.ownerPanel() != null) {
            throw new UIException(UIException.ILLEGAL_STATE,
                    "Component is already attached to a panel");
        }
        int breaks = 0;
        if (layoutManager instanceof HTMLLayout htmlLayout) {
            breaks = htmlLayout.consumePendingBreaks();
        }
        components.add(component);
        flow.add(new FlowEntry(component, breaks));
        component.attachToPanel(this);
    }

    /**
     * Gets the focus-management object that controls focus movement among
     * components on this panel.
     *
     * @return the focus-management object
     */
    public FocusManager getFocusManager() {
        return focusManager;
    }

    /**
     * Sets the focus-management object that controls focus movement among
     * components on this panel.
     * By default, a terminal-dependent system focus manager is set.
     *
     * @param focusManager the focus-management object
     * @throws NullPointerException if {@code focusManager} is {@code null}
     * @throws IllegalArgumentException if the specified object is not a
     *         system-provided focus manager
     */
    public void setFocusManager(FocusManager focusManager) {
        if (focusManager == null) {
            throw new NullPointerException("focusManager");
        }
        if (!(focusManager instanceof SystemFocusManager)) {
            throw new IllegalArgumentException("Only system-provided focus managers are accepted");
        }
        this.focusManager = focusManager;
    }

    /**
     * Sets the background color.
     * The background color changes immediately when this method is called.
     *
     * @param color the color value that represents the background color
     */
    @Override
    public void setBackground(int color) {
        super.setBackground(color);
    }

    /**
     * Registers a component listener.
     * Only one listener can be registered with a panel.
     * Passing {@code null} unregisters the listener.
     *
     * @param componentListener the listener object to register
     */
    public void setComponentListener(ComponentListener componentListener) {
        this.componentListener = componentListener;
    }

    /**
     * Registers a key listener.
     * Only one listener can be registered with a panel.
     * Passing {@code null} unregisters the listener.
     *
     * @param keyListener the listener object to register
     */
    public void setKeyListener(KeyListener keyListener) {
        this.keyListener = keyListener;
    }

    /**
     * Sets the layout-management object that controls component placement on
     * the panel.
     * Passing {@code null} disables layout management.
     *
     * @param layoutManager the layout-management object
     */
    public void setLayoutManager(LayoutManager layoutManager) {
        this.layoutManager = layoutManager;
    }

    /**
     * Sets whether the soft labels are visible.
     *
     * @param visible {@code true} to show the soft labels, or {@code false} to
     *                hide them
     */
    @Override
    public void setSoftLabelVisible(boolean visible) {
        super.setSoftLabelVisible(visible);
    }

    /**
     * Registers a soft-key listener.
     * Only one listener can be registered with a panel.
     * Passing {@code null} unregisters the listener.
     *
     * @param softKeyListener the listener object to register
     */
    public void setSoftKeyListener(SoftKeyListener softKeyListener) {
        this.softKeyListener = softKeyListener;
    }
    public void removeKeyListener() {
        this.keyListener = null;
    }

    /**
     * Unregisters the soft key listener.
     */
    public void removeSoftKeyListener() {
        this.softKeyListener = null;
    }

    /**
     * Performs process Soft Key Event.
     */
    @Override
    public void processSoftKeyEvent(int type, int key) {
        if (softKeyListener == null) {
            return;
        }
        if (type == Display.KEY_PRESSED_EVENT) {
            softKeyListener.softKeyPressed(key);
        } else if (type == Display.KEY_RELEASED_EVENT) {
            softKeyListener.softKeyReleased(key);
        }
    }

    /**
     * Sets the frame title string.
     *
     * @param title the title string
     */
    public void setTitle(String title) {
        this.title = title;
    }

    final void requestFocus(Component component) {
        if (component == null || !component.acceptsFocusOn(this)) {
            return;
        }
        if (focusedComponent == component) {
            return;
        }
        if (focusedComponent != null) {
            focusedComponent.setFocused(false);
        }
        focusedComponent = component;
        component.setFocused(true);
    }

    final void componentBecameUnavailable(Component component) {
        if (focusedComponent == component) {
            focusedComponent.setFocused(false);
            focusedComponent = null;
        }
    }

    final void fireComponentAction(Component component, int type, int param) {
        if (componentListener != null) {
            componentListener.componentAction(component, type, param);
        }
    }

    String hostTitle() {
        return title == null ? "" : title;
    }

    List<Component> hostComponents() {
        return Collections.unmodifiableList(components);
    }

    List<FlowEntry> hostFlow() {
        return Collections.unmodifiableList(flow);
    }

    LayoutManager hostLayoutManager() {
        return layoutManager;
    }

    Component hostFocusedComponent() {
        return focusedComponent;
    }

    int hostScrollOffset() {
        return scrollOffset;
    }

    void hostSetScrollOffset(int scrollOffset) {
        this.scrollOffset = scrollOffset;
    }

    int hostContentHeight() {
        return contentHeight;
    }

    void hostSetContentHeight(int contentHeight) {
        this.contentHeight = contentHeight;
    }

    int hostViewportHeight() {
        return viewportHeight;
    }

    void hostSetViewportHeight(int viewportHeight) {
        this.viewportHeight = viewportHeight;
    }

    /**
     * Host entry point for keypad events while this panel is the current frame.
     * The regular DoJa API does not expose panel key handling directly; the
     * handset runtime is responsible for moving focus and activating
     * components.
     */
    public void hostProcessKeyEvent(int type, int key) {
        PanelRenderer.layoutForInput(this);
        hostEnsureInitialFocus();
        if (type == Display.KEY_PRESSED_EVENT) {
            // Bring the initial focus into view before acting on the press so
            // an off-screen first component is visible when it is used.
            ensureFocusVisible();
            if (keyListener != null) {
                keyListener.keyPressed(this, key);
            }
            switch (key) {
                case Display.KEY_UP -> moveFocus(0, -1);
                case Display.KEY_DOWN -> moveFocus(0, 1);
                case Display.KEY_LEFT -> moveFocus(-1, 0);
                case Display.KEY_RIGHT -> moveFocus(1, 0);
                case Display.KEY_SELECT -> activateFocusedComponent();
                default -> {
                }
            }
        } else if (type == Display.KEY_RELEASED_EVENT) {
            if (keyListener != null) {
                keyListener.keyReleased(this, key);
            }
        }
    }

    void hostEnsureInitialFocus() {
        if (focusedComponent != null && focusedComponent.ownerPanel() == this) {
            return;
        }
        for (Component component : components) {
            if (component.acceptsFocusOn(this)) {
                requestFocus(component);
                return;
            }
        }
    }

    private void moveFocus(int dx, int dy) {
        if (focusedComponent == null) {
            hostEnsureInitialFocus();
            return;
        }
        if (dy != 0) {
            // A list box with more than one item consumes vertical presses
            // while its internal cursor can still move.
            if (focusedComponent instanceof ListBox listBox
                    && listBox.hostItemCount() > 1 && listBox.hostMoveCursor(dy)) {
                ensureFocusVisible();
                return;
            }
            // Up/down follows the order in which interactive components were
            // added. That is the reading order of an HTMLLayout flow and makes
            // every control reachable, including buttons on one line.
            Component next = nextFocusable(focusedComponent, dy > 0 ? 1 : -1);
            if (next != null) {
                requestFocus(next);
                ensureFocusVisible();
            }
            return;
        }
        int focusX = focusedComponent.getX() + focusedComponent.getWidth() / 2;
        int focusY = focusedComponent.getY() + focusedComponent.getHeight() / 2;
        Component best = null;
        long bestScore = Long.MAX_VALUE;
        for (Component candidate : components) {
            if (candidate == focusedComponent || !candidate.acceptsFocusOn(this)) {
                continue;
            }
            int candidateX = candidate.getX() + candidate.getWidth() / 2;
            int candidateY = candidate.getY() + candidate.getHeight() / 2;
            int offsetX = candidateX - focusX;
            int offsetY = candidateY - focusY;
            if (dy > 0 && offsetY <= 0) {
                continue;
            }
            if (dy < 0 && offsetY >= 0) {
                continue;
            }
            if (dx > 0 && offsetX <= 0) {
                continue;
            }
            if (dx < 0 && offsetX >= 0) {
                continue;
            }
            long primary = dy != 0 ? Math.abs((long) offsetY) : Math.abs((long) offsetX);
            long secondary = dy != 0 ? Math.abs((long) offsetX) : Math.abs((long) offsetY);
            long score = primary * 4L + secondary;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) {
            requestFocus(best);
            ensureFocusVisible();
        }
    }

    private Component nextFocusable(Component from, int direction) {
        int index = components.indexOf(from);
        if (index < 0) {
            index = direction > 0 ? -1 : components.size();
        }
        for (int i = index + direction; i >= 0 && i < components.size(); i += direction) {
            Component candidate = components.get(i);
            if (candidate.acceptsFocusOn(this)) {
                return candidate;
            }
        }
        return null;
    }

    private void activateFocusedComponent() {
        if (focusedComponent instanceof Button button) {
            fireComponentAction(button, ComponentListener.BUTTON_PRESSED, 0);
        } else if (focusedComponent instanceof AnchorButton anchorButton) {
            fireComponentAction(anchorButton, ComponentListener.BUTTON_PRESSED, 0);
        } else if (focusedComponent instanceof ListBox listBox) {
            listBox.hostActivate();
        }
    }

    private void ensureFocusVisible() {
        Component component = focusedComponent;
        if (component == null) {
            return;
        }
        int viewport = viewportHeight > 0 ? viewportHeight : Math.max(0, getHeight());
        int top = component.getY();
        int bottom = top + Math.max(1, component.getHeight());
        if (top < scrollOffset) {
            scrollOffset = top;
        } else if (bottom > scrollOffset + viewport) {
            scrollOffset = bottom - viewport;
        }
        int maxScroll = Math.max(0, contentHeight - viewport);
        if (scrollOffset < 0) {
            scrollOffset = 0;
        } else if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
    }

    static final class FlowEntry {
        private final Component component;
        private final int breaks;

        private FlowEntry(Component component, int breaks) {
            this.component = component;
            this.breaks = breaks;
        }

        Component component() {
            return component;
        }

        int breaks() {
            return breaks;
        }
    }

    private static final class SystemFocusManager implements FocusManager {
    }
}
