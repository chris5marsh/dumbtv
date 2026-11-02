package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Notification-derived data only; never treats a connection request as success. */
final class VpnState {
    /** How many recent locations the home screen keeps and shows. */
    static final int RECENT = 4;

    private VpnState() {}

    static String country(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String result = "";
        int longest = 0;
        for (String code : Locale.getISOCountries()) {
            String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
            String needle = name.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(needle);
            if (at >= 0 && name.length() > longest
                    && (at == 0 || !Character.isLetter(lower.charAt(at - 1)))
                    && (at + needle.length() == lower.length()
                        || !Character.isLetter(lower.charAt(at + needle.length())))) {
                result = code;
                longest = name.length();
            }
        }
        return result;
    }

    /** "Connecting to …" and "Disconnected" both name a country but are not a connection. */
    static boolean connected(String text) {
        return text.toLowerCase(Locale.ROOT).contains("connected to");
    }

    static String name(String code) {
        return new Locale("", code).getDisplayCountry(Locale.ENGLISH);
    }

    static String flag(String code) {
        if (!code.matches("[A-Z]{2}")) return "";
        return new String(Character.toChars(0x1f1e6 + code.charAt(0) - 'A'))
                + new String(Character.toChars(0x1f1e6 + code.charAt(1) - 'A'));
    }

    static List<String> recent(String saved, String connected) {
        List<String> result = new ArrayList<>();
        for (String code : (connected + "," + saved).split(",")) {
            if (code.matches("[A-Z]{2}") && !result.contains(code) && result.size() < RECENT) result.add(code);
        }
        return result;
    }

    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
