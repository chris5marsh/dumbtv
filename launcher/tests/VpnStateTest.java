package tv.dumbtv.home;

import java.util.Arrays;

public final class VpnStateTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check("GB".equals(VpnState.country("Connected to United Kingdom #123")));
        check("AU".equals(VpnState.country("Australia - Sydney")));
        check("".equals(VpnState.country("Connecting...")));
        check("".equals(VpnState.country("Australian server unavailable")));
        check(VpnState.connected("Connected to Australia #1008 null"));
        check(!VpnState.connected("Connecting to Australia #1008… null"));
        check(VpnState.recent("GB,AU,IS", "AU").equals(Arrays.asList("AU", "GB", "IS")));
        check(VpnState.recent("GB,GB,AU,IS,US", "").equals(Arrays.asList("GB", "AU", "IS", "US")));
        check(VpnState.recent("GB,AU,IS,US", "CH").equals(Arrays.asList("CH", "GB", "AU", "IS")));
        check("1:01:01".equals(VpnState.duration(3661000)));
        check("0:00:00".equals(VpnState.duration(-1)));
        check("🇬🇧".equals(VpnState.flag("GB")));
        System.out.println("VpnStateTest passed");
    }
}
