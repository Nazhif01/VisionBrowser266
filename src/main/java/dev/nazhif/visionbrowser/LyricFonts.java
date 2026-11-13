package dev.nazhif.visionbrowser;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The fonts offered for the 3D lyrics: four built-in logical fonts plus any popular families installed on this computer. */
final class LyricFonts {
    private static final String[] PREFERRED = {
            "Segoe UI", "Arial", "Verdana", "Tahoma", "Trebuchet MS", "Georgia", "Times New Roman", "Courier New", "Consolas",
            "Impact", "Comic Sans MS", "Segoe Script", "Yu Gothic", "Microsoft YaHei", "Meiryo", "Helvetica", "Helvetica Neue",
            "Noto Sans", "Noto Serif", "DejaVu Sans", "DejaVu Serif", "Roboto", "Open Sans", "Lato", "Montserrat", "Poppins",
            "Ubuntu", "Fira Sans", "Palatino Linotype", "Candara", "Calibri", "Cambria", "Bahnschrift", "Lucida Sans", "Papyrus"};
    private static List<String> names;

    private LyricFonts() { }

    static synchronized List<String> names() {
        if (names == null) {
            List<String> list = new ArrayList<>(List.of("Dialog", "SansSerif", "Serif", "Monospaced"));
            try {
                Set<String> installed = new HashSet<>(Arrays.asList(
                        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
                for (String p : PREFERRED) if (installed.contains(p)) list.add(p);
            } catch (Throwable ignored) {
                // headless or font-less system: the four built-ins are still available
            }
            names = list;
        }
        return names;
    }

    /** The chosen family, or the composite "Dialog" font when the chosen one cannot draw every character of this line (e.g. Japanese). */
    static Font make(String family, int size, String text) {
        Font f = new Font(family, Font.BOLD, size);
        return f.canDisplayUpTo(text) == -1 ? f : new Font("Dialog", Font.BOLD, size);
    }
}