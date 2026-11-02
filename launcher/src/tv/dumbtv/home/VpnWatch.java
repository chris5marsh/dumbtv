package tv.dumbtv.home;

import android.app.Notification;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

/**
 * Reads NordVPN's status notification, and switches NordVPN between countries for the home screen.
 *
 * NordVPN's TV app takes {@code nordvpn://connect?country=XX} only while it is disconnected, and
 * has no link that disconnects. So a switch first opens NordVPN's own screen, where {@link Guard}
 * presses its Disconnect button, then sends the connect link once this listener sees the VPN
 * go down. Home comes back when the notification shows the state that was asked for.
 */
public final class VpnWatch extends NotificationListenerService {
    static final String PACKAGE = "com.nordvpn.android";
    /** Apps that ask to connect the VPN first when it is off. */
    static final java.util.Set<String> NEEDS_VPN = java.util.Collections.singleton("ar.tvplayer.tv");
    /** A target for NordVPN's quick connect: any country will do. */
    static final String ANY = "*";
    private static final long SWITCH_MS = 30_000;
    static volatile boolean available;
    static volatile boolean connected;
    static volatile String country = "";
    static volatile long since;

    /** The country asked for, "" for disconnected, or null when nothing is pending. */
    private static volatile String target;
    private static volatile long targetUntil;
    private static volatile boolean connectSent;
    /** Opened instead of home once the target is reached. */
    private static volatile Intent then;
    /** Read by {@link Guard}: NordVPN's Disconnect button should be pressed. */
    static volatile boolean disconnecting;

    /** Lives in home.conf, so a saved profile carries it to another TV. */
    static final String RECENT_KEY = "vpn.recent";

    /** Recent locations, newest first, as comma-separated country codes. */
    static String recent(Context c) {
        String saved = Config.get(c).get(RECENT_KEY, "");
        if (!saved.isEmpty()) return saved;
        // Versions before home.conf kept them in private preferences.
        SharedPreferences old = c.getSharedPreferences("vpn", Context.MODE_PRIVATE);
        return old.getString("recent", "");
    }

    static String pending() {
        String t = target;
        return t != null && SystemClock.elapsedRealtime() < targetUntil ? t : null;
    }

    /** Connects NordVPN to {@code code}'s country, or disconnects it when {@code code} is "". */
    static void request(Context c, String code) {
        request(c, code, null);
    }

    /** As {@link #request(Context, String)}, then opens {@code next} instead of coming home. */
    static void request(Context c, String code, Intent next) {
        then = next;
        target = code;
        targetUntil = SystemClock.elapsedRealtime() + SWITCH_MS;
        connectSent = false;
        disconnecting = connected;
        if (connected) {
            open(c, "nordvpn://tv_control_activity");
            Guard.pressDisconnect();
        }
        else if (!code.isEmpty()) connect(c);
    }

    private static void connect(Context c) {
        connectSent = true;
        open(c, ANY.equals(target) ? "nordvpn://connect"
                : "nordvpn://connect?country=" + Uri.encode(target.toLowerCase(java.util.Locale.ROOT)));
    }

    private static void open(Context c, String uri) {
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE));
    }

    private static void start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            target = null;
            then = null;
            disconnecting = false;
        }
    }

    /** Takes the next step of a pending switch after the VPN state changed. */
    private void advance() {
        String t = pending();
        if (t == null) {
            target = null;
            then = null;
            disconnecting = false;
            return;
        }
        if (t.isEmpty() ? !connected : connected && (ANY.equals(t) || t.equals(country))) {
            target = null;
            disconnecting = false;
            Intent next = then;
            then = null;
            if (next != null) start(this, next);
            else Guard.home(this);
        } else if (!connected && !t.isEmpty() && !connectSent) {
            disconnecting = false;
            connect(this);
        }
    }

    @Override public void onListenerConnected() { refresh(); }
    @Override public void onListenerDisconnected() { clear(false); }
    @Override public void onDestroy() { clear(false); super.onDestroy(); }
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (PACKAGE.equals(notification.getPackageName())) refresh();
    }
    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        if (PACKAGE.equals(notification.getPackageName())) refresh();
    }

    private static void clear(boolean known) {
        available = known;
        connected = false;
        country = "";
        since = 0;
    }

    private void refresh() {
        read();
        advance();
    }

    private void read() {
        StatusBarNotification[] notifications;
        try { notifications = getActiveNotifications(); }
        catch (SecurityException e) { clear(false); return; }
        if (notifications == null) { clear(false); return; }
        for (StatusBarNotification entry : notifications) {
            if (!PACKAGE.equals(entry.getPackageName())) continue;
            Notification n = entry.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0 || n.actions == null) continue;
            boolean disconnectable = false;
            for (Notification.Action candidate : n.actions) {
                if ("Disconnect".equalsIgnoreCase(String.valueOf(candidate.title))) disconnectable = true;
            }
            String text = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)) + " "
                    + String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)) + " "
                    + String.valueOf(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String code = VpnState.country(text);
            // Require a location, the word "connected" and Nord's disconnect control together.
            if (!disconnectable || code.isEmpty() || !VpnState.connected(text)) continue;
            if (!connected || !code.equals(country)) {
                long age = Math.max(0, System.currentTimeMillis() - n.when);
                since = SystemClock.elapsedRealtime() - Math.min(age, SystemClock.elapsedRealtime());
            }
            country = code;
            connected = true;
            available = true;
            String before = recent(this);
            String after = TextUtils.join(",", VpnState.recent(before, code));
            // Every write redraws the home screen on its next return, so only a change is saved.
            if (!after.equals(Config.get(this).get(RECENT_KEY, ""))) Config.get(this).set(RECENT_KEY, after);
            return;
        }
        clear(true);
    }
}
