package org.thunderdog.challegram.voip;

final class StringUtils {
    private StringUtils() {
    }

    static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
