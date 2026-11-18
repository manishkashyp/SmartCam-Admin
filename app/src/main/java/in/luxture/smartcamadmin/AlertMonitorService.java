package in.luxture.smartcamadmin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AlertMonitorService extends Service {

    private ScheduledExecutorService scheduler;
    private Map<String, Boolean> lastOnlineState = new HashMap<>();
    private Map<String, Integer> lastChargingState = new HashMap<>();
    private static final String CHANNEL_ID = "smartcam_admin_alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(202, buildForegroundNotification("Monitoring Cameras 24x7..."));
        startMonitoringLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel bgChan = new NotificationChannel(
                        CHANNEL_ID, "SmartCam Watchdog", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(bgChan);

                NotificationChannel alertChan = new NotificationChannel(
                        "smartcam_high_alerts", "Camera Security Alerts", NotificationManager.IMPORTANCE_HIGH);
                alertChan.enableVibration(true);
                nm.createNotificationChannel(alertChan);
            }
        }
    }

    private Notification buildForegroundNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("SmartCam Admin Guard")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void pushHighAlert(String title, String message, int notifId) {
        Intent i = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, notifId, i, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "smartcam_high_alerts")
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);

        Notification n = b.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, n);
    }

    private void startMonitoringLoop() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = getSharedPreferences("SmartCamAdminPrefs", MODE_PRIVATE);
                    String token = prefs.getString("token", "");
                    if (token.isEmpty()) return;

                    URL url = new URL("https://vastram.luxture.in/cctv/api.php?action=list&token=" + token);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);

                    if (conn.getResponseCode() == 200) {
                        InputStream is = conn.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                        is.close();

                        JSONObject data = new JSONObject(baos.toString("UTF-8"));
                        if ("ok".equals(data.optString("status"))) {
                            JSONArray nodes = data.optJSONArray("nodes");
                            if (nodes != null) {
                                for (int i = 0; i < nodes.length(); i++) {
                                    JSONObject c = nodes.getJSONObject(i);
                                    String cid = c.optString("cam_id");
                                    String cname = c.optString("cam_name", cid);
                                    boolean online = c.optBoolean("online", false);
                                    int charging = c.optInt("is_charging", 1);

                                    if (lastOnlineState.containsKey(cid) && lastOnlineState.get(cid) && !online) {
                                        pushHighAlert("🚨 Camera Offline Alert!", cname + " disconnected from network!", cid.hashCode());
                                    }
                                    lastOnlineState.put(cid, online);

                                    if (lastChargingState.containsKey(cid) && lastChargingState.get(cid) == 1 && charging == 0) {
                                        pushHighAlert("⚡ Power Unplugged Alert!", cname + " charger was unplugged!", cid.hashCode() + 1);
                                    }
                                    lastChargingState.put(cid, charging);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, 10, 20, TimeUnit.SECONDS);
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
