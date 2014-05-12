/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.VarcharType;
import com.facebook.presto.type.ColorType;
import com.facebook.presto.type.SqlType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.awt.Color;

import static com.facebook.presto.operator.scalar.StringFunctions.upper;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;

public final class ColorFunctions
{
    private static final String ANSI_RESET = "\u001b[0m";

    private static final Slice RENDERED_TRUE = render(Slices.copiedBuffer("\u2713", Charsets.UTF_8), color(Slices.copiedBuffer("green", Charsets.UTF_8)));
    private static final Slice RENDERED_FALSE = render(Slices.copiedBuffer("\u2717", Charsets.UTF_8), color(Slices.copiedBuffer("red", Charsets.UTF_8)));

    public enum SystemColor
    {
        BLACK(0, "black"),
        RED(1, "red"),
        GREEN(2, "green"),
        YELLOW(3, "yellow"),
        BLUE(4, "blue"),
        MAGENTA(5, "magenta"),
        CYAN(6, "cyan"),
        WHITE(7, "white");

        private final int index;
        private final String name;

        SystemColor(int index, String name)
        {
            this.index = index;
            this.name = name;
        }

        private int getIndex()
        {
            return index;
        }

        public String getName()
        {
            return name;
        }

        public static SystemColor valueOf(int index)
        {
            for (SystemColor color : values()) {
                if (index == color.getIndex()) {
                    return color;
                }
            }

            throw new IllegalArgumentException(String.format("invalid index: %s", index));
        }
    }

    private ColorFunctions() {}

    @ScalarFunction
    @SqlType(ColorType.class)
    public static long color(@SqlType(VarcharType.class) Slice color)
    {
        int rgb = parseRgb(color);

        if (rgb != -1) {
            return rgb;
        }

        // encode system colors (0-15) as negative values, offset by one
        try {
            SystemColor systemColor = SystemColor.valueOf(upper(color).toString(UTF_8));
            int index = systemColor.getIndex();
            return -(index + 1);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(format("Invalid color: '%s'", color.toString(Charsets.UTF_8)), e);
        }
    }

    @ScalarFunction
    @SqlType(ColorType.class)
    public static long rgb(@SqlType(BigintType.class) long red, @SqlType(BigintType.class) long green, @SqlType(BigintType.class) long blue)
    {
        checkArgument(red >= 0 && red <= 255, "red must be between 0 and 255");
        checkArgument(green >= 0 && green <= 255, "green must be between 0 and 255");
        checkArgument(blue >= 0 && blue <= 255, "blue must be between 0 and 255");

        return (red << 16) | (green << 8) | blue;
    }

    /**
     * Interpolate a color between lowColor and highColor based the provided value
     * <p/>
     * The value is truncated to the range [low, high] if it's outside.
     * Color must be a valid rgb value of the form #rgb
     */
    @ScalarFunction
    @SqlType(ColorType.class)
    public static long color(
            @SqlType(DoubleType.class) double value,
            @SqlType(DoubleType.class) double low,
            @SqlType(DoubleType.class) double high,
            @SqlType(ColorType.class) long lowColor,
            @SqlType(ColorType.class) long highColor)
    {
        return color((value - low) * 1.0 / (high - low), lowColor, highColor);
    }

    /**
     * Interpolate a color between lowColor and highColor based on the provided value
     * <p/>
     * The value is truncated to the range [0, 1] if necessary
     * Color must be a valid rgb value of the form #rgb
     */
    @ScalarFunction
    @SqlType(ColorType.class)
    public static long color(@SqlType(DoubleType.class) double fraction, @SqlType(ColorType.class) long lowColor, @SqlType(ColorType.class) long highColor)
    {
        Preconditions.checkArgument(lowColor >= 0, "lowColor not a valid RGB color");
        Preconditions.checkArgument(highColor >= 0, "highColor not a valid RGB color");

        fraction = Math.min(1, fraction);
        fraction = Math.max(0, fraction);

        return interpolate((float) fraction, lowColor, highColor);
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice render(@SqlType(VarcharType.class) Slice value, @SqlType(ColorType.class) long color)
    {
        StringBuilder builder = new StringBuilder(value.length());

        // color
        builder.append(ansiColorEscape(color))
                .append(value.toString(Charsets.UTF_8))
                .append(ANSI_RESET);

        return Slices.copiedBuffer(builder.toString(), Charsets.UTF_8);
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice render(@SqlType(BigintType.class) long value, @SqlType(ColorType.class) long color)
    {
        return render(Slices.copiedBuffer(Long.toString(value), Charsets.UTF_8), color);
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice render(@SqlType(DoubleType.class) double value, @SqlType(ColorType.class) long color)
    {
        return render(Slices.copiedBuffer(Double.toString(value), Charsets.UTF_8), color);
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice render(@SqlType(BooleanType.class) boolean value)
    {
        return value ? RENDERED_TRUE : RENDERED_FALSE;
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice bar(@SqlType(DoubleType.class) double percent, @SqlType(BigintType.class) long width)
    {
        return bar(percent, width, rgb(255, 0, 0), rgb(0, 255, 0));
    }

    @ScalarFunction
    @SqlType(VarcharType.class)
    public static Slice bar(
            @SqlType(DoubleType.class) double percent,
            @SqlType(BigintType.class) long width,
            @SqlType(ColorType.class) long lowColor,
            @SqlType(ColorType.class) long highColor)
    {
        long count = (int) (percent * width);
        count = Math.min(width, count);
        count = Math.max(0, count);

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < count; i++) {
            float fraction = (float) (i * 1.0 / (width - 1));

            int color = interpolate(fraction, lowColor, highColor);

            builder.append(ansiColorEscape(color))
                    .append('\u2588');
        }
        // reset
        builder.append(ANSI_RESET);

        // pad to force column to be the requested width
        for (long i = count; i < width; ++i) {
            builder.append(' ');
        }

        return Slices.copiedBuffer(builder.toString(), Charsets.UTF_8);
    }

    private static int interpolate(float fraction, long lowRgb, long highRgb)
    {
        float[] lowHsv = Color.RGBtoHSB(getRed(lowRgb), getGreen(lowRgb), getBlue(lowRgb), null);
        float[] highHsv = Color.RGBtoHSB(getRed(highRgb), getGreen(highRgb), getBlue(highRgb), null);

        float h = fraction * (highHsv[0] - lowHsv[0]) + lowHsv[0];
        float s = fraction * (highHsv[1] - lowHsv[1]) + lowHsv[1];
        float v = fraction * (highHsv[2] - lowHsv[2]) + lowHsv[2];

        return Color.HSBtoRGB(h, s, v) & 0xFF_FF_FF;
    }

    /**
     * Convert the given color (rgb or system) to an ansi-compatible index (for use with ESC[38;5;<value>m)
     */
    private static int toAnsi(int red, int green, int blue)
    {
        // rescale to 0-5 range
        red = red * 6 / 256;
        green = green * 6 / 256;
        blue = blue * 6 / 256;

        return 16 + red * 36 + green * 6 + blue;
    }

    private static String ansiColorEscape(long color)
    {
        return "\u001b[38;5;" + toAnsi(color) + 'm';
    }

    /**
     * Convert the given color (rgb or system) to an ansi-compatible index (for use with ESC[38;5;<value>m)
     */
    private static int toAnsi(long color)
    {
        if (color >= 0) { // an rgb value encoded as in Color.getRGB
            return toAnsi(getRed(color), getGreen(color), getBlue(color));
        }
        else {
            return (int) (-color - 1);
        }
    }

    @VisibleForTesting
    static int parseRgb(Slice color)
    {
        if (color.length() != 4 || color.getByte(0) != '#') {
            return -1;
        }

        int red = Character.digit((char) color.getByte(1), 16);
        int green = Character.digit((char) color.getByte(2), 16);
        int blue = Character.digit((char) color.getByte(3), 16);

        if (red == -1 || green == -1 || blue == -1) {
            return -1;
        }

        // replicate the nibbles to turn a color of the form #rgb => #rrggbb (css semantics)
        red = (red << 4) | red;
        green = (green << 4) | green;
        blue = (blue << 4) | blue;

        return (int) rgb(red, green, blue);
    }

    @VisibleForTesting
    static int getRed(long color)
    {
        checkArgument(color >= 0, "color is not a valid rgb value");

        return (int) ((color >>> 16) & 0xff);
    }

    @VisibleForTesting
    static int getGreen(long color)
    {
        checkArgument(color >= 0, "color is not a valid rgb value");

        return (int) ((color >>> 8) & 0xff);
    }

    @VisibleForTesting
    static int getBlue(long color)
    {
        checkArgument(color >= 0, "color is not a valid rgb value");

        return (int) (color & 0xff);
    }
}
