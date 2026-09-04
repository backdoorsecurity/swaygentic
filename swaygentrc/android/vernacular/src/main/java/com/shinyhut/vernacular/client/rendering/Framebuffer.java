package com.shinyhut.vernacular.client.rendering;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;

import com.shinyhut.vernacular.client.VncSession;
import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.client.rendering.renderers.CopyRectRenderer;
import com.shinyhut.vernacular.client.rendering.renderers.CursorRenderer;
import com.shinyhut.vernacular.client.rendering.renderers.HextileRenderer;
import com.shinyhut.vernacular.client.rendering.renderers.PixelDecoder;
import com.shinyhut.vernacular.client.rendering.renderers.RRERenderer;
import com.shinyhut.vernacular.client.rendering.renderers.RawRenderer;
import com.shinyhut.vernacular.client.rendering.renderers.Renderer;
import com.shinyhut.vernacular.client.rendering.renderers.ZLibRenderer;
import com.shinyhut.vernacular.protocol.messages.ColorMapEntry;
import com.shinyhut.vernacular.protocol.messages.Encoding;
import com.shinyhut.vernacular.protocol.messages.FramebufferUpdate;
import com.shinyhut.vernacular.protocol.messages.Rectangle;
import com.shinyhut.vernacular.protocol.messages.SetColorMapEntries;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.shinyhut.vernacular.protocol.messages.Encoding.COPYRECT;
import static com.shinyhut.vernacular.protocol.messages.Encoding.CURSOR;
import static com.shinyhut.vernacular.protocol.messages.Encoding.DESKTOP_SIZE;
import static com.shinyhut.vernacular.protocol.messages.Encoding.HEXTILE;
import static com.shinyhut.vernacular.protocol.messages.Encoding.RAW;
import static com.shinyhut.vernacular.protocol.messages.Encoding.RRE;
import static com.shinyhut.vernacular.protocol.messages.Encoding.ZLIB;

public class Framebuffer {

    private final VncSession session;
    private final Map<Long, ColorMapEntry> colorMap = new ConcurrentHashMap<>();
    private final Map<Encoding, Renderer> renderers = new ConcurrentHashMap<>();
    private final CursorRenderer cursorRenderer;

    private Bitmap frame;

    public Framebuffer(VncSession session) {
        PixelDecoder pixelDecoder = new PixelDecoder(colorMap);
        RawRenderer rawRenderer = new RawRenderer(pixelDecoder, session.getPixelFormat());
        renderers.put(RAW, rawRenderer);
        renderers.put(COPYRECT, new CopyRectRenderer());
        renderers.put(RRE, new RRERenderer(pixelDecoder, session.getPixelFormat()));
        renderers.put(HEXTILE, new HextileRenderer(rawRenderer, pixelDecoder, session.getPixelFormat()));
        renderers.put(ZLIB, new ZLibRenderer(rawRenderer));
        cursorRenderer = new CursorRenderer(rawRenderer);

        frame = Bitmap.createBitmap(
                session.getFramebufferWidth(),
                session.getFramebufferHeight(),
                Bitmap.Config.ARGB_8888);
        this.session = session;
    }

    public void processUpdate(FramebufferUpdate update) throws VncException {
        InputStream in = session.getInputStream();
        try {
            for (int i = 0; i < update.getNumberOfRectangles(); i++) {
                Rectangle rectangle = Rectangle.decode(in);
                if (rectangle.getEncoding() == DESKTOP_SIZE) {
                    resizeFramebuffer(rectangle);
                } else if (rectangle.getEncoding() == CURSOR) {
                    updateCursor(rectangle, in);
                } else {
                    Renderer renderer = renderers.get(rectangle.getEncoding());
                    if (renderer == null) {
                        throw new UnexpectedVncException(
                                new IllegalStateException("Unsupported encoding: " + rectangle.getEncoding()));
                    }
                    renderer.render(in, frame, rectangle);
                }
            }
            paint();
            session.framebufferUpdated();
        } catch (IOException e) {
            throw new UnexpectedVncException(e);
        }
    }

    private void paint() {
        Consumer<Bitmap> listener = session.getConfig().getScreenUpdateListener();
        if (listener != null) {
            listener.accept(frame.copy(Bitmap.Config.ARGB_8888, false));
        }
    }

    public void updateColorMap(SetColorMapEntries update) {
        for (int i = 0; i < update.getColors().size(); i++) {
            colorMap.put((long) i + update.getFirstColor(), update.getColors().get(i));
        }
    }

    private void resizeFramebuffer(Rectangle newSize) {
        int width = newSize.getWidth();
        int height = newSize.getHeight();
        session.setFramebufferWidth(width);
        session.setFramebufferHeight(height);
        Bitmap resized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(resized).drawBitmap(frame, 0, 0, null);
        frame.recycle();
        frame = resized;
    }

    private void updateCursor(Rectangle cursor, InputStream in) throws VncException {
        if (cursor.getWidth() > 0 && cursor.getHeight() > 0) {
            Bitmap cursorImage = Bitmap.createBitmap(
                    cursor.getWidth(), cursor.getHeight(), Bitmap.Config.ARGB_8888);
            cursorRenderer.render(in, cursorImage, cursor);
            BiConsumer<Bitmap, Point> listener = session.getConfig().getMousePointerUpdateListener();
            if (listener != null) {
                listener.accept(cursorImage, new Point(cursor.getX(), cursor.getY()));
            }
        }
    }
}
