package com.docomostar.ui.graphics3d;

/**
 * Defines the figure object that holds model shape data.
 */
public class Figure extends DrawableObject3D {
    /**
     * Creates a figure wrapper around decoded model data.
     *
     * @param delegate the DoJa figure object
     */
    Figure(com.nttdocomo.ui.graphics3d.Figure delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Figure figure() {
        return (com.nttdocomo.ui.graphics3d.Figure) delegate();
    }

    /**
     * Sets the texture mapped onto the model data.
     *
     * @param texture the texture object
     */
    public void setTexture(Texture texture) {
        figure().setTexture(texture == null ? null : (com.nttdocomo.ui.graphics3d.Texture) texture.delegate());
    }

    /**
     * Sets the textures mapped onto the model data.
     *
     * @param textures the texture array
     */
    public void setTextures(Texture[] textures) {
        if (textures == null) {
            figure().setTextures(null);
            return;
        }
        com.nttdocomo.ui.graphics3d.Texture[] converted =
                new com.nttdocomo.ui.graphics3d.Texture[textures.length];
        for (int i = 0; i < textures.length; i++) {
            converted[i] = textures[i] == null
                    ? null
                    : (com.nttdocomo.ui.graphics3d.Texture) textures[i].delegate();
        }
        figure().setTextures(converted);
    }

    /**
     * Associates an action table and action index with this figure.
     *
     * @param actionTable the action table
     * @param action the action-data index
     */
    public void setAction(ActionTable actionTable, int action) {
        figure().setAction(actionTable == null
                ? null
                : (com.nttdocomo.ui.graphics3d.ActionTable) actionTable.delegate(), action);
    }

    /**
     * Gets the action table currently associated with this figure.
     *
     * @return the current action table, or {@code null} if none is set
     */
    public ActionTable getActionTable() {
        com.nttdocomo.ui.graphics3d.ActionTable table = figure().getActionTable();
        return table == null ? null : new ActionTable(table);
    }

    /**
     * Gets the number of appearance patterns in the model data.
     *
     * @return the number of patterns
     */
    public int getNumPatterns() {
        return figure().getNumPatterns();
    }

    /**
     * Sets the current appearance pattern.
     *
     * @param pattern the pattern index
     */
    public void setPattern(int pattern) {
        figure().setPattern(pattern);
    }

    /**
     * Sets the current time and applies the action state for that time.
     *
     * @param time the current time
     */
    @Override
    public void setTime(int time) {
        figure().setTime(time);
    }
}
