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
    private static final long SWITCH_MS = 30_000;
    static volatile boolean available;
    static volatile boolean connected;
    static volatile String country = "";
    static volatile long since;

    /** The country asked for, "" for disconnected, or null when nothing is pending. */
    private static volatile String target;
    private static volatile long targetUntil;
    private static volatile boolean connectSent;
    /** Read by {@link Guard}: NordVPN's Disconnect button should be pressed. */
    static volatile boolean disconnecting;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("vpn", Context.MODE_PRIVATE);
    }

    static String pending() {
        String t = target;
        return t != null && SystemClock.elapsedRealtime() < targetUntil ? t : null;
    }

    /** Connects NordVPN to {@code code}'s country, or disconnects it when {@code code} is "". */
    static void request(Context c, String code) {
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
        open(c, "nordvpn://connect?country=" + Uri.encode(target.toLowerCase(java.util.Locale.ROOT)));
    }

    private static void open(Context c, String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            target = null;
            disconnecting = false;
        }
    }

    /** Takes the next step of a pending switch after the VPN state changed. */
    private void advance() {
        String t = pending();
        if (t == null) {
            target = null;
            disconnecting = false;
            return;
        }
        if (t.isEmpty() ? !connected : connected && t.equals(country)) {
            target = null;
            disconnecting = false;
            Guard.home(this);
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
            SharedPreferences p = prefs(this);
            String recent = TextUtils.join(",", VpnState.recent(p.getString("recent", ""), code));
            p.edit().putString("recent", recent).apply();
            return;
        }
        clear(true);
    }
}
