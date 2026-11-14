package dev.nazhif.visionbrowser;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Reads the left mouse button directly, because the chat screen owns the input events.
 * Primary source: SDL (SDLMouse.SDL_GetMouseState(FloatBuffer, FloatBuffer), the call Minecraft 26.3's SDL backend uses).
 * Secondary source: MouseHandler.isLeftPressed() if this version has it. Either one reporting "down" counts.
 */
final class MouseProbe {
    private static FloatBuffer bx, by;
    private static boolean sdlBroken;
    private static Method reflected;
    private static boolean reflectedResolved;

    private MouseProbe() { }

    static boolean leftDown(Minecraft mc) {
        boolean down = false;
        if (!sdlBroken) {
            try {
                if (bx == null) {
                    bx = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    by = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                }
                int mask = SDLMouse.SDL_GetMouseState(bx, by);
                down = (mask & 1) != 0; // SDL_BUTTON_LMASK
            } catch (Throwable error) {
                sdlBroken = true;
            }
        }
        if (!down) {
            try {
                if (!reflectedResolved) {
                    reflectedResolved = true;
                    try {
                        Method m = mc.mouseHandler.getClass().getMethod("isLeftPressed");
                        if (m.getReturnType() == boolean.class) reflected = m;
                    } catch (Throwable ignored) { }
                }
                if (reflected != null) down = (Boolean) reflected.invoke(mc.mouseHandler);
            } catch (Throwable ignored) { }
        }
        return down;
    }
}