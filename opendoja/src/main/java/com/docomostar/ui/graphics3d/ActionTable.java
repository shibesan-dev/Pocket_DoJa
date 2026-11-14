package com.docomostar.ui.graphics3d;

/**
 * Defines the action table used to animate a figure.
 */
public class ActionTable extends Object3D {
    ActionTable(com.nttdocomo.ui.graphics3d.ActionTable delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.ActionTable actionTable() {
        return (com.nttdocomo.ui.graphics3d.ActionTable) delegate();
    }

    /**
     * Gets the number of actions stored in this table.
     *
     * @return the action count
     */
    public int getNumActions() {
        return actionTable().getNumActions();
    }

    /**
     * Gets the maximum frame number for an action.
     *
     * @param action the action index
     * @return the maximum frame number
     */
    public int getMaxFrame(int action) {
        return actionTable().getMaxFrame(action);
    }
}
