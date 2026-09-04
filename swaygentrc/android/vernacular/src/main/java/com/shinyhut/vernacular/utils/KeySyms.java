package com.shinyhut.vernacular.utils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * X11 keysyms used by VernacularClient.type(String).
 * Android port: no java.awt.event.KeyEvent mapping (keyboard deferred in swaygentrc MVP).
 */
public class KeySyms {

    public static final int ENTER = 0xff0d;
    public static final int TAB = 0xff09;

    private static final Map<Integer, Integer> KEYCODES = new ConcurrentHashMap<>();
    private static final Map<Character, Integer> CONTROL_CHARACTERS = new ConcurrentHashMap<>();
    private static final Map<Character, Integer> SHIFT_CONTROL_CHARACTERS = new ConcurrentHashMap<>();

    // Android KeyEvent keycodes (subset) → X11 keysyms, for optional future use
    static {
        KEYCODES.put(67, 0xff08); // DEL / BACK_SPACE
        KEYCODES.put(61, 0xff09); // TAB
        KEYCODES.put(66, 0xff0d); // ENTER
        KEYCODES.put(111, 0xff1b); // ESCAPE
        KEYCODES.put(124, 0xff63); // INSERT
        KEYCODES.put(112, 0xffff); // FORWARD_DEL
        KEYCODES.put(122, 0xff50); // MOVE_HOME
        KEYCODES.put(123, 0xff57); // MOVE_END
        KEYCODES.put(92, 0xff55); // PAGE_UP
        KEYCODES.put(93, 0xff56); // PAGE_DOWN
        KEYCODES.put(21, 0xff51); // DPAD_LEFT
        KEYCODES.put(19, 0xff52); // DPAD_UP
        KEYCODES.put(22, 0xff53); // DPAD_RIGHT
        KEYCODES.put(20, 0xff54); // DPAD_DOWN
    }

    static {
        CONTROL_CHARACTERS.put((char) 0x00, 0x0040);
        CONTROL_CHARACTERS.put((char) 0x01, 0x0061);
        CONTROL_CHARACTERS.put((char) 0x02, 0x0062);
        CONTROL_CHARACTERS.put((char) 0x03, 0x0063);
        CONTROL_CHARACTERS.put((char) 0x04, 0x0064);
        CONTROL_CHARACTERS.put((char) 0x05, 0x0065);
        CONTROL_CHARACTERS.put((char) 0x06, 0x0066);
        CONTROL_CHARACTERS.put((char) 0x07, 0x0067);
        CONTROL_CHARACTERS.put((char) 0x08, 0x0068);
        CONTROL_CHARACTERS.put((char) 0x09, 0x0069);
        CONTROL_CHARACTERS.put((char) 0x0a, 0x006a);
        CONTROL_CHARACTERS.put((char) 0x0b, 0x006b);
        CONTROL_CHARACTERS.put((char) 0x0c, 0x006c);
        CONTROL_CHARACTERS.put((char) 0x0d, 0x006d);
        CONTROL_CHARACTERS.put((char) 0x0e, 0x006e);
        CONTROL_CHARACTERS.put((char) 0x0f, 0x006f);
        CONTROL_CHARACTERS.put((char) 0x10, 0x0070);
        CONTROL_CHARACTERS.put((char) 0x11, 0x0071);
        CONTROL_CHARACTERS.put((char) 0x12, 0x0072);
        CONTROL_CHARACTERS.put((char) 0x13, 0x0073);
        CONTROL_CHARACTERS.put((char) 0x14, 0x0074);
        CONTROL_CHARACTERS.put((char) 0x15, 0x0075);
        CONTROL_CHARACTERS.put((char) 0x16, 0x0076);
        CONTROL_CHARACTERS.put((char) 0x17, 0x0077);
        CONTROL_CHARACTERS.put((char) 0x18, 0x0078);
        CONTROL_CHARACTERS.put((char) 0x19, 0x0079);
        CONTROL_CHARACTERS.put((char) 0x1a, 0x007a);
        CONTROL_CHARACTERS.put((char) 0x1b, 0x005b);
        CONTROL_CHARACTERS.put((char) 0x1c, 0x005c);
        CONTROL_CHARACTERS.put((char) 0x1d, 0x005d);
        CONTROL_CHARACTERS.put((char) 0x1e, 0x005e);
        CONTROL_CHARACTERS.put((char) 0x1f, 0x005f);
    }

    static {
        SHIFT_CONTROL_CHARACTERS.put((char) 0x01, 0x0041);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x02, 0x0042);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x03, 0x0043);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x04, 0x0044);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x05, 0x0045);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x06, 0x0046);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x07, 0x0047);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x08, 0x0048);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x09, 0x0049);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0a, 0x004a);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0b, 0x004b);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0c, 0x004c);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0d, 0x004d);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0e, 0x004e);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x0f, 0x004f);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x10, 0x0050);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x11, 0x0051);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x12, 0x0052);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x13, 0x0053);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x14, 0x0054);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x15, 0x0055);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x16, 0x0056);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x17, 0x0057);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x18, 0x0058);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x19, 0x0059);
        SHIFT_CONTROL_CHARACTERS.put((char) 0x1a, 0x005a);
    }

    public static Optional<Integer> forKeyCode(int keyCode) {
        return Optional.ofNullable(KEYCODES.get(keyCode));
    }
}
