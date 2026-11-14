package com.docomostar.ui.graphics3d;

import com.docomostar.ui.util3d.Transform;

/**
 * Represents a drawable 3D group.
 */
public class Group extends DrawableObject3D {
    /**
     * Creates an empty group.
     */
    public Group() {
        super(new com.nttdocomo.ui.graphics3d.Group());
    }

    Group(com.nttdocomo.ui.graphics3d.Group delegate) {
        super(delegate);
    }

    private com.nttdocomo.ui.graphics3d.Group group() {
        return (com.nttdocomo.ui.graphics3d.Group) delegate();
    }

    /**
     * Disposes this object and releases its resources.
     */
    @Override
    public void dispose() {
        group().dispose();
    }

    /**
     * Returns the number of elements held by this group.
     *
     * @return the number of elements
     */
    public int getNumElements() {
        return group().getNumElements();
    }

    /**
     * Returns the element at the specified index.
     *
     * @param index the element index
     * @return the element
     */
    public Object3D getElement(int index) {
        return Object3D.wrap(group().getElement(index));
    }

    /**
     * Removes the element at the specified index.
     *
     * @param index the element index
     */
    public void removeElement(int index) {
        group().removeElement(index);
    }

    /**
     * Adds an element to the group.
     *
     * @param object the object to add
     */
    public void addElement(Object3D object) {
        group().addElement(object.delegate());
    }

    /**
     * Sets the group transform.
     *
     * @param transform the transform to set
     */
    public void setTransform(Transform transform) {
        group().setTransform(transform);
    }

    /**
     * Copies the group transform into the supplied object.
     *
     * @param transform the destination transform
     */
    public void getTransform(Transform transform) {
        group().getTransform(transform);
    }

    /**
     * Sets time.
     *
     * @param time the current time
     */
    @Override
    public void setTime(int time) {
        group().setTime(time);
    }
}
