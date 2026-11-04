package com.docomostar.ui;

/**
 * Star compatibility shim delegating to com.nttdocomo.ui.AudioPresenter.
 */
public class AudioPresenter extends com.nttdocomo.ui.AudioPresenter {
    public AudioPresenter() {
        super();
    }

    protected AudioPresenter(int explicitPort) {
        super(explicitPort);
    }

    public static AudioPresenter getAudioPresenter() {
        return new AudioPresenter();
    }

    public static AudioPresenter getAudioPresenter(int port) {
        return new AudioPresenter(port);
    }

    public static void setSession(int session) {
        com.nttdocomo.ui.AudioPresenter.setSession(session);
    }

    public static int getSession() {
        return com.nttdocomo.ui.AudioPresenter.getSession();
    }

    public static void releaseAll() {
        com.nttdocomo.ui.AudioPresenter.releaseAll();
    }

    public static void releaseAllSessions() {
        com.nttdocomo.ui.AudioPresenter.releaseAllSessions();
    }

    public void setMediaListener(com.docomostar.ui.MediaListener listener) {
        super.setMediaListener(listener);
    }

    public void setSound(com.docomostar.media.MediaSound sound) {
        super.setSound(sound);
    }

    public com.docomostar.media.MediaResource getMediaResource() {
        com.nttdocomo.ui.MediaResource resource = super.getMediaResource();
        if (resource == null) {
            return null;
        }
        if (resource instanceof com.docomostar.media.MediaResource docomostarResource) {
            return docomostarResource;
        }
        return new MediaResourceAdapter(resource);
    }

    private static final class MediaResourceAdapter implements com.docomostar.media.MediaResource {
        private final com.nttdocomo.ui.MediaResource delegate;

        MediaResourceAdapter(com.nttdocomo.ui.MediaResource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void use() throws com.nttdocomo.io.ConnectionException {
            delegate.use();
        }

        @Override
        public void use(com.nttdocomo.ui.MediaResource other, boolean exclusive) throws com.nttdocomo.io.ConnectionException {
            delegate.use(other, exclusive);
        }

        @Override
        public void unuse() {
            delegate.unuse();
        }

        @Override
        public void dispose() {
            delegate.dispose();
        }

        @Override
        public String getProperty(String key) {
            return delegate.getProperty(key);
        }

        @Override
        public void setProperty(String key, String value) {
            delegate.setProperty(key, value);
        }

        @Override
        public boolean isRedistributable() {
            return delegate.isRedistributable();
        }

        @Override
        public boolean setRedistributable(boolean redistributable) {
            return delegate.setRedistributable(redistributable);
        }
    }
}
