package com.shinyhut.vernacular.client.rendering.renderers;

import android.graphics.Bitmap;

import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.protocol.messages.Rectangle;

import java.io.InputStream;

public interface Renderer {
    void render(InputStream in, Bitmap destination, Rectangle rectangle) throws VncException;
}
