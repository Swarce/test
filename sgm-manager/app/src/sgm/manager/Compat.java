package sgm.manager;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.provider.Settings;

/** API 26+ calls made through reflection (the app is compiled against the API 23 SDK). */
final class Compat {
    private Compat() {
    }

    static Notification.Builder notificationBuilder(Context c, String channelId, String channelName) {
        try {
            Class<?> chCls = Class.forName("android.app.NotificationChannel");
            Object ch = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(channelId, channelName, 2 /* IMPORTANCE_LOW */);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationManager.class.getMethod("createNotificationChannel", chCls).invoke(nm, ch);
            return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, channelId);
        } catch (Exception e) {
            return new Notification.Builder(c);
        }
    }

    /** "hostname" when Private DNS is set to a specific provider, which bypasses the VPN's resolver. */
    static String privateDnsMode(Context c) {
        try {
            return Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        } catch (Exception e) {
            return null;
        }
    }
}
