package com.shinyhut.vernacular.client.rendering.renderers;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.protocol.messages.Rectangle;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CopyRectRenderer implements Renderer {

    @Override
    public void render(InputStream in, Bitmap destination, Rectangle rectangle) throws VncException {
        try {
            DataInput dataInput = new DataInputStream(in);
            int srcX = dataInput.readUnsignedShort();
            int srcY = dataInput.readUnsignedShort();
            int w = rectangle.getWidth();
            int h = rectangle.getHeight();
            Bitmap src = Bitmap.createBitmap(destination, srcX, srcY, w, h);
            new Canvas(destination).drawBitmap(src, rectangle.getX(), rectangle.getY(), null);
            src.recycle();
        } catch (IOException e) {
            throw new UnexpectedVncException(e);
        }
    }
}
