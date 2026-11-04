package com.docomostar.io;

/**
 * Star compatibility shim. Some Star i-appli titles use
 * {@code com.docomostar.io.SocketConnection} for raw socket/serial links;
 * this delegates to the {@code com.nttdocomo.io.SPPConnection} implementation
 * so their connection classes work unchanged. Without it, those titles fail
 * to launch with
 * {@code NoClassDefFoundError: Failed resolution of: Lcom/docomostar/io/SocketConnection}.
 */
public interface SocketConnection extends com.nttdocomo.io.SPPConnection {
}