package in.luxture.smartcamadmin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String API_URL = "https://vastram.luxture.in/cctv/api.php";
    private static final String CLIPS_BASE_URL = "https://vastram.luxture.in/cctv/ssd_clips/";
    private static final int RC_GOOGLE_SIGN_IN = 9001;

    private SharedPreferences prefs;
    private String authToken = "";
    private String userName = "";
    private String userRole = "viewer";

    private FrameLayout rootContainer;
    private LinearLayout liveCardsContainer;
    private LinearLayout ssdListContainer;
    private LinearLayout usersListContainer;
    private VideoView ssdVideoPlayer;

    private ExecutorService netPool = Executors.newCachedThreadPool();
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isActivityVisible = true;
    private String activeTab = "LIVE";

    private Map<String, ImageView> camImageViews = new HashMap<>();
    private Map<String, TextView> camHeaderViews = new HashMap<>();
    private Map<String, TextView> camTelViews = new HashMap<>();
    private Map<String, String> camLanUrls = new HashMap<>();
    private Map<String, Boolean> camDirectLanMode = new HashMap<>();
    private Map<String, Integer> camClientRotation = new HashMap<>();
    private Map<String, Boolean> camStreamRunning = new HashMap<>();

    // User Editor Fields
    private int editingUserId = 0;
    private EditText uNameEt, uUserEt, uEmailEt, uPassEt;
    private Spinner uRoleSpinner;
    private TextView userFormTitle;
    private Button saveUserBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("SmartCamAdminPrefs", MODE_PRIVATE);
        authToken = prefs.getString("token", "");
        userName = prefs.getString("name", "");
        userRole = prefs.getString("role", "viewer");

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.parseColor("#0B0F19"));
        setContentView(rootContainer);

        if (authToken.isEmpty()) {
            showLoginScreen();
        } else {
            startGuardService();
            showMainDashboard();
        }
    }

    private void startGuardService() {
        Intent s = new Intent(this, AlertMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(s);
        } else {
            startService(s);
        }
    }

    // =========================================================================
    // 1. NATIVE LOGIN SCREEN (USERNAME/PASSWORD + GOOGLE SIGN-IN)
    // =========================================================================
    private void showLoginScreen() {
        rootContainer.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(28);
        box.setPadding(p, dp(60), p, p);

        TextView logo = new TextView(this);
        logo.setText("🛡️ SmartCam Enterprise");
        logo.setTextColor(Color.parseColor("#00E676"));
        logo.setTextSize(24);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(logo);

        TextView sub = new TextView(this);
        sub.setText(" Native Admin & Family Control App");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(28));
        box.addView(sub);

        final EditText userEt = makeInput("Username or Gmail ID");
        final EditText passEt = makeInput("Password");
        passEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(userEt);
        box.addView(passEt);

        Button loginBtn = new Button(this);
        loginBtn.setText("🔒 SECURE SIGN IN");
        loginBtn.setBackgroundColor(Color.parseColor("#2979FF"));
        loginBtn.setTextColor(Color.WHITE);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, dp(14));
        loginBtn.setLayoutParams(lp);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performPasswordLogin(userEt.getText().toString().trim(), passEt.getText().toString());
            }
        });
        box.addView(loginBtn);

        Button googleBtn = new Button(this);
        googleBtn.setText("G  SIGN IN WITH GOOGLE ACCOUNT");
        googleBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        googleBtn.setTextColor(Color.WHITE);
        googleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        googleBtn.setLayoutParams(lp);
        googleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGoogleLoginFlow();
            }
        });
        box.addView(googleBtn);

        scroll.addView(box);
        rootContainer.addView(scroll);
    }

    private void performPasswordLogin(final String u, final String p) {
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "Enter Username & Password", Toast.LENGTH_SHORT).show();
            return;
        }
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "app_login");
                    req.put("username", u);
                    req.put("password", p);
                    JSONObject res = postJson(req);
                    if ("ok".equals(res.optString("status"))) {
                        saveSessionAndLaunch(
                                res.optString("token"),
                                res.optString("name", u),
                                res.optString("role", "viewer")
                        );
                    } else {
                        final String msg = res.optString("msg", "Login Failed");
                        uiHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                    }
                } catch (Exception e) {
                    uiHandler.post(() -> Toast.makeText(MainActivity.this, "Network Error", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void startGoogleLoginFlow() {
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .build();
            GoogleSignInClient client = GoogleSignIn.getClient(this, gso);
            client.signOut();
            startActivityForResult(client.getSignInIntent(), RC_GOOGLE_SIGN_IN);
        } catch (Exception e) {
            Toast.makeText(this, "Google Play Services error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null && account.getEmail() != null) {
                    performGoogleEmailVerify(account.getEmail(), account.getIdToken());
                }
            } catch (ApiException e) {
                Toast.makeText(this, "Google Sign-In Cancelled or Unconfigured (Code " + e.getStatusCode() + ")", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void performGoogleEmailVerify(final String email, final String idToken) {
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "app_google_login");
                    req.put("email", email);
                    req.put("id_token", idToken != null ? idToken : "");
                    JSONObject res = postJson(req);
                    if ("ok".equals(res.optString("status"))) {
                        saveSessionAndLaunch(
                                res.optString("token"),
                                res.optString("name", email),
                                res.optString("role", "viewer")
                        );
                    } else {
                        final String msg = res.optString("msg", "Email not authorized by Admin!");
                        uiHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void saveSessionAndLaunch(final String token, final String name, final String role) {
        authToken = token;
        userName = name;
        userRole = role;
        prefs.edit()
                .putString("token", token)
                .putString("name", name)
                .putString("role", role)
                .apply();
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                startGuardService();
                showMainDashboard();
            }
        });
    }

    // =========================================================================
    // 2. MAIN ENTERPRISE DASHBOARD (3 TABS: LIVE, SSD PLAYBACK, USERS)
    // =========================================================================
    private void showMainDashboard() {
        rootContainer.removeAllViews();
        camImageViews.clear();
        camHeaderViews.clear();
        camTelViews.clear();
        camStreamRunning.clear();

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#111827"));
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("🛡️ SmartCam Admin  ");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(17);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(appTitle);

        TextView roleBadge = new TextView(this);
        roleBadge.setText(userName + " (" + userRole.toUpperCase() + ")");
        roleBadge.setTextColor(Color.parseColor("#00E676"));
        roleBadge.setTextSize(11);
        roleBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        titleRow.addView(roleBadge);
        topBar.addView(titleRow);

        // Navigation Bar
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, dp(8), 0, 0);

        final ScrollView liveScroll = new ScrollView(this);
        final ScrollView ssdScroll = new ScrollView(this);
        final ScrollView usersScroll = new ScrollView(this);

        ssdScroll.setVisibility(View.GONE);
        usersScroll.setVisibility(View.GONE);

        navRow.addView(makeNavButton("🎥 Live Cams", "#2979FF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = "LIVE";
                liveScroll.setVisibility(View.VISIBLE);
                ssdScroll.setVisibility(View.GONE);
                usersScroll.setVisibility(View.GONE);
                fetchCameraNodes();
            }
        }));

        navRow.addView(makeNavButton("📼 SSD Footage", "#7C3AED", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activeTab = "SSD";
                liveScroll.setVisibility(View.GONE);
                ssdScroll.setVisibility(View.VISIBLE);
                usersScroll.setVisibility(View.GONE);
                loadSsdRecordings();
            }
        }));

        if ("admin".equalsIgnoreCase(userRole)) {
            navRow.addView(makeNavButton("👥 Users & Access", "#00897B", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activeTab = "USERS";
                    liveScroll.setVisibility(View.GONE);
                    ssdScroll.setVisibility(View.GONE);
                    usersScroll.setVisibility(View.VISIBLE);
                    loadUsersList();
                }
            }));
        }

        navRow.addView(makeNavButton("🚪 Logout", "#C62828", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().clear().apply();
                authToken = "";
                showLoginScreen();
            }
        }));

        navScroll.addView(navRow);
        topBar.addView(navScroll);
        mainCol.addView(topBar);

        // Content Frame
        FrameLayout contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams cfParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        contentFrame.setLayoutParams(cfParams);

        // 1. Live Tab Content
        liveCardsContainer = new LinearLayout(this);
        liveCardsContainer.setOrientation(LinearLayout.VERTICAL);
        liveCardsContainer.setPadding(dp(10), dp(10), dp(10), dp(20));
        liveScroll.addView(liveCardsContainer);
        contentFrame.addView(liveScroll);

        // 2. SSD Tab Content
        LinearLayout ssdBox = new LinearLayout(this);
        ssdBox.setOrientation(LinearLayout.VERTICAL);
        ssdBox.setPadding(dp(12), dp(12), dp(12), dp(20));

        TextView ssdHead = new TextView(this);
        ssdHead.setText("📼 JioFiber SSD Recorded Videos");
        ssdHead.setTextColor(Color.WHITE);
        ssdHead.setTextSize(16);
        ssdHead.setTypeface(Typeface.DEFAULT_BOLD);
        ssdBox.addView(ssdHead);

        ssdVideoPlayer = new VideoView(this);
        LinearLayout.LayoutParams vpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        vpParams.setMargins(0, dp(8), 0, dp(10));
        ssdVideoPlayer.setLayoutParams(vpParams);
        ssdVideoPlayer.setVisibility(View.GONE);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(ssdVideoPlayer);
        ssdVideoPlayer.setMediaController(mc);
        ssdBox.addView(ssdVideoPlayer);

        ssdListContainer = new LinearLayout(this);
        ssdListContainer.setOrientation(LinearLayout.VERTICAL);
        ssdBox.addView(ssdListContainer);
        ssdScroll.addView(ssdBox);
        contentFrame.addView(ssdScroll);

        // 3. Users Management Tab Content
        LinearLayout uBox = new LinearLayout(this);
        uBox.setOrientation(LinearLayout.VERTICAL);
        uBox.setPadding(dp(12), dp(12), dp(12), dp(20));

        userFormTitle = new TextView(this);
        userFormTitle.setText("➕ Add / Edit Family Member or Admin");
        userFormTitle.setTextColor(Color.parseColor("#00E676"));
        userFormTitle.setTextSize(15);
        userFormTitle.setTypeface(Typeface.DEFAULT_BOLD);
        uBox.addView(userFormTitle);

        uNameEt = makeInput("Full Name (e.g. Manish)");
        uUserEt = makeInput("Username (e.g. admin)");
        uEmailEt = makeInput("Gmail ID (For 1-Tap Google Sign-In)");
        uPassEt = makeInput("Password (Leave blank to keep existing)");
        uBox.addView(uNameEt);
        uBox.addView(uUserEt);
        uBox.addView(uEmailEt);
        uBox.addView(uPassEt);

        uRoleSpinner = new Spinner(this);
        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"viewer", "admin"});
        uRoleSpinner.setAdapter(roleAdapter);
        uRoleSpinner.setBackgroundColor(Color.parseColor("#1E293B"));
        uBox.addView(uRoleSpinner);

        saveUserBtn = new Button(this);
        saveUserBtn.setText("💾 SAVE USER ACCOUNT");
        saveUserBtn.setBackgroundColor(Color.parseColor("#2979FF"));
        saveUserBtn.setTextColor(Color.WHITE);
        saveUserBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveUserFromApp();
            }
        });
        uBox.addView(saveUserBtn);

        usersListContainer = new LinearLayout(this);
        usersListContainer.setOrientation(LinearLayout.VERTICAL);
        usersListContainer.setPadding(0, dp(14), 0, 0);
        uBox.addView(usersListContainer);
        usersScroll.addView(uBox);
        contentFrame.addView(usersScroll);

        mainCol.addView(contentFrame);
        rootContainer.addView(mainCol);

        fetchCameraNodes();
        startPeriodicNodePoll();
    }

    // =========================================================================
    // 3. LIVE CAMERA CARDS & SMOOTH DOUBLE-BUFFERED PRELOADER
    // =========================================================================
    private void startPeriodicNodePoll() {
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!authToken.isEmpty() && isActivityVisible && "LIVE".equals(activeTab)) {
                    fetchCameraNodes();
                }
                if (!authToken.isEmpty()) {
                    uiHandler.postDelayed(this, 4500);
                }
            }
        }, 4500);
    }

    private void fetchCameraNodes() {
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(API_URL + "?action=list&token=" + authToken);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);

                    if (conn.getResponseCode() == 401) {
                        uiHandler.post(() -> {
                            prefs.edit().clear().apply();
                            authToken = "";
                            showLoginScreen();
                        });
                        return;
                    }

                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    is.close();

                    final JSONObject data = new JSONObject(baos.toString("UTF-8"));
                    if ("ok".equals(data.optString("status"))) {
                        final JSONArray nodes = data.optJSONArray("nodes");
                        uiHandler.post(() -> renderCameraCards(nodes));
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void renderCameraCards(JSONArray nodes) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject cam = nodes.optJSONObject(i);
            if (cam == null) continue;

            final String cid = cam.optString("cam_id");
            String cname = cam.optString("cam_name", cid);
            String res = cam.optString("resolution", "720p");
            boolean online = cam.optBoolean("online", false);
            int secAgo = cam.optInt("sec_ago", 0);
            int bat = cam.optInt("battery_level", 0);
            int chg = cam.optInt("is_charging", 0);
            double temp = cam.optDouble("temp_c", 0.0);
            double ssd = cam.optDouble("ssd_free_gb", 0.0);
            final String lat = cam.optString("lat", "");
            final String lng = cam.optString("lng", "");
            String peerUrl = cam.optString("peer_id", "");

            camLanUrls.put(cid, peerUrl);

            if (!camImageViews.containsKey(cid)) {
                camDirectLanMode.put(cid, false);
                camClientRotation.put(cid, 0);

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundColor(Color.parseColor("#151C2C"));
                LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cParams.setMargins(0, 0, 0, dp(16));
                card.setLayoutParams(cParams);
                card.setPadding(dp(10), dp(10), dp(10), dp(10));

                TextView headerTv = new TextView(this);
                headerTv.setTextSize(14);
                headerTv.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(headerTv);
                camHeaderViews.put(cid, headerTv);

                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams ivParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(235));
                ivParams.setMargins(0, dp(8), 0, dp(8));
                iv.setLayoutParams(ivParams);
                iv.setBackgroundColor(Color.BLACK);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(iv);
                camImageViews.put(cid, iv);

                TextView telTv = new TextView(this);
                telTv.setTextColor(Color.parseColor("#94A3B8"));
                telTv.setTextSize(12);
                telTv.setPadding(0, 0, 0, dp(6));
                card.addView(telTv);
                camTelViews.put(cid, telTv);

                // Controls Row 1: Direct LAN, Screen Flip, GPS Map
                HorizontalScrollView ctrlScroll1 = new HorizontalScrollView(this);
                LinearLayout row1 = new LinearLayout(this);
                row1.setOrientation(LinearLayout.HORIZONTAL);

                final Button lanBtn = makeSmallButton("⚡ Direct Wi-Fi", "#00897B");
                lanBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean cur = !camDirectLanMode.get(cid);
                        camDirectLanMode.put(cid, cur);
                        lanBtn.setText(cur ? "🌐 Cloud Mode" : "⚡ Direct Wi-Fi");
                        lanBtn.setBackgroundColor(Color.parseColor(cur ? "#E65100" : "#00897B"));
                    }
                });
                row1.addView(lanBtn);

                Button flipUiBtn = makeSmallButton("📐 Flip View", "#334155");
                flipUiBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int deg = (camClientRotation.get(cid) + 90) % 360;
                        camClientRotation.put(cid, deg);
                    }
                });
                row1.addView(flipUiBtn);

                Button mapBtn = makeSmallButton("📍 GPS Map", "#0284C7");
                mapBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!lat.isEmpty() && !lng.isEmpty()) {
                            Intent mapIntent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://www.google.com/maps?q=" + lat + "," + lng));
                            startActivity(mapIntent);
                        }
                    }
                });
                row1.addView(mapBtn);
                ctrlScroll1.addView(row1);
                card.addView(ctrlScroll1);

                // Admin-Only Hardware & Quality Controls
                if ("admin".equalsIgnoreCase(userRole)) {
                    HorizontalScrollView ctrlScroll2 = new HorizontalScrollView(this);
                    LinearLayout row2 = new LinearLayout(this);
                    row2.setOrientation(LinearLayout.HORIZONTAL);
                    row2.setPadding(0, dp(6), 0, 0);

                    row2.addView(makeCmdButton("480p", "#475569", cid, "RES_480P"));
                    row2.addView(makeCmdButton("720p", "#0284C7", cid, "RES_720P"));
                    row2.addView(makeCmdButton("1080p", "#2563EB", cid, "RES_1080P"));
                    row2.addView(makeCmdButton("2K", "#7C3AED", cid, "RES_2K"));
                    row2.addView(makeCmdButton("4K Max", "#9333EA", cid, "RES_4K"));
                    row2.addView(makeCmdButton("🔄 Rot 90°", "#2979FF", cid, "ROTATE_90"));
                    row2.addView(makeCmdButton("🔦 Torch", "#FF9100", cid, "TORCH_TOGGLE"));
                    row2.addView(makeCmdButton("📷 Flip Cam", "#00897B", cid, "SWITCH_CAM"));
                    row2.addView(makeCmdButton("🔍 2x", "#1E293B", cid, "ZOOM_2X"));
                    row2.addView(makeCmdButton("🔍 1x", "#1E293B", cid, "ZOOM_1X"));
                    row2.addView(makeCmdButton("🚨 Siren", "#D50000", cid, "SIREN_ON"));

                    ctrlScroll2.addView(row2);
                    card.addView(ctrlScroll2);
                }

                liveCardsContainer.addView(card);
                startCameraFrameStream(cid);
            }

            TextView hTv = camHeaderViews.get(cid);
            if (hTv != null) {
                String statusBadge = online ? "🟢 LIVE" : "🔴 OFFLINE (" + secAgo + "s)";
                hTv.setText(cname + "  [" + res + "]   " + statusBadge);
                hTv.setTextColor(online ? Color.parseColor("#00E676") : Color.parseColor("#FF5252"));
            }

            TextView tTv = camTelViews.get(cid);
            if (tTv != null) {
                tTv.setText("🔋 " + bat + "%" + (chg == 1 ? " ⚡Charging" : "") +
                        "   |   🌡️ " + temp + "°C   |   💾 SSD Free: " + ssd + " GB");
            }
        }
    }

    private void startCameraFrameStream(final String cid) {
        if (Boolean.TRUE.equals(camStreamRunning.get(cid))) return;
        camStreamRunning.put(cid, true);

        netPool.execute(new Runnable() {
            @Override
            public void run() {
                while (!authToken.isEmpty() && camImageViews.containsKey(cid)) {
                    if (!isActivityVisible || !"LIVE".equals(activeTab)) {
                        try { Thread.sleep(600); } catch (Exception ignored) {}
                        continue;
                    }
                    long startMs = System.currentTimeMillis();
                    try {
                        boolean isLan = Boolean.TRUE.equals(camDirectLanMode.get(cid));
                        String peer = camLanUrls.get(cid);
                        String frameUrl = (isLan && peer != null && peer.startsWith("http"))
                                ? peer + "/shot.jpg?t=" + startMs
                                : API_URL + "?action=view_frame&cam_id=" + cid + "&token=" + authToken + "&t=" + startMs;

                        HttpURLConnection conn = (HttpURLConnection) new URL(frameUrl).openConnection();
                        conn.setConnectTimeout(2500);
                        conn.setReadTimeout(2500);
                        if (conn.getResponseCode() == 200) {
                            InputStream is = conn.getInputStream();
                            byte[] bytes = readAllBytes(is);
                            is.close();
                            if (bytes.length > 200) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                if (bmp != null) {
                                    int rot = camClientRotation.get(cid) != null ? camClientRotation.get(cid) : 0;
                                    if (rot != 0) {
                                        Matrix m = new Matrix();
                                        m.postRotate(rot);
                                        Bitmap rBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                                        bmp.recycle();
                                        bmp = rBmp;
                                    }
                                    final Bitmap finalBmp = bmp;
                                    uiHandler.post(() -> {
                                        ImageView iv = camImageViews.get(cid);
                                        if (iv != null) iv.setImageBitmap(finalBmp);
                                    });
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    long elapsed = System.currentTimeMillis() - startMs;
                    long wait = Boolean.TRUE.equals(camDirectLanMode.get(cid)) ? 90 : 320;
                    if (elapsed < wait) {
                        try { Thread.sleep(wait - elapsed); } catch (Exception ignored) {}
                    }
                }
                camStreamRunning.put(cid, false);
            }
        });
    }

    private void sendHardwareCmd(final String cid, final String cmd) {
        Toast.makeText(this, "Sending " + cmd + "...", Toast.LENGTH_SHORT).show();
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Boolean.TRUE.equals(camDirectLanMode.get(cid))) {
                        String peer = camLanUrls.get(cid);
                        if (peer != null && peer.startsWith("http")) {
                            new URL(peer + "/cmd?action=" + cmd).openStream().close();
                        }
                    }
                    JSONObject req = new JSONObject();
                    req.put("action", "send_cmd");
                    req.put("token", authToken);
                    req.put("cam_id", cid);
                    req.put("cmd", cmd);
                    postJson(req);
                } catch (Exception ignored) {}
            }
        });
    }

    // =========================================================================
    // 4. SSD RECORDINGS TAB (LIST, FETCH FROM CAMERA SSD, PLAY VIDEO)
    // =========================================================================
    private void loadSsdRecordings() {
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(API_URL + "?action=list_recordings&token=" + authToken);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    InputStream is = conn.getInputStream();
                    JSONObject data = new JSONObject(new String(readAllBytes(is), "UTF-8"));
                    is.close();
                    final JSONArray clips = data.optJSONArray("clips");
                    uiHandler.post(() -> renderSsdList(clips));
                } catch (Exception ignored) {}
            }
        });
    }

    private void renderSsdList(JSONArray clips) {
        ssdListContainer.removeAllViews();
        if (clips == null || clips.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No SSD recordings indexed yet. Camera Node syncs 10-min MP4 clips automatically.");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            empty.setPadding(0, dp(10), 0, 0);
            ssdListContainer.addView(empty);
            return;
        }
        for (int i = 0; i < clips.length(); i++) {
            JSONObject c = clips.optJSONObject(i);
            if (c == null) continue;
            final String cid = c.optString("cam_id");
            final String fname = c.optString("file_name");
            double sizeMb = c.optDouble("file_size_mb", 0);
            String dt = c.optString("recorded_at", "");
            boolean uploaded = c.optInt("is_uploaded", 0) == 1;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Color.parseColor("#151C2C"));
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rp);

            TextView info = new TextView(this);
            info.setText(cid + " | " + fname + "\n" + dt + " (" + sizeMb + " MB)");
            info.setTextColor(Color.WHITE);
            info.setTextSize(12);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            row.addView(info);

            Button actBtn = makeSmallButton(uploaded ? "▶ Play Video" : "☁️ Fetch SSD", uploaded ? "#00E676" : "#2979FF");
            if (uploaded) actBtn.setTextColor(Color.BLACK);
            actBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (uploaded) {
                        ssdVideoPlayer.setVisibility(View.VISIBLE);
                        ssdVideoPlayer.setVideoURI(Uri.parse(CLIPS_BASE_URL + cid + "_" + fname));
                        ssdVideoPlayer.start();
                    } else {
                        requestSsdClipUpload(cid, fname);
                    }
                }
            });
            row.addView(actBtn);
            ssdListContainer.addView(row);
        }
    }

    private void requestSsdClipUpload(final String cid, final String fname) {
        Toast.makeText(this, "Requesting clip from Camera SSD...", Toast.LENGTH_LONG).show();
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "request_clip");
                    req.put("token", authToken);
                    req.put("cam_id", cid);
                    req.put("file_name", fname);
                    postJson(req);
                } catch (Exception ignored) {}
            }
        });
    }

    // =========================================================================
    // 5. ADMIN USER MANAGEMENT TAB (CREATE, EDIT PASSWORD/GMAIL, DELETE)
    // =========================================================================
    private void loadUsersList() {
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(API_URL + "?action=list_users&token=" + authToken);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    InputStream is = conn.getInputStream();
                    JSONObject data = new JSONObject(new String(readAllBytes(is), "UTF-8"));
                    is.close();
                    final JSONArray users = data.optJSONArray("users");
                    uiHandler.post(() -> renderUsersList(users));
                } catch (Exception ignored) {}
            }
        });
    }

    private void renderUsersList(JSONArray users) {
        usersListContainer.removeAllViews();
        if (users == null) return;
        for (int i = 0; i < users.length(); i++) {
            final JSONObject u = users.optJSONObject(i);
            if (u == null) continue;
            final int uid = u.optInt("id");
            final String name = u.optString("name");
            final String uname = u.optString("username");
            final String email = u.optString("email", "");
            final String role = u.optString("role", "viewer");

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundColor(Color.parseColor("#151C2C"));
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(0, 0, 0, dp(8));
            card.setLayoutParams(cp);

            TextView tv = new TextView(this);
            tv.setText(name + " (" + role.toUpperCase() + ")\nUser: " + uname + " | Gmail: " + email);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(12);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            card.addView(tv);

            Button editBtn = makeSmallButton("✏️ Edit", "#2979FF");
            editBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editingUserId = uid;
                    uNameEt.setText(name);
                    uUserEt.setText(uname);
                    uEmailEt.setText(email);
                    uPassEt.setText("");
                    uRoleSpinner.setSelection("admin".equalsIgnoreCase(role) ? 1 : 0);
                    userFormTitle.setText("✏️ Editing User: " + name);
                    saveUserBtn.setText("✅ UPDATE USER & PASSWORD");
                }
            });
            card.addView(editBtn);

            Button delBtn = makeSmallButton("🗑️", "#C62828");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteUserFromApp(uid);
                }
            });
            card.addView(delBtn);

            usersListContainer.addView(card);
        }
    }

    private void saveUserFromApp() {
        final String name = uNameEt.getText().toString().trim();
        final String uname = uUserEt.getText().toString().trim();
        final String email = uEmailEt.getText().toString().trim();
        final String pass = uPassEt.getText().toString();
        final String role = uRoleSpinner.getSelectedItem().toString();

        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "add_user");
                    req.put("token", authToken);
                    req.put("user_id", editingUserId);
                    req.put("name", name);
                    req.put("username", uname);
                    req.put("email", email);
                    req.put("password", pass);
                    req.put("role", role);
                    final JSONObject res = postJson(req);
                    uiHandler.post(() -> {
                        Toast.makeText(MainActivity.this, res.optString("msg", "Saved!"), Toast.LENGTH_SHORT).show();
                        editingUserId = 0;
                        uNameEt.setText("");
                        uUserEt.setText("");
                        uEmailEt.setText("");
                        uPassEt.setText("");
                        userFormTitle.setText("➕ Add / Edit Family Member or Admin");
                        saveUserBtn.setText("💾 SAVE USER ACCOUNT");
                        loadUsersList();
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    private void deleteUserFromApp(final int uid) {
        netPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "delete_user");
                    req.put("token", authToken);
                    req.put("user_id", uid);
                    postJson(req);
                    uiHandler.post(() -> loadUsersList());
                } catch (Exception ignored) {}
            }
        });
    }

    // =========================================================================
    // HELPER UI & NETWORK METHODS
    // =========================================================================
    private JSONObject postJson(JSONObject payload) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(payload.toString().getBytes("UTF-8"));
        os.close();
        InputStream is = conn.getInputStream();
        byte[] bytes = readAllBytes(is);
        is.close();
        return new JSONObject(new String(bytes, "UTF-8"));
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
        return baos.toByteArray();
    }

    private EditText makeInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(Color.parseColor("#1E293B"));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        et.setLayoutParams(lp);
        return et;
    }

    private Button makeNavButton(String text, String colorHex, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(listener);
        return b;
    }

    private Button makeSmallButton(String text, String colorHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(colorHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private Button makeCmdButton(String text, String colorHex, final String cid, final String cmd) {
        Button b = makeSmallButton(text, colorHex);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendHardwareCmd(cid, cmd);
            }
        });
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
