package com.docomostar.ui;

public interface MediaListener extends com.nttdocomo.ui.MediaListener {
    @Override
    default void mediaAction(com.nttdocomo.ui.MediaPresenter presenter, int type, int param) {
        if (presenter instanceof com.docomostar.ui.MediaPresenter docomostarPresenter) {
            mediaAction(docomostarPresenter, type, param);
        }
    }

    void mediaAction(com.docomostar.ui.MediaPresenter presenter, int type, int param);
}
