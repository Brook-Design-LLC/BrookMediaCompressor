package com.brook.tools.mediacompress;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProgressParser {
    private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern FPS = Pattern.compile("fps=\\s*([\\d.]+)");
    private static final Pattern SPEED = Pattern.compile("speed=\\s*([\\d.]+)x");

    private ProgressParser() {
    }

    public static Double parseTimeSeconds(String line) {
        Matcher m = TIME.matcher(line);
        if (!m.find()) {
            return null;
        }
        return Integer.parseInt(m.group(1)) * 3600.0
                + Integer.parseInt(m.group(2)) * 60.0
                + Double.parseDouble(m.group(3));
    }

    public static String parseFps(String line) {
        Matcher m = FPS.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    public static String parseSpeed(String line) {
        Matcher m = SPEED.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
