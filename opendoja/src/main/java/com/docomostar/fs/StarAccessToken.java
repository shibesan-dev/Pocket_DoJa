package com.docomostar.fs;

/**
 * Defines a Star storage access token.
 */
public class StarAccessToken extends AccessToken {
    StarAccessToken(com.nttdocomo.fs.AccessToken delegate) {
        super(delegate);
    }
}
