package in.itantra.mobile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.nsd.*;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.WifiP2pManager.*;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

/**
 * Foreground, multi-link local transport with Wi-Fi Direct (P2P), LAN TCP, and Bluetooth Classic RFCOMM Fallback.
 * Primary: Wi-Fi Direct / Wi-Fi LAN streaming on port 8988.
 * Fallback: Bluetooth Classic RFCOMM with unified ITP frame stream.
 * Automatically switches to Bluetooth RFCOMM if Wi-Fi link drops, keeping speech flowing seamlessly.
 */
public final class LocalTransport {
    public static final UUID SWAR_BT_UUID = UUID.fromString("6a4b1234-9876-4321-b1c2-123456789abc");
    public static final UUID LINC_BT_UUID = SWAR_BT_UUID;

    public interface Listener {
        void event(String type, JSONObject data);
        void received(ItpPacket.Decoded message);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Unified Transport Socket Abstraction
    // ─────────────────────────────────────────────────────────────────────────────
    public interface TransportSocket extends Closeable {
        InputStream getInputStream() throws IOException;
        OutputStream getOutputStream() throws IOException;
        void close() throws IOException;
        boolean isConnected();
        boolean isClosed();
        String getRemoteAddress();
        String getTransportType(); // "wifi" or "bluetooth"
    }

    public static final class TcpTransportSocket implements TransportSocket {
        private final Socket socket;
        public TcpTransportSocket(Socket socket) { this.socket = socket; }
        @Override public InputStream getInputStream() throws IOException { return socket.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return socket.getOutputStream(); }
        @Override public void close() throws IOException { socket.close(); }
        @Override public boolean isConnected() { return socket != null && socket.isConnected() && !socket.isClosed(); }
        @Override public boolean isClosed() { return socket == null || socket.isClosed(); }
        @Override public String getRemoteAddress() {
            return (socket != null && socket.getInetAddress() != null) ? socket.getInetAddress().getHostAddress() : "";
        }
        @Override public String getTransportType() { return "wifi"; }
        public Socket getRawSocket() { return socket; }
    }

    public static final class BtTransportSocket implements TransportSocket {
        private final BluetoothSocket socket;
        public BtTransportSocket(BluetoothSocket socket) { this.socket = socket; }
        @Override public InputStream getInputStream() throws IOException { return socket.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return socket.getOutputStream(); }
        @Override public void close() throws IOException { socket.close(); }
        @Override public boolean isConnected() { return socket != null && socket.isConnected(); }
        @Override public boolean isClosed() { return socket == null || !socket.isConnected(); }
        @Override public String getRemoteAddress() {
            try {
                return (socket != null && socket.getRemoteDevice() != null) ? socket.getRemoteDevice().getAddress() : "";
            } catch (Exception ignored) { return ""; }
        }
        @Override public String getTransportType() { return "bluetooth"; }
        public BluetoothSocket getRawSocket() { return socket; }
    }

    private final Context context;
    private volatile Listener listener;
    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final UUID session = UUID.randomUUID();
    private final Map<Long, Long> pending = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Queue<String[]> outbox = new ConcurrentLinkedQueue<>();
    private final Object writeLock = new Object();

    // WiFi Direct (P2P) fields
    private WifiP2pManager p2pManager;
    private Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private volatile boolean p2pReceiverRegistered = false;
    private volatile boolean p2pDiscovering = false;
    private volatile boolean p2pEnabled = false;
    private volatile boolean p2pGroupConnected = false;
    private volatile boolean p2pIsGroupOwner = false;
    private volatile String p2pGroupOwnerAddress = null;
    private volatile String myP2pAddress = null;
    private volatile long lastP2pDiscoveryTime = 0;

    // Bluetooth Classic RFCOMM fields
    private final BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothServerSocket btServer;
    private BroadcastReceiver btReceiver;
    private volatile boolean btReceiverRegistered = false;
    private volatile String lastPeerBtAddress = "";
    private volatile String lastPeerBtName = "";
    private volatile boolean btConnecting = false;
    private volatile boolean btFallbackActive = false;
    private volatile long lastBtDiscoveryTime = 0;

    // Transport Stream State
    private volatile TransportSocket activeSocket = null;
    private volatile TransportSocket pendingSocket = null;
    private volatile String currentTransport = "none"; // "wifi", "bluetooth", or "none"
    private volatile ServerSocket server;
    private volatile DatagramSocket udpSocket;
    private volatile boolean connected = false, closed = false, hosting = false;
    private volatile String pin = "", address = "", peer = "", state = "DISCONNECTED", callsign = Build.MODEL;
    private volatile int port = 8988;
    private long seq = 0, sent = 0, received = 0, bytesSent = 0, bytesReceived = 0, sourceSent = 0, payloadSent = 0;
    private long crcFailures = 0, fecFailures = 0, corrected = 0, duplicates = 0, unacked = 0, lastPacketBytes = 0;
    private volatile long lastReceived = now(), nextSend = 0, generation = 0;
    private final long metricsStarted = now();
    private volatile int bitrate = 2000;
    private volatile long rtt = -1;
    private int retry = 0;
    private UUID remoteSession;
    private long remoteSequence = 0;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> approvalTimer = null;
    private final WifiManager.WifiLock wifiLock;
    private final PowerManager.WakeLock wakeLock;
    private volatile String lastPeerAddress = "";
    private volatile int lastPeerPort = 8988;
    private volatile String lastPeerPin = "";
    private volatile String lastPeerCallsign = "";
    private volatile String lastPeerP2p = "";
    private volatile boolean userExplicitDisconnect = false;
    private volatile boolean p2pConnecting = false;
    private volatile ScheduledFuture<?> outgoingApprovalTimer = null;
    private volatile boolean switchingToWifi = false;

    private synchronized void startOutgoingApprovalTimer() {
        if (outgoingApprovalTimer != null) {
            outgoingApprovalTimer.cancel(false);
            outgoingApprovalTimer = null;
        }
        outgoingApprovalTimer = clock.schedule(() -> {
            synchronized (LocalTransport.this) {
                if (!connected && ("WAITING_APPROVAL".equals(state) || "CONNECTING".equals(state))) {
                    Log.w(TAG, "Outgoing connection request timed out waiting for peer response. Resetting to STANDBY.");
                    event("request_declined", "peer", peer != null ? peer : "Peer", "reason", "Connection request timed out. Peer did not respond.");
                    if (!btFallbackActive && !"bluetooth".equals(currentTransport)) {
                        triggerBluetoothFallback();
                    } else {
                        resetToStandby();
                    }
                }
            }
        }, 15, TimeUnit.SECONDS);
    }

    private boolean isMatchingPeer(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String nb = b.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return !na.isEmpty() && !nb.isEmpty() && (na.contains(nb) || nb.contains(na));
    }

    public LocalTransport(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicast = wifi.createMulticastLock("swar-discovery");
        multicast.setReferenceCounted(false);
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SWAR:MeshWakeLock") : null;
        if (wakeLock != null) wakeLock.setReferenceCounted(false);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SWAR:MeshWifiLock");
        wifiLock.setReferenceCounted(false);

        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();

        android.content.SharedPreferences prefs = context.getSharedPreferences("swar_transport_prefs", Context.MODE_PRIVATE);
        String savedP2p = prefs.getString("last_peer_p2p", "");
        if (isValidP2pAddress(savedP2p)) {
            lastPeerP2p = savedP2p;
        } else {
            lastPeerP2p = "";
            prefs.edit().remove("last_peer_p2p").apply();
        }
        lastPeerAddress = prefs.getString("last_peer_address", "");
        lastPeerCallsign = prefs.getString("last_peer_callsign", "");
        lastPeerBtAddress = prefs.getString("last_peer_bt_address", "");
        lastPeerBtName = prefs.getString("last_peer_bt_name", "");
        Log.i(TAG, "Restored peer cache: P2P=" + lastPeerP2p + ", IP=" + lastPeerAddress + ", Callsign=" + lastPeerCallsign + ", BT=" + lastPeerBtAddress + " (" + lastPeerBtName + ")");

        initWifiP2p();
        registerBtReceiver();
        startBtServer();

        clock.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        startListening();
        startUdpBeaconListener();
    }

    private void acquireLocks() {
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    static JSONObject json(Object... fields) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < fields.length; i += 2) o.put((String) fields[i], fields[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    private void event(String type, Object... fields) {
        Listener l = listener;
        if (l != null) {
            l.event(type, json(fields));
        }
    }

    private void state(String value) {
        state = value;
        Log.i(TAG, "Transport state changed -> " + value + " (peer=" + peer + ", transport=" + currentTransport + ")");
        event("connection", "state", value, "peer", peer, "transport", currentTransport);
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public Listener getListener() {
        return listener;
    }

    public boolean isConnected() { return connected; }
    public String getState() { return state; }
    public String getPeer() { return peer; }
    public String getAddress() { return address; }
    public String getCallsign() { return callsign; }
    public String getTransportType() { return currentTransport; }

    public void bitrate(int value) {
        if (value == 500 || value == 1000 || value == 2000 || value == 4000 || value == 8000 || value == 16000) {
            bitrate = value;
        }
    }

    public void callsign(String name) {
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            if (!trimmed.equals(callsign)) {
                callsign = trimmed;
                if (registration != null && nsd != null) {
                    try {
                        nsd.unregisterService(registration);
                    } catch (Exception ignored) {}
                    registration = null;
                    advertise();
                }
            }
        }
    }

    public boolean isSelfAddress(String host) {
        if (host == null || host.isEmpty() || host.equals("127.0.0.1") || host.equals("localhost")) return true;
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (host.equals(addr.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==========================================
    // Bluetooth Classic (RFCOMM) Implementation
    // ==========================================

    public String getBluetoothName() {
        try {
            if (bluetoothAdapter != null) {
                String name = bluetoothAdapter.getName();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (SecurityException ignored) {}
        return Build.MODEL;
    }

    public String getBluetoothAddress() {
        try {
            if (bluetoothAdapter == null) return "";
            String addr = bluetoothAdapter.getAddress();
            if (addr != null && !addr.equals("02:00:00:00:00:00")) return addr;

            Field mServiceField = bluetoothAdapter.getClass().getDeclaredField("mService");
            mServiceField.setAccessible(true);
            Object btManagerService = mServiceField.get(bluetoothAdapter);
            if (btManagerService != null) {
                Method getAddressMethod = btManagerService.getClass().getMethod("getAddress");
                Object res = getAddressMethod.invoke(btManagerService);
                if (res instanceof String s && !s.equals("02:00:00:00:00:00")) return s;
            }

            String secureAddr = android.provider.Settings.Secure.getString(context.getContentResolver(), "bluetooth_address");
            if (secureAddr != null && !secureAddr.isEmpty()) return secureAddr;
        } catch (Exception ignored) {}
        return "";
    }

    public void startBtServer() {
        if (closed || btServer != null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        io.execute(() -> {
            try {
                BluetoothServerSocket server = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("SWAR_Voice_Bridge", SWAR_BT_UUID);
                btServer = server;
                Log.i(TAG, "Bluetooth RFCOMM Server listening with UUID " + SWAR_BT_UUID);
                while (!closed && btServer == server) {
                    BluetoothSocket client = server.accept();
                    if (client == null) continue;
                    String remoteName = "";
                    String remoteAddr = "";
                    try {
                        if (client.getRemoteDevice() != null) {
                            remoteName = client.getRemoteDevice().getName();
                            remoteAddr = client.getRemoteDevice().getAddress();
                        }
                    } catch (Exception ignored) {}
                    Log.i(TAG, "Accepted incoming Bluetooth RFCOMM connection from " + remoteName + " (" + remoteAddr + ")");

                    if (connected && "wifi".equals(currentTransport)) {
                        Log.i(TAG, "Rejecting incoming BT connection because primary Wi-Fi is actively connected");
                        try { client.close(); } catch (Exception ignored) {}
                        continue;
                    }

                    if (pendingSocket != null) {
                        try { pendingSocket.close(); } catch (Exception ignored) {}
                        pendingSocket = null;
                    }
                    attach(new BtTransportSocket(client), true);
                }
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException in Bluetooth RFCOMM server: " + se.getMessage());
            } catch (Exception e) {
                Log.d(TAG, "Bluetooth RFCOMM server exit: " + e.getMessage());
            }
        });
    }

    public void closeBtServer() {
        BluetoothServerSocket old = btServer;
        btServer = null;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    public synchronized void registerBtReceiver() {
        if (context == null || bluetoothAdapter == null || btReceiverRegistered) return;
        try {
            if (btReceiver == null) {
                btReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handleBtIntent(intent);
                    }
                };
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(btReceiver, filter);
            }
            btReceiverRegistered = true;
            Log.i(TAG, "Bluetooth BroadcastReceiver registered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register BT receiver: " + e.getMessage());
        }
    }

    public synchronized void unregisterBtReceiver() {
        if (!btReceiverRegistered || context == null || btReceiver == null) return;
        try {
            context.unregisterReceiver(btReceiver);
            btReceiverRegistered = false;
            Log.i(TAG, "Bluetooth BroadcastReceiver unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister BT receiver: " + e.getMessage());
        }
    }

    private void handleBtIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth enabled by system -> starting RFCOMM server");
                startBtServer();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                Log.i(TAG, "Bluetooth disabled by system");
                closeBtServer();
            }
        } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            try {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev == null) return;
                String dName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (dName == null || dName.isEmpty()) dName = dev.getName();
                String dAddr = dev.getAddress();
                if (dAddr == null || dAddr.isEmpty()) return;

                boolean isTarget = (lastPeerBtAddress != null && dAddr.equalsIgnoreCase(lastPeerBtAddress)) ||
                                   (lastPeerBtName != null && !lastPeerBtName.isEmpty() && lastPeerBtName.equalsIgnoreCase(dName)) ||
                                   (lastPeerCallsign != null && !lastPeerCallsign.isEmpty() && lastPeerCallsign.equalsIgnoreCase(dName));

                boolean isPhone = isTarget || isMobilePhone(dev);
                if (!isPhone) {
                    Log.d(TAG, "Ignoring non-phone Bluetooth device: " + dName + " [" + dAddr + "]");
                    return;
                }

                Log.d(TAG, "Discovered BT mobile phone: " + dName + " [" + dAddr + "]");

                if (dName != null && !dName.isEmpty()) {
                    event("peer",
                        "name", dName,
                        "model", dName,
                        "address", "",
                        "btAddress", dAddr,
                        "source", "bluetooth",
                        "deviceType", "phone",
                        "status", "Available"
                    );
                }

                // If this is our target fallback peer and fallback is active, auto-connect!
                if (!connected && !btConnecting && !userExplicitDisconnect) {
                    if (isTarget && btFallbackActive) {
                        Log.i(TAG, "Discovered target Bluetooth peer: " + dName + " (" + dAddr + ") -> Connecting Bluetooth fallback!");
                        connectBluetooth(dAddr);
                    }
                }
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException in BT ACTION_FOUND: " + se.getMessage());
            } catch (Exception ignored) {}
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            Log.d(TAG, "Bluetooth discovery finished");
        }
    }

    public static boolean isNonPhoneName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String s = name.toLowerCase(Locale.US);
        // Audio & headphones
        if (s.contains("buds") || s.contains("earphone") || s.contains("headphone") || 
            s.contains("headset") || s.contains("earbuds") || s.contains("airpod") || 
            s.contains("airdope") || s.contains("soundbar") || s.contains("speaker") || 
            s.contains("subwoofer") || s.contains("amplifier") || s.contains("tws") || 
            s.contains("neckband") || s.contains("wireless z") || s.contains("tune ") || 
            s.contains("jbl") || s.contains("boat") || s.contains("noise") || 
            s.contains("boult") || s.contains("mivi") || s.contains("ptron") || 
            s.contains("ahuja") || s.contains("aavante") || s.contains("philips tax") ||
            s.contains("bs-311") || s.contains("coend-aio") || s.contains("audio")) {
            return true;
        }
        // Wearables / Smartwatches
        if (s.contains("watch") || s.contains("band") || s.contains("fitbit") || 
            s.contains("garmin") || s.contains("amazfit") || s.contains("strap") || 
            s.contains("tracker") || s.contains("ring")) {
            return true;
        }
        // TVs & Displays
        if (s.contains("[tv]") || s.contains("smart tv") || s.contains("bravia") || 
            s.contains("television") || s.contains("firetv") || s.contains("roku") || 
            s.contains("chromecast") || s.contains("projector") || s.contains("webos") || 
            s.contains("tizen") || s.contains("oled") || s.contains("qled")) {
            return true;
        }
        // Computers & Printers
        if (s.contains("desktop") || s.contains("laptop") || s.contains("macbook") || 
            s.contains("printer") || s.contains("deskjet") || s.contains("laserjet") || 
            s.contains("epson") || s.contains("canon") || s.contains("brother") || 
            s.contains("keyboard") || s.contains("mouse")) {
            return true;
        }
        // Appliances, Smart Home & IoT
        if (s.contains("fridge") || s.contains("refrigerator") || s.contains("washer") ||
            s.contains("dryer") || s.contains("oven") || s.contains("microwave") ||
            s.contains("cooler") || s.contains("air conditioner") || s.contains("vacuum") ||
            s.contains("camera") || s.contains("door") || s.contains("lock") ||
            s.contains("sensor") || s.contains("beacon") || s.contains("tag") ||
            s.contains("hub") || s.contains("router") || s.contains("gateway") ||
            s.contains("bridge") || s.contains("iot")) {
            return true;
        }
        return false;
    }

    public static boolean isPhoneName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String s = name.toLowerCase(Locale.US);
        if (s.startsWith("swar") || s.startsWith("linc") || s.startsWith("itantra")) return true;
        return s.contains("phone") || s.contains("mobile") || s.contains("handset") ||
               s.contains("realme") || s.contains("motorola") || s.contains("moto ") ||
               s.contains("samsung") || s.contains("galaxy") || s.contains("pixel") ||
               s.contains("redmi") || s.contains("xiaomi") || s.contains("oneplus") ||
               s.contains("iphone") || s.contains("oppo") || s.contains("vivo") ||
               s.contains("iqoo") || s.contains("poco") || s.contains("infinix") ||
               s.contains("tecno") || s.contains("honor") || s.contains("huawei") ||
               s.contains("nokia") || s.contains("xperia") || s.contains("zenfone") ||
               s.contains("c100x") || s.contains("fusion") || s.contains("edge");
    }

    public static boolean isMobilePhone(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            String name = dev.getName();
            BluetoothClass btClass = dev.getBluetoothClass();
            if (btClass != null) {
                int major = btClass.getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    major == BluetoothClass.Device.Major.WEARABLE ||
                    major == BluetoothClass.Device.Major.COMPUTER ||
                    major == BluetoothClass.Device.Major.PERIPHERAL ||
                    major == BluetoothClass.Device.Major.IMAGING ||
                    major == BluetoothClass.Device.Major.TOY ||
                    major == BluetoothClass.Device.Major.HEALTH) {
                    return false;
                }
                if (major == BluetoothClass.Device.Major.PHONE) {
                    if (isNonPhoneName(name)) return false;
                    return true;
                }
            }

            if (name == null || name.trim().isEmpty()) return false;
            if (isNonPhoneName(name)) return false;
            if (isPhoneName(name)) return true;
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean isMobilePhoneP2p(WifiP2pDevice device) {
        if (device == null) return false;
        String primaryType = device.primaryDeviceType;
        if (primaryType != null && !primaryType.isEmpty()) {
            if (primaryType.startsWith("10-") || primaryType.startsWith("10:")) {
                return true;
            }
            if (primaryType.startsWith("2-") || primaryType.startsWith("3-") ||
                primaryType.startsWith("4-") || primaryType.startsWith("5-") ||
                primaryType.startsWith("6-") || primaryType.startsWith("7-") ||
                primaryType.startsWith("8-") || primaryType.startsWith("9-") ||
                primaryType.startsWith("11-")) {
                return false;
            }
        }

        String name = device.deviceName;
        if (name == null || name.trim().isEmpty()) return false;
        if (isNonPhoneName(name)) return false;
        if (isPhoneName(name)) return true;

        return !name.startsWith("DIRECT-") && !name.contains("Print") && !name.contains("TV");
    }

    public void discoverBluetoothPeers() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        long currentTime = now();
        if (currentTime - lastBtDiscoveryTime < 12000) return;
        lastBtDiscoveryTime = currentTime;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    if (!isMobilePhone(dev)) {
                        Log.d(TAG, "Skipping non-phone bonded BT device: " + dev.getName());
                        continue;
                    }
                    String bName = dev.getName();
                    String bAddr = dev.getAddress();
                    Log.d(TAG, "Bonded BT phone: " + bName + " [" + bAddr + "]");
                    event("peer",
                        "name", bName != null ? bName : "Paired Mobile Phone",
                        "model", bName != null ? bName : "",
                        "address", "",
                        "btAddress", bAddr,
                        "source", "bluetooth",
                        "deviceType", "phone",
                        "status", "Paired"
                    );
                }
            }

            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            bluetoothAdapter.startDiscovery();
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException in discoverBluetoothPeers: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "discoverBluetoothPeers error: " + e.getMessage());
        }
    }

    public void connectBluetooth(String btAddress) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            event("error", "message", "Bluetooth is disabled or unsupported");
            return;
        }
        if (btAddress == null || btAddress.trim().isEmpty()) {
            event("error", "message", "Invalid Bluetooth address");
            return;
        }
        final String targetAddr = btAddress.trim();
        if (connected && "bluetooth".equals(currentTransport)) {
            Log.d(TAG, "Already connected via Bluetooth; skipping duplicate connect");
            return;
        }
        btConnecting = true;
        userExplicitDisconnect = false;
        state("WAITING_APPROVAL");
        startOutgoingApprovalTimer();
        Log.i(TAG, "Connecting to Bluetooth peer at " + targetAddr + " via RFCOMM...");

        io.execute(() -> {
            try {
                try {
                    if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
                } catch (Exception ignored) {}

                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(targetAddr);
                BluetoothSocket clientSocket = null;

                // Attempt 1: Insecure RFCOMM with SWAR UUID (bypasses pairing prompts)
                try {
                    clientSocket = device.createInsecureRfcommSocketToServiceRecord(SWAR_BT_UUID);
                    clientSocket.connect();
                    Log.i(TAG, "Insecure RFCOMM connection established to " + targetAddr);
                } catch (Exception e1) {
                    Log.w(TAG, "Insecure RFCOMM failed: " + e1.getMessage() + "; trying Secure RFCOMM...");
                    // Attempt 2: Secure RFCOMM with SWAR UUID
                    try {
                        clientSocket = device.createRfcommSocketToServiceRecord(SWAR_BT_UUID);
                        clientSocket.connect();
                        Log.i(TAG, "Secure RFCOMM connection established to " + targetAddr);
                    } catch (Exception e2) {
                        Log.w(TAG, "Secure RFCOMM failed: " + e2.getMessage() + "; trying reflection port 1 fallback...");
                        // Attempt 3: Standard RFCOMM channel 1 reflection fallback
                        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                        clientSocket = (BluetoothSocket) m.invoke(device, 1);
                        if (clientSocket != null) clientSocket.connect();
                        Log.i(TAG, "Reflection RFCOMM channel 1 connection established to " + targetAddr);
                    }
                }

                if (clientSocket != null && clientSocket.isConnected()) {
                    btConnecting = false;
                    btFallbackActive = false;
                    lastPeerBtAddress = targetAddr;
                    try {
                        String rName = device.getName();
                        if (rName != null && !rName.isEmpty()) lastPeerBtName = rName;
                    } catch (Exception ignored) {}
                    persistPeerInfo(lastPeerP2p, lastPeerAddress, lastPeerCallsign, lastPeerBtAddress, lastPeerBtName);
                    attach(new BtTransportSocket(clientSocket), false);
                } else {
                    throw new IOException("Failed to establish RFCOMM socket");
                }
            } catch (Exception e) {
                btConnecting = false;
                Log.w(TAG, "Bluetooth connect error to " + targetAddr + ": " + e.getMessage());
                event("error", "message", "Bluetooth connect failed: " + e.getMessage());
                lost(null, "Bluetooth connection failed: " + e.getMessage());
            }
        });
    }

    public void triggerBluetoothFallback() {
        if (connected || closed || userExplicitDisconnect) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.i(TAG, "[Fallback] Bluetooth unavailable or turned off; cannot fallback to BT");
            return;
        }
        btFallbackActive = true;
        Log.i(TAG, "[Fallback] Wi-Fi lost/failed. Initiating automatic Bluetooth Classic fallback!");
        event("notice", "message", "Wi-Fi link lost. Switching to Bluetooth fallback...");

        // 1. Direct connect to cached peer Bluetooth MAC
        if (lastPeerBtAddress != null && !lastPeerBtAddress.isEmpty()) {
            Log.i(TAG, "[Fallback] Found cached peer BT address: " + lastPeerBtAddress + " -> connecting!");
            connectBluetooth(lastPeerBtAddress);
            return;
        }

        // 2. Scan bonded devices for matching peer name / callsign
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    String bName = dev.getName();
                    if (bName != null && ((lastPeerBtName != null && bName.equalsIgnoreCase(lastPeerBtName)) ||
                                          (lastPeerCallsign != null && bName.equalsIgnoreCase(lastPeerCallsign)))) {
                        Log.i(TAG, "[Fallback] Found matching bonded BT peer: " + bName + " (" + dev.getAddress() + ")");
                        lastPeerBtAddress = dev.getAddress();
                        connectBluetooth(dev.getAddress());
                        return;
                    }
                }
            }
        } catch (SecurityException ignored) {}

        // 3. Otherwise start discovery to locate peer over Bluetooth
        Log.i(TAG, "[Fallback] Starting Bluetooth inquiry scan to locate peer...");
        discoverBluetoothPeers();
    }

    // ==========================================
    // WiFi Direct (P2P) Implementation
    // ==========================================

    private void initWifiP2p() {
        try {
            if (context != null) {
                p2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
                if (p2pManager != null) {
                    p2pChannel = p2pManager.initialize(context, context.getMainLooper(), () -> {
                        Log.w(TAG, "WiFi P2P Channel disconnected; re-initializing...");
                        try {
                            p2pChannel = p2pManager.initialize(context, context.getMainLooper(), null);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to reinitialize P2P channel: " + e.getMessage());
                        }
                    });
                    Log.i(TAG, "WiFi P2P initialized successfully");
                    if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            p2pManager.requestDeviceInfo(p2pChannel, device -> {
                                if (device != null && device.deviceAddress != null) {
                                    myP2pAddress = device.deviceAddress;
                                    Log.i(TAG, "Self P2P device initialized: " + myP2pAddress);
                                    event("self_p2p", "p2pAddress", device.deviceAddress, "name", device.deviceName != null ? device.deviceName : "");
                                }
                            });
                        } catch (Exception ignored) {}
                    }
                } else {
                    Log.w(TAG, "WifiP2pManager is null; WiFi Direct not supported on this device");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize WiFi Direct: " + e.getMessage());
        }
    }

    public String getMyP2pAddress() {
        return myP2pAddress;
    }

    public void requestSelfP2pInfo() {
        if (myP2pAddress != null && !myP2pAddress.isEmpty()) {
            event("self_p2p", "p2pAddress", myP2pAddress);
        } else if (Build.VERSION.SDK_INT >= 29 && p2pManager != null && p2pChannel != null) {
            try {
                p2pManager.requestDeviceInfo(p2pChannel, device -> {
                    if (device != null && device.deviceAddress != null) {
                        myP2pAddress = device.deviceAddress;
                        Log.i(TAG, "Self P2P device initialized via request: " + myP2pAddress);
                        event("self_p2p", "p2pAddress", device.deviceAddress, "name", device.deviceName != null ? device.deviceName : "");
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    public synchronized void registerP2pReceiver() {
        if (context == null || p2pManager == null || p2pChannel == null || p2pReceiverRegistered) return;
        try {
            if (p2pReceiver == null) {
                p2pReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handleP2pIntent(intent);
                    }
                };
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(p2pReceiver, filter);
            }
            p2pReceiverRegistered = true;
            Log.i(TAG, "WiFi P2P BroadcastReceiver registered");
            discoverP2pPeers();
        } catch (Exception e) {
            Log.w(TAG, "Failed to register P2P receiver: " + e.getMessage());
        }
    }

    public synchronized void unregisterP2pReceiver() {
        if (!p2pReceiverRegistered || context == null || p2pReceiver == null) return;
        try {
            context.unregisterReceiver(p2pReceiver);
            p2pReceiverRegistered = false;
            Log.i(TAG, "WiFi P2P BroadcastReceiver unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister P2P receiver: " + e.getMessage());
        }
    }

    private void handleP2pIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                p2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                Log.i(TAG, "WiFi P2P state changed: " + (p2pEnabled ? "ENABLED" : "DISABLED"));
                if (!p2pEnabled) {
                    p2pDiscovering = false;
                }
            }

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (p2pManager != null && p2pChannel != null) {
                    try {
                        p2pManager.requestPeers(p2pChannel, peers -> {
                            if (peers == null) return;
                            Collection<WifiP2pDevice> deviceList = peers.getDeviceList();
                            Log.i(TAG, "WiFi P2P peers discovered count: " + deviceList.size());
                            for (WifiP2pDevice device : deviceList) {
                                String p2pAddr = device.deviceAddress;
                                String devName = device.deviceName;
                                if (p2pAddr == null) continue;
                                if (myP2pAddress != null && p2pAddr.equalsIgnoreCase(myP2pAddress)) continue;

                                if (device.status == WifiP2pDevice.UNAVAILABLE || device.status == WifiP2pDevice.FAILED) {
                                    event("peerLost", "name", devName != null ? devName : "", "p2pAddress", p2pAddr);
                                    continue;
                                }

                                if (!isMobilePhoneP2p(device)) {
                                    Log.d(TAG, "Ignoring non-phone WiFi Direct device: " + devName + " (" + p2pAddr + ")");
                                    continue;
                                }

                                Log.i(TAG, "P2P mobile phone found: " + devName + " (" + p2pAddr + ") status=" + p2pDeviceStatus(device.status));
                                event("peer",
                                    "name", devName != null && !devName.isEmpty() ? devName : "WiFi Direct Phone",
                                    "model", devName != null ? devName : "",
                                    "address", "",
                                    "p2pAddress", p2pAddr,
                                    "port", 8988,
                                    "source", "p2p",
                                    "deviceType", "phone",
                                    "status", p2pDeviceStatus(device.status)
                                );

                                // Auto-reconnect to known P2P peer if disconnected
                                if (!connected && !p2pConnecting && !userExplicitDisconnect && lastPeerP2p != null && !lastPeerP2p.isEmpty()) {
                                    if (p2pAddr.equalsIgnoreCase(lastPeerP2p) && device.status == WifiP2pDevice.AVAILABLE) {
                                        Log.i(TAG, "Known P2P peer available: " + devName + " -> Auto-reconnecting P2P!");
                                        connectP2p(lastPeerP2p);
                                    }
                                }

                                // If currently on Bluetooth fallback, and this peer is detected on Wi-Fi Direct: upgrade to Wi-Fi Direct!
                                if (connected && "bluetooth".equals(currentTransport) && !p2pConnecting && !switchingToWifi) {
                                    boolean matchesPeer = (lastPeerP2p != null && p2pAddr.equalsIgnoreCase(lastPeerP2p)) ||
                                                          (peer != null && !peer.isEmpty() && devName != null && isMatchingPeer(devName, peer)) ||
                                                          (lastPeerCallsign != null && !lastPeerCallsign.isEmpty() && devName != null && isMatchingPeer(devName, lastPeerCallsign));
                                    if (matchesPeer && device.status == WifiP2pDevice.AVAILABLE) {
                                        Log.i(TAG, "[Wi-Fi Recovery] Connected peer detected on Wi-Fi Direct (" + devName + ")! Upgrading from Bluetooth fallback to Wi-Fi Direct...");
                                        switchingToWifi = true;
                                        connectP2p(p2pAddr);
                                    }
                                }
                            }
                        });
                    } catch (SecurityException se) {
                        Log.w(TAG, "SecurityException requesting P2P peers: " + se.getMessage());
                    } catch (Exception e) {
                        Log.w(TAG, "Error requesting P2P peers: " + e.getMessage());
                    }
                }
            }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                boolean isP2pConn = networkInfo != null && networkInfo.isConnected();
                Log.i(TAG, "WiFi P2P connection changed: " + (isP2pConn ? "CONNECTED" : "DISCONNECTED"));
                if (isP2pConn && p2pManager != null && p2pChannel != null) {
                    p2pConnecting = false;
                    try {
                        p2pManager.requestConnectionInfo(p2pChannel, info -> {
                            if (info == null) return;
                            p2pGroupConnected = info.groupFormed;
                            p2pIsGroupOwner = info.isGroupOwner;
                            InetAddress goInet = info.groupOwnerAddress;
                            p2pGroupOwnerAddress = goInet != null ? goInet.getHostAddress() : null;

                            Log.i(TAG, "P2P Group Formed! isGroupOwner=" + p2pIsGroupOwner + ", GO Address=" + p2pGroupOwnerAddress);
                            event("p2p_connection",
                                "groupFormed", info.groupFormed,
                                "isGroupOwner", info.isGroupOwner,
                                "groupOwnerAddress", p2pGroupOwnerAddress != null ? p2pGroupOwnerAddress : ""
                            );

                            if (info.groupFormed) {
                                if (p2pIsGroupOwner) {
                                    Log.i(TAG, "Acting as P2P Group Owner; listening on port 8988 for incoming client TCP");
                                    startListening();
                                } else {
                                    if (p2pGroupOwnerAddress != null && !p2pGroupOwnerAddress.isEmpty()) {
                                        Log.i(TAG, "Acting as P2P Client; connecting to GO at " + p2pGroupOwnerAddress + ":8988");
                                        address = p2pGroupOwnerAddress;
                                        lastPeerAddress = p2pGroupOwnerAddress;
                                        retryP2pClientConnect(p2pGroupOwnerAddress, 0);
                                    }
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Error requesting P2P connection info: " + e.getMessage());
                    }
                } else if (!isP2pConn) {
                    p2pGroupConnected = false;
                    p2pIsGroupOwner = false;
                    p2pGroupOwnerAddress = null;
                    Log.i(TAG, "WiFi P2P disconnected" + (p2pConnecting ? " (connection attempt still in progress)" : " — restarting peer discovery"));
                    if (!p2pConnecting) {
                        p2pDiscovering = false;
                        lastP2pDiscoveryTime = 0;
                        if (!closed && !userExplicitDisconnect) {
                            discoverP2pPeers();
                        }
                    }
                }
            }

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                WifiP2pDevice thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (thisDevice != null && thisDevice.deviceAddress != null) {
                    myP2pAddress = thisDevice.deviceAddress;
                    Log.i(TAG, "This device P2P address: " + thisDevice.deviceAddress + " (" + thisDevice.deviceName + ")");
                    event("self_p2p", "p2pAddress", thisDevice.deviceAddress, "name", thisDevice.deviceName != null ? thisDevice.deviceName : "");
                }
            }
        }
    }

    public void discoverP2pPeers() {
        if (p2pManager == null || p2pChannel == null) return;
        long currentTime = now();
        if (p2pDiscovering || (currentTime - lastP2pDiscoveryTime < 15000)) return;
        lastP2pDiscoveryTime = currentTime;
        try {
            p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    p2pDiscovering = true;
                    Log.i(TAG, "WiFi P2P peer discovery active");
                    clock.schedule(() -> { p2pDiscovering = false; }, 12, TimeUnit.SECONDS);
                }

                @Override
                public void onFailure(int reason) {
                    p2pDiscovering = false;
                    Log.w(TAG, "WiFi P2P peer discovery failed: " + p2pFailureReason(reason));
                }
            });
        } catch (SecurityException se) {
            p2pDiscovering = false;
            Log.w(TAG, "SecurityException on discoverP2pPeers: " + se.getMessage());
        } catch (Exception e) {
            p2pDiscovering = false;
            Log.w(TAG, "Error on discoverP2pPeers: " + e.getMessage());
        }
    }

    public void stopP2pDiscovery() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.stopPeerDiscovery(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { p2pDiscovering = false; }
                @Override public void onFailure(int reason) {}
            });
        } catch (Exception ignored) {}
    }

    public void clearPersistentGroups() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            Class<?> listenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager$PersistentGroupInfoListener");
            Method requestPersistentGroupInfo = WifiP2pManager.class.getMethod("requestPersistentGroupInfo",
                WifiP2pManager.Channel.class, listenerClass);
            Method deletePersistentGroup = WifiP2pManager.class.getMethod("deletePersistentGroup",
                WifiP2pManager.Channel.class, int.class, WifiP2pManager.ActionListener.class);

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxyObj, method, args) -> {
                    if ("onPersistentGroupInfoAvailable".equals(method.getName()) && args != null && args.length > 0 && args[0] != null) {
                        try {
                            Object groupListObj = args[0];
                            Method getGroupList = groupListObj.getClass().getMethod("getGroupList");
                            @SuppressWarnings("unchecked")
                            Collection<WifiP2pGroup> list = (Collection<WifiP2pGroup>) getGroupList.invoke(groupListObj);
                            if (list != null) {
                                for (WifiP2pGroup g : list) {
                                    try {
                                        deletePersistentGroup.invoke(p2pManager, p2pChannel, g.getNetworkId(), new WifiP2pManager.ActionListener() {
                                            @Override public void onSuccess() { Log.i(TAG, "Cleared stale persistent group: netId=" + g.getNetworkId()); }
                                            @Override public void onFailure(int r) {}
                                        });
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    return null;
                }
            );

            requestPersistentGroupInfo.invoke(p2pManager, p2pChannel, proxy);
        } catch (Exception e) {
            Log.d(TAG, "Persistent groups cleanup reflection: " + e.getMessage());
        }
    }

    public void createP2pGroup() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WiFi Direct autonomous group created successfully");
                }
                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "WiFi Direct createGroup failed: " + p2pFailureReason(reason));
                }
            });
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException on createGroup: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Exception on createGroup: " + e.getMessage());
        }
    }

    public static boolean isValidP2pAddress(String addr) {
        return addr != null && addr.length() == 17 && addr.contains(":") && !addr.startsWith("02:00:00");
    }

    public void persistPeerInfo(String p2p, String addr, String callsign, String btAddr, String btName) {
        try {
            android.content.SharedPreferences.Editor ed = context.getSharedPreferences("swar_transport_prefs", Context.MODE_PRIVATE).edit();
            if (isValidP2pAddress(p2p)) {
                lastPeerP2p = p2p;
                ed.putString("last_peer_p2p", p2p);
            }
            if (addr != null && !addr.isEmpty() && !addr.startsWith("127.") && !addr.equals("0.0.0.0")) {
                lastPeerAddress = addr;
                ed.putString("last_peer_address", addr);
            }
            if (callsign != null && !callsign.isEmpty()) {
                lastPeerCallsign = callsign;
                ed.putString("last_peer_callsign", callsign);
            }
            if (btAddr != null && !btAddr.isEmpty() && !btAddr.equals("02:00:00:00:00:00")) {
                lastPeerBtAddress = btAddr;
                ed.putString("last_peer_bt_address", btAddr);
            }
            if (btName != null && !btName.isEmpty()) {
                lastPeerBtName = btName;
                ed.putString("last_peer_bt_name", btName);
            }
            ed.apply();
        } catch (Exception ignored) {}
    }

    public void persistPeerInfo(String p2p, String addr, String callsign) {
        persistPeerInfo(p2p, addr, callsign, lastPeerBtAddress, lastPeerBtName);
    }

    public void connectP2p(String p2pAddress) {
        if (!isValidP2pAddress(p2pAddress)) {
            Log.w(TAG, "Refusing connect to invalid P2P address: " + p2pAddress);
            return;
        }
        connectP2pInternal(p2pAddress, 0);
    }

    private void connectP2pInternal(String p2pAddress, int attempt) {
        if (p2pManager == null || p2pChannel == null || p2pAddress == null || p2pAddress.trim().isEmpty()) {
            event("error", "message", "WiFi Direct is unavailable or invalid address.");
            return;
        }
        if (connected && "wifi".equals(currentTransport)) {
            Log.d(TAG, "Already connected; skipping duplicate connectP2p");
            return;
        }
        p2pConnecting = true;
        lastPeerP2p = p2pAddress.trim();
        persistPeerInfo(lastPeerP2p, null, null);
        userExplicitDisconnect = false;

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = p2pAddress.trim();
        config.wps.setup = WpsInfo.PBC;

        state("WAITING_APPROVAL");
        Log.i(TAG, "Connecting via WiFi Direct to " + p2pAddress + " (attempt " + (attempt + 1) + ")");

        doP2pConnect(config, p2pAddress, attempt);
    }

    private void doP2pConnect(WifiP2pConfig config, String p2pAddress, int attempt) {
        if (connected || closed) return;
        try {
            p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WiFi P2P connect initiated successfully to " + p2pAddress);
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "WiFi P2P connect failed (attempt " + (attempt + 1) + "): " + p2pFailureReason(reason));
                    if (reason == WifiP2pManager.BUSY && attempt < 5 && !connected && !closed) {
                        Log.i(TAG, "WiFi Direct framework busy: retrying connect #" + (attempt + 2) + " in 500ms");
                        clock.schedule(() -> doP2pConnect(config, p2pAddress, attempt + 1), 500, TimeUnit.MILLISECONDS);
                    } else if (reason == WifiP2pManager.ERROR && attempt < 3 && !connected && !closed) {
                        Log.i(TAG, "WiFi Direct peer not fresh in scan cache: rediscovering and retrying #" + (attempt + 2) + " in 1000ms");
                        discoverP2pPeers();
                        clock.schedule(() -> doP2pConnect(config, p2pAddress, attempt + 1), 1000, TimeUnit.MILLISECONDS);
                    } else {
                        p2pConnecting = false;
                        event("error", "message", "WiFi Direct connect failed: " + p2pFailureReason(reason));
                        state("DISCONNECTED");
                        // Fallback to Bluetooth if P2P cannot establish
                        triggerBluetoothFallback();
                    }
                }
            });
        } catch (SecurityException se) {
            p2pConnecting = false;
            Log.w(TAG, "SecurityException on connectP2p: " + se.getMessage());
            event("error", "message", "WiFi Direct permission missing: " + se.getMessage());
            state("DISCONNECTED");
            triggerBluetoothFallback();
        } catch (Exception e) {
            p2pConnecting = false;
            Log.w(TAG, "Exception on connectP2p: " + e.getMessage());
            event("error", "message", "WiFi Direct error: " + e.getMessage());
            state("DISCONNECTED");
            triggerBluetoothFallback();
        }
    }

    public void disconnectP2pGroup() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.i(TAG, "WiFi P2P group removed"); }
                @Override public void onFailure(int reason) {}
            });
            p2pManager.cancelConnect(p2pChannel, null);
        } catch (Exception ignored) {}
    }

    private void retryP2pClientConnect(String goAddress, int attempt) {
        if (connected || closed || p2pIsGroupOwner) return;
        io.execute(() -> {
            try {
                Log.i(TAG, "P2P Client connect attempt #" + (attempt + 1) + " to GO " + goAddress + ":8988");
                Socket next = new Socket();
                next.connect(new InetSocketAddress(goAddress, 8988), 3000);
                attach(new TcpTransportSocket(next), false);
            } catch (Exception e) {
                Log.w(TAG, "P2P Client connect attempt #" + (attempt + 1) + " failed: " + e.getMessage());
                if (attempt < 15 && !connected && !closed && p2pGroupConnected && !p2pIsGroupOwner) {
                    clock.schedule(() -> retryP2pClientConnect(goAddress, attempt + 1), 1000, TimeUnit.MILLISECONDS);
                } else if (!connected) {
                    lost(null, "Failed to connect to P2P Group Owner: " + e.getMessage());
                }
            }
        });
    }

    private static String p2pDeviceStatus(int status) {
        return switch (status) {
            case WifiP2pDevice.AVAILABLE -> "Available";
            case WifiP2pDevice.INVITED -> "Invited";
            case WifiP2pDevice.CONNECTED -> "Connected";
            case WifiP2pDevice.FAILED -> "Failed";
            case WifiP2pDevice.UNAVAILABLE -> "Unavailable";
            default -> "Unknown";
        };
    }

    private static String p2pFailureReason(int reason) {
        return switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported on this device";
            case WifiP2pManager.ERROR -> "Internal framework error";
            case WifiP2pManager.BUSY -> "Framework busy";
            default -> "Code " + reason;
        };
    }

    public void startListening() {
        if (closed || server != null) return;
        io.execute(() -> {
            if (server != null) return;
            try {
                ServerSocket localServer = new ServerSocket();
                localServer.setReuseAddress(true);
                localServer.bind(new InetSocketAddress(8988));
                server = localServer;
                Log.i(TAG, "TCP Server listening on port 8988");
                advertise();
                while (!closed && server == localServer) {
                    Socket incoming = localServer.accept();
                    if (connected) {
                        String incomingIp = incoming.getInetAddress() != null ? incoming.getInetAddress().getHostAddress() : "";
                        String currentPeerIp = (activeSocket != null) ? activeSocket.getRemoteAddress() : "";
                        if (incomingIp.equals(currentPeerIp)) {
                            Log.i(TAG, "Replacing stale connection with new incoming connection from " + incomingIp);
                            try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
                            activeSocket = null;
                            connected = false;
                        } else {
                            try {
                                DataOutputStream out = new DataOutputStream(incoming.getOutputStream());
                                JSONObject busy = json("type", "REJECT", "reason", "Device is busy in another session");
                                FrameIO.write(out, (byte) 1, busy.toString().getBytes(StandardCharsets.UTF_8));
                                incoming.close();
                            } catch (Exception ignored) {}
                            continue;
                        }
                    }

                    // Simultaneous connect tie-breaker
                    if (activeSocket != null && !connected) {
                        String inIp = incoming.getInetAddress() != null ? incoming.getInetAddress().getHostAddress() : "";
                        String localIp = incoming.getLocalAddress() != null ? incoming.getLocalAddress().getHostAddress() : "";
                        boolean yieldToIncoming = localIp.compareTo(inIp) < 0;
                        if (yieldToIncoming) {
                            Log.i(TAG, "Simultaneous connect tie-breaker: yielding outgoing in favor of incoming from " + inIp);
                            try { activeSocket.close(); } catch (Exception ignored) {}
                            activeSocket = null;
                        } else {
                            Log.i(TAG, "Simultaneous connect tie-breaker: keeping outgoing to " + address + ", rejecting incoming from " + inIp);
                            try { incoming.close(); } catch (Exception ignored) {}
                            continue;
                        }
                    }

                    if (pendingSocket != null) {
                        try { pendingSocket.close(); } catch (Exception ignored) {}
                        pendingSocket = null;
                    }
                    attach(new TcpTransportSocket(incoming), true);
                }
            } catch (Exception e) {
                Log.w(TAG, "ServerSocket error: " + e.getMessage());
            }
        });
    }

    private void startUdpBeaconListener() {
        io.execute(() -> {
            try {
                DatagramSocket udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(8989));
                udpSocket = udp;
                Log.i(TAG, "UDP Beacon listening on port 8989");
                byte[] buf = new byte[1024];
                while (!closed && udpSocket == udp) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udp.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("SWAR_BEACON:") || msg.startsWith("LINC_BEACON:")) {
                        String[] parts = msg.split(":");
                        if (parts.length >= 4) {
                            String beaconSession = parts[2];
                            String beaconCallsign = parts[3];
                            String beaconModel = parts.length >= 5 ? parts[4] : "";
                            String beaconP2p = parts.length >= 6 ? parts[5] : "";
                            String senderIp = packet.getAddress() != null ? packet.getAddress().getHostAddress() : "";
                            if (!beaconP2p.isEmpty()) lastPeerP2p = beaconP2p;
                            if (!session.toString().equals(beaconSession) && !isSelfAddress(senderIp)) {
                                Log.i(TAG, "Discovered SWAR peer via UDP beacon: " + beaconCallsign + " (" + senderIp + ")" + (!beaconP2p.isEmpty() ? " [p2p:" + beaconP2p + "]" : ""));
                                if (!beaconP2p.isEmpty()) {
                                    event("peer", "name", beaconCallsign, "model", beaconModel, "address", senderIp, "p2pAddress", beaconP2p, "port", 8988, "source", "beacon");
                                } else {
                                    event("peer", "name", beaconCallsign, "model", beaconModel, "address", senderIp, "port", 8988, "source", "beacon");
                                }

                                if (!connected && !userExplicitDisconnect && (!lastPeerAddress.isEmpty() || !lastPeerCallsign.isEmpty())) {
                                    if (senderIp.equals(lastPeerAddress) || (!lastPeerCallsign.isEmpty() && beaconCallsign.equals(lastPeerCallsign))) {
                                        Log.i(TAG, "Known peer reappeared on radar: " + beaconCallsign + " (" + senderIp + ") -> Instant Reconnect!");
                                        lastPeerAddress = senderIp;
                                        address = senderIp;
                                        port = 8988;
                                        connectInternal();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "UDP Beacon listener exited: " + e.getMessage());
            }
        });
    }

    private void broadcastUdpBeacon() {
        if (connected) return;
        io.execute(() -> {
            try {
                String p2pSuffix = (myP2pAddress != null && !myP2pAddress.isEmpty()) ? ":" + myP2pAddress : "";
                String payloadStr = "SWAR_BEACON:8988:" + session.toString() + ":" + callsign + ":" + Build.MODEL + p2pSuffix;
                byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
                DatagramSocket sender = new DatagramSocket();
                sender.setBroadcast(true);

                DatagramPacket p1 = new DatagramPacket(payload, payload.length, InetAddress.getByName("255.255.255.255"), 8989);
                sender.send(p1);

                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                        InetAddress bcast = ifaceAddr.getBroadcast();
                        if (bcast != null) {
                            sender.send(new DatagramPacket(payload, payload.length, bcast, 8989));
                        }
                    }
                }
                sender.close();
            } catch (Exception ignored) {}
        });
    }

    public void host() {
        io.execute(() -> {
            if (server != null) { event("host", "pin", pin, "addresses", addresses(), "port", 8988); return; }
            disconnect();
            userExplicitDisconnect = false;
            hosting = true;
            pin = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            startListening();
            state("DISCOVERING");
            if (addresses().length() == 0 && p2pManager != null && p2pChannel != null) {
                createP2pGroup();
            }
            event("host", "pin", pin, "addresses", addresses(), "port", 8988);
        });
    }

    public void connect(String host, int requestedPort, String code, String clientCallsign) {
        if (!host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}") || requestedPort < 1 || requestedPort > 65535) {
            event("error", "message", "Enter a valid local IPv4 address.");
            return;
        }
        if (isSelfAddress(host)) {
            Log.d(TAG, "Ignoring connect to self address: " + host);
            return;
        }
        try {
            InetAddress ip = InetAddress.getByName(host);
            if (!ip.isSiteLocalAddress() && !ip.isLinkLocalAddress()) {
                event("error", "message", "Only local-network addresses are accepted.");
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (clientCallsign != null && !clientCallsign.trim().isEmpty()) callsign = clientCallsign.trim();
        disconnect();
        userExplicitDisconnect = false;
        hosting = false;
        address = host;
        port = requestedPort;
        pin = code != null ? code : "";
        retry = 0;
        connectInternal();
    }

    public void connect(String host, int requestedPort, String code) {
        connect(host, requestedPort, code, callsign);
    }

    private void connectInternal() {
        long cycle = generation;
        io.execute(() -> {
            if (closed || hosting || connected || address.isEmpty() || cycle != generation) return;
            state(retry == 0 ? "WAITING_APPROVAL" : "RECONNECTING");
            startOutgoingApprovalTimer();
            Socket next = new Socket();
            try {
                Log.i(TAG, "Connecting to peer at " + address + ":" + port + "...");
                next.connect(new InetSocketAddress(address, port), 4000);
                if (cycle != generation) {
                    next.close();
                    return;
                }
                attach(new TcpTransportSocket(next), false);
            } catch (Exception e) {
                try { next.close(); } catch (Exception ignored) {}
                if (cycle == generation) lost(null, e.getMessage());
            }
        });
    }

    private void attach(TransportSocket next, boolean accepted) throws IOException {
        activeSocket = next;
        connected = false;
        lastReceived = now();
        remoteSession = null;
        remoteSequence = 0;
        currentTransport = next.getTransportType();
        state(accepted ? "CONNECTING" : "WAITING_APPROVAL");
        if (!accepted) startOutgoingApprovalTimer();

        if (!accepted) {
            String myBtName = getBluetoothName();
            String myBtAddr = getBluetoothAddress();
            control(json("type", "HELLO", "protocol", 1, "session", session.toString(),
                "name", Build.MODEL, "callsign", callsign, "pin", pin,
                "codec", "utf8-deflate-hamming84",
                "p2pAddress", isValidP2pAddress(myP2pAddress) ? myP2pAddress : "",
                "btName", myBtName, "btAddress", myBtAddr,
                "transport", currentTransport,
                "languages", "hi,en,hinglish-experimental"));
        }

        io.execute(() -> {
            try {
                DataInputStream input = new DataInputStream(next.getInputStream());
                while (!closed && (activeSocket == next || pendingSocket == next)) {
                    byte[] body = FrameIO.read(input);
                    int length = body.length;
                    synchronized (this) { bytesReceived += length + 4; }
                    lastReceived = now();
                    if (body[0] == 1) {
                        onControl(new JSONObject(new String(body, 1, length - 1, StandardCharsets.UTF_8)), accepted);
                    } else if (body[0] == 2) {
                        if (!connected) throw new IOException("Data before handshake");
                        onPacket(Arrays.copyOfRange(body, 1, length));
                    } else throw new IOException("Unknown frame kind");
                }
            } catch (Exception e) {
                lost(next, e.getMessage());
            }
        });
    }

    private void onControl(JSONObject data, boolean accepted) throws Exception {
        String type = data.optString("type");
        if (!connected) {
            if (accepted && type.equals("HELLO")) {
                if (data.optInt("protocol") != 1 || !data.optString("codec").equals("utf8-deflate-hamming84")) {
                    control(json("type", "REJECT", "reason", "Protocol or codec mismatch"));
                    throw new IOException("Protocol mismatch");
                }
                remoteSession = UUID.fromString(data.getString("session"));
                String reqName = data.optString("name", "Nearby Phone");
                String reqCallsign = data.optString("callsign", reqName);
                String incomingP2p = data.optString("p2pAddress", "");
                String incomingBtName = data.optString("btName", "");
                String incomingBtAddr = data.optString("btAddress", "");
                if (isValidP2pAddress(incomingP2p)) {
                    lastPeerP2p = incomingP2p;
                }
                if (!incomingBtAddr.isEmpty()) lastPeerBtAddress = incomingBtAddr;
                if (!incomingBtName.isEmpty()) lastPeerBtName = incomingBtName;
                persistPeerInfo(lastPeerP2p, lastPeerAddress, reqCallsign, lastPeerBtAddress, lastPeerBtName);

                peer = reqCallsign;
                pendingSocket = activeSocket;
                String incomingPin = data.optString("pin", "");
                if (!pin.isEmpty() && !pin.equals(incomingPin)) {
                    control(json("type", "REJECT", "reason", "Incorrect PIN"));
                    throw new IOException("Incorrect PIN");
                }

                String clientAddr = (activeSocket != null) ? activeSocket.getRemoteAddress() : address;
                state("PENDING_APPROVAL");
                Log.i(TAG, "Incoming connection request from " + reqCallsign + " (" + clientAddr + "). Showing Accept/Decline modal on receiver.");
                event("connection_request", "peer", reqCallsign, "name", reqName, "callsign", reqCallsign, "address", clientAddr, "pin", incomingPin, "transport", currentTransport);
                if (approvalTimer != null) approvalTimer.cancel(false);
                approvalTimer = clock.schedule(() -> {
                    if (pendingSocket != null && !connected) {
                        Log.i(TAG, "Incoming request from " + reqCallsign + " timed out after 30s. Declining.");
                        respondRequest(false);
                    }
                }, 30, TimeUnit.SECONDS);
                return;
            }
            if (!accepted && type.equals("READY") && data.optInt("protocol") == 1 && data.optString("codec").equals("utf8-deflate-hamming84")) {
                remoteSession = UUID.fromString(data.getString("session"));
                peer = data.optString("callsign", data.optString("name", "Phone"));
                String peerP2p = data.optString("p2pAddress", "");
                if (isValidP2pAddress(peerP2p)) {
                    lastPeerP2p = peerP2p;
                }
                String peerBtName = data.optString("btName", "");
                String peerBtAddr = data.optString("btAddress", "");
                if (!peerBtAddr.isEmpty()) lastPeerBtAddress = peerBtAddr;
                if (!peerBtName.isEmpty()) lastPeerBtName = peerBtName;
                persistPeerInfo(lastPeerP2p, lastPeerAddress, peer, lastPeerBtAddress, lastPeerBtName);

                Log.i(TAG, "Connection READY received from peer (" + currentTransport + "): " + peer);
                ready();
                return;
            }
            if (!accepted && type.equals("REJECT")) {
                String reason = data.optString("reason", "Connection request was declined by " + peer);
                event("request_declined", "peer", peer, "reason", reason);
                retry = 99;
                userExplicitDisconnect = true;
                address = "";
                resetToStandby();
                return;
            }
            if (accepted && type.equals("CANCEL")) {
                if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
                event("request_cancelled", "peer", peer);
                resetToStandby();
                return;
            }
            throw new IOException(type.equals("REJECT") ? "Connection rejected" : "Invalid handshake");
        }
        switch (type) {
            case "PING" -> control(json("type", "PONG", "at", data.getLong("at")));
            case "PONG" -> rtt = Math.max(0, now() - data.getLong("at"));
            case "ACK" -> {
                long sequence = data.getLong("seq");
                Long start = pending.remove(sequence);
                if (start != null) {
                    event("delivery", "sequence", sequence, "state", "Decoded by peer", "ackMs", now() - start);
                }
            }
            case "BYE" -> {
                Log.i(TAG, "Remote peer explicitly disconnected (" + currentTransport + "): " + peer);
                userExplicitDisconnect = true;
                retry = 99;
                address = "";
                event("notice", "message", (peer.isEmpty() ? "Peer" : peer) + " disconnected the link.");
                disconnectP2pGroup();
                resetToStandby();
                return;
            }
            default -> throw new IOException("Unknown control message");
        }
    }

    public void respondRequest(boolean accept) {
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        TransportSocket s = pendingSocket != null ? pendingSocket : activeSocket;
        if (s == null) return;
        io.execute(() -> {
            try {
                if (accept) {
                    activeSocket = s;
                    pendingSocket = null;
                    String myBtName = getBluetoothName();
                    String myBtAddr = getBluetoothAddress();
                    control(json("type", "READY", "protocol", 1, "session", session.toString(),
                        "name", Build.MODEL, "callsign", callsign,
                        "p2pAddress", isValidP2pAddress(myP2pAddress) ? myP2pAddress : "",
                        "btName", myBtName, "btAddress", myBtAddr,
                        "transport", currentTransport,
                        "codec", "utf8-deflate-hamming84"));
                    ready();
                } else {
                    activeSocket = s;
                    try {
                        control(json("type", "REJECT", "reason", "Connection declined by " + (callsign.isEmpty() ? Build.MODEL : callsign)));
                    } catch (Exception ignored) {}
                    pendingSocket = null;
                    activeSocket = null;
                    retry = 99;
                    userExplicitDisconnect = true;
                    resetToStandby();
                }
            } catch (Exception e) {
                lost(s, e.getMessage());
            }
        });
    }

    public void cancelRequest() {
        io.execute(() -> {
            try {
                if (activeSocket != null) control(json("type", "CANCEL"));
            } catch (Exception ignored) {}
            resetToStandby();
        });
    }

    private void ready() {
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        if (outgoingApprovalTimer != null) { outgoingApprovalTimer.cancel(false); outgoingApprovalTimer = null; }
        lastReceived = now();
        connected = true;
        retry = 0;
        btConnecting = false;
        userExplicitDisconnect = false;
        String prevTransport = currentTransport;
        currentTransport = (activeSocket != null) ? activeSocket.getTransportType() : "wifi";

        if ("wifi".equals(currentTransport) && btFallbackActive) {
            Log.i(TAG, "[Switch] Wi-Fi Direct connection established! Upgrading from Bluetooth fallback to Wi-Fi Direct.");
            btFallbackActive = false;
            switchingToWifi = false;
            event("notice", "message", "Wi-Fi Direct detected! Upgraded from Bluetooth to Wi-Fi Direct.");
        } else {
            btFallbackActive = "bluetooth".equals(currentTransport);
        }

        if (activeSocket != null && "wifi".equals(currentTransport)) {
            String remoteIp = activeSocket.getRemoteAddress();
            if (remoteIp != null && !remoteIp.isEmpty()) {
                lastPeerAddress = remoteIp;
                address = remoteIp;
            }
        }
        if (port > 0) lastPeerPort = port;
        if (pin != null) lastPeerPin = pin;
        if (peer != null) lastPeerCallsign = peer;

        acquireLocks();
        persistPeerInfo(lastPeerP2p, lastPeerAddress, lastPeerCallsign, lastPeerBtAddress, lastPeerBtName);
        state("CONNECTED");
        flushOutbox();
    }

    private void flushOutbox() {
        String[] msg;
        while ((msg = outbox.poll()) != null) {
            Log.i(TAG, "Flushing queued speech to peer (" + currentTransport + "): '" + msg[0] + "' (" + msg[1] + ")");
            sendTextInternal(msg[0], msg[1]);
        }
    }

    private void onPacket(byte[] wire) throws IOException {
        ItpPacket.Decoded message;
        try {
            message = ItpPacket.decode(wire);
        } catch (IOException e) {
            synchronized (this) {
                if (e.getMessage() != null && e.getMessage().contains("CRC")) crcFailures++;
                else fecFailures++;
            }
            event("error", "message", "Packet rejected: " + e.getMessage());
            return;
        }
        if (!message.session().equals(remoteSession)) throw new IOException("Session mismatch");
        String key = message.session() + ":" + message.sequence();
        synchronized (this) {
            if (seen.contains(key)) {
                duplicates++;
                control(json("type", "ACK", "seq", message.sequence()));
                return;
            }
            if (message.sequence() <= remoteSequence) throw new IOException("Out-of-order sequence");
            remoteSequence = message.sequence();
            seen.add(key);
            if (seen.size() > 1024) seen.remove(seen.iterator().next());
            received++;
            corrected += message.correctedCodewords();
        }
        control(json("type", "ACK", "seq", message.sequence()));
        Log.i(TAG, "Received ITP packet from peer (" + currentTransport + "): '" + message.text() + "', forwarding to listener");
        Listener l = listener;
        if (l != null) {
            l.received(message);
        }
    }

    public void sendText(String text, String language) {
        if (text == null || text.trim().isEmpty()) return;
        if (!connected) {
            if (outbox.size() < 10) {
                outbox.add(new String[]{text.trim(), language});
                Log.i(TAG, "Not connected yet; queued speech for auto-connect: '" + text.trim() + "'");
                event("notice", "message", "Linking to peer phone... speech queued");
            }
            discover();
            return;
        }
        sendTextInternal(text, language);
    }

    public void sendEmergency(String text, String language) {
        if (text == null || text.trim().isEmpty()) return;
        if (!connected) {
            event("error", "message", "Not connected. Cannot send emergency.");
            return;
        }
        sendTextInternalEmergency(text, language);
    }

    private void sendTextInternalEmergency(String text, String language) {
        TransportSocket target = activeSocket;
        sender.execute(() -> {
            try {
                if (target != activeSocket || !connected) throw new IOException("Connection changed before transmission");
                long sequence;
                synchronized (this) { sequence = ++seq; }
                var packet = ItpPacket.encode(session, sequence, language, text.trim(), true);
                long delay = Math.max(0, nextSend - now());
                if (delay > 0) Thread.sleep(delay);
                if (target != activeSocket || !connected) throw new IOException("Connection lost while waiting for bitrate budget");
                nextSend = now() + Math.max(1, packet.payloadBytes() * 8000L / bitrate);
                event("sent", "text", text.trim(), "sequence", sequence, "packetBytes", packet.wire().length + 5, "payloadBytes", packet.payloadBytes(), "emergency", true, "transport", currentTransport);
                pending.put(sequence, now());
                write((byte) 2, packet.wire());
                synchronized (this) {
                    sent++;
                    sourceSent += packet.sourceBytes();
                    payloadSent += packet.payloadBytes();
                    lastPacketBytes = packet.wire().length + 5;
                }
                Log.i(TAG, "Successfully sent EMERGENCY ITP packet #" + sequence + " via " + currentTransport + ": '" + text.trim() + "'");
            } catch (Exception e) {
                event("error", "message", "Emergency not sent: " + e.getMessage());
            }
        });
    }

    private void sendTextInternal(String text, String language) {
        TransportSocket target = activeSocket;
        sender.execute(() -> {
            try {
                if (target != activeSocket || !connected) throw new IOException("Connection changed before transmission");
                long sequence;
                synchronized (this) { sequence = ++seq; }
                var packet = ItpPacket.encode(session, sequence, language, text.trim());
                long delay = Math.max(0, nextSend - now());
                if (delay > 0) Thread.sleep(delay);
                if (target != activeSocket || !connected) throw new IOException("Connection lost while waiting for bitrate budget");
                nextSend = now() + Math.max(1, packet.payloadBytes() * 8000L / bitrate);
                event("sent", "text", text.trim(), "sequence", sequence, "packetBytes", packet.wire().length + 5, "payloadBytes", packet.payloadBytes(), "transport", currentTransport);
                pending.put(sequence, now());
                write((byte) 2, packet.wire());
                synchronized (this) {
                    sent++;
                    sourceSent += packet.sourceBytes();
                    payloadSent += packet.payloadBytes();
                    lastPacketBytes = packet.wire().length + 5;
                }
                Log.i(TAG, "Successfully sent ITP packet #" + sequence + " via " + currentTransport + ": '" + text.trim() + "'");
            } catch (Exception e) {
                event("error", "message", "Message not sent: " + e.getMessage());
            }
        });
    }

    private void control(JSONObject data) throws IOException {
        write((byte) 1, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void write(byte kind, byte[] bytes) throws IOException {
        synchronized (writeLock) {
            TransportSocket current = activeSocket != null ? activeSocket : pendingSocket;
            if (current == null) throw new IOException("Disconnected");
            DataOutputStream out = new DataOutputStream(current.getOutputStream());
            FrameIO.write(out, kind, bytes);
            bytesSent += bytes.length + 5;
        }
    }

    private void tick() {
        try {
            if (connected) {
                if (now() - lastReceived > 30000) {
                    lost(activeSocket, "Heartbeat timeout");
                    return;
                }
                control(json("type", "PING", "at", now()));

                // If currently on Bluetooth fallback, check for Wi-Fi Direct recovery!
                if ("bluetooth".equals(currentTransport) && p2pEnabled && !closed && !switchingToWifi) {
                    if (now() - lastP2pDiscoveryTime >= 10000) {
                        discoverP2pPeers();
                    }
                }
            } else {
                broadcastUdpBeacon();
                boolean isBusyConnecting = p2pConnecting || btConnecting || "WAITING_APPROVAL".equals(state) || "PENDING_APPROVAL".equals(state);
                if (p2pEnabled && !closed && !isBusyConnecting && (now() - lastP2pDiscoveryTime >= 15000)) {
                    discoverP2pPeers();
                }
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && !closed && !isBusyConnecting && (now() - lastBtDiscoveryTime >= 20000)) {
                    discoverBluetoothPeers();
                }
            }
            for (var entry : pending.entrySet()) {
                if (now() - entry.getValue() > 12000 && pending.remove(entry.getKey(), entry.getValue())) {
                    unacked++;
                    event("delivery", "sequence", entry.getKey(), "state", "No decode acknowledgement; delivery uncertain");
                }
            }
            double seconds = Math.max(1, (now() - metricsStarted) / 1000.0);
            event("metrics", "sent", sent, "received", received, "txBytes", bytesSent, "rxBytes", bytesReceived, "appTxBps", Math.round(bytesSent * 8 / seconds), "payloadBps", Math.round(payloadSent * 8 / seconds), "payloadBytes", payloadSent, "sourceBytes", sourceSent, "crcFailures", crcFailures, "fecFailures", fecFailures, "fecCorrected", corrected, "duplicates", duplicates, "unacknowledged", unacked, "rttMs", rtt, "configuredBps", bitrate, "lastPacketBytes", lastPacketBytes, "overheadBytes", bytesSent - payloadSent, "textCompression", payloadSent == 0 ? 0 : Math.round(sourceSent * 100.0 / payloadSent) / 100.0, "transport", currentTransport);

            if (!connected && !hosting && activeSocket == null && address.isEmpty()) {
                try {
                    for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                        for (InetAddress ip : Collections.list(network.getInetAddresses())) {
                            if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                                String ipStr = ip.getHostAddress();
                                if (ipStr != null && ipStr.startsWith("192.168.43.") && !ipStr.equals("192.168.43.1")) {
                                    event("peer", "name", "Hotspot Host", "address", "192.168.43.1", "port", 8988);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Heartbeat tick warning: " + e.getMessage());
            if (connected && (activeSocket == null || activeSocket.isClosed())) lost(activeSocket, e.getMessage());
        }
    }

    public long getRtt() { return rtt > 0 ? rtt : 4; }

    public int getWifiRssi() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int r = info.getRssi();
                    if (r != -127 && r != 0) return r;
                }
            }
        } catch (Exception ignored) {}
        return -65;
    }

    private synchronized void lost(TransportSocket expected, String reason) {
        if (expected != null && activeSocket != expected && pendingSocket != expected) return;
        TransportSocket old = activeSocket;
        activeSocket = null;
        connected = false;
        String prevTransport = currentTransport;
        currentTransport = "none";
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        TransportSocket p = pendingSocket;
        pendingSocket = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        if (outgoingApprovalTimer != null) { outgoingApprovalTimer.cancel(false); outgoingApprovalTimer = null; }
        if (closed) return;
        Log.i(TAG, "Connection lost (" + prevTransport + "): " + reason);

        boolean isExplicitDeclineOrCancel = reason != null && (
            reason.contains("Declined") || reason.contains("declined") ||
            reason.contains("reject") || reason.contains("Reject") ||
            reason.contains("cancel") || reason.contains("Cancel") ||
            reason.contains("local user") || reason.contains("explicit") ||
            reason.contains("Peer explicitly disconnected") ||
            reason.contains("BYE")
        );
        if (isExplicitDeclineOrCancel) {
            retry = 99;
            userExplicitDisconnect = true;
            address = "";
            releaseLocks();
            disconnectP2pGroup();
            resetToStandby();
            return;
        }

        event("error", "message", "Link interrupted: " + (reason != null && !reason.trim().isEmpty() ? reason : "Connection lost — Reconnecting"));

        // Auto-reconnect or fallback
        boolean hasValidP2p = isValidP2pAddress(lastPeerP2p);
        String rawTargetIp = !address.isEmpty() ? address : lastPeerAddress;
        boolean isP2pIp = rawTargetIp != null && rawTargetIp.startsWith("192.168.49.");

        final String targetIp;
        if (isP2pIp && !p2pGroupConnected) {
            targetIp = "";
            address = "";
            lastPeerAddress = "";
        } else {
            targetIp = rawTargetIp;
        }

        if (!userExplicitDisconnect && retry < 3 && (hasValidP2p || (targetIp != null && !targetIp.isEmpty()))) {
            retry++;
            state("RECONNECTING");
            startListening();
            long cycle = generation;
            long delaySec = Math.min(3, Math.max(1, retry % 4));
            clock.schedule(() -> {
                if (cycle == generation && !connected && !closed && !userExplicitDisconnect) {
                    if (p2pGroupConnected && p2pGroupOwnerAddress != null && !p2pIsGroupOwner) {
                        address = p2pGroupOwnerAddress;
                        connectInternal();
                    } else if (hasValidP2p) {
                        Log.i(TAG, "Auto-reconnecting P2P to " + lastPeerP2p);
                        connectP2p(lastPeerP2p);
                    } else if (targetIp != null && !targetIp.isEmpty()) {
                        Log.i(TAG, "Fast auto-reconnect attempt #" + retry + " to " + targetIp);
                        address = targetIp;
                        port = lastPeerPort > 0 ? lastPeerPort : 8988;
                        pin = lastPeerPin;
                        connectInternal();
                    }
                }
            }, delaySec, TimeUnit.SECONDS);
        } else if (!userExplicitDisconnect && !btFallbackActive) {
            // Wi-Fi connection lost/failed -> AUTOMATIC BLUETOOTH FALLBACK!
            retry = 0;
            triggerBluetoothFallback();
        } else {
            retry = 0;
            address = "";
            resetToStandby();
        }
    }

    public synchronized void disconnect() {
        userExplicitDisconnect = true;
        btFallbackActive = false;
        switchingToWifi = false;
        releaseLocks();
        generation++;
        address = "";
        lastPeerAddress = "";
        lastPeerCallsign = "";
        connected = false;
        hosting = false;
        currentTransport = "none";
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        if (outgoingApprovalTimer != null) { outgoingApprovalTimer.cancel(false); outgoingApprovalTimer = null; }
        TransportSocket p = pendingSocket;
        pendingSocket = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        TransportSocket old = activeSocket;
        activeSocket = null;
        if (old != null) {
            try {
                control(json("type", "BYE", "reason", "explicit_disconnect"));
            } catch (Exception ignored) {}
            try { Thread.sleep(60); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
        for (long id : pending.keySet()) event("delivery", "sequence", id, "state", "Disconnected; delivery uncertain");
        pending.clear();
        disconnectP2pGroup();
        resetToStandby();
    }

    public synchronized void resetToStandby() {
        connected = false;
        btConnecting = false;
        p2pConnecting = false;
        btFallbackActive = false;
        switchingToWifi = false;
        address = "";
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        if (outgoingApprovalTimer != null) { outgoingApprovalTimer.cancel(false); outgoingApprovalTimer = null; }
        TransportSocket p = pendingSocket;
        pendingSocket = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        TransportSocket old = activeSocket;
        activeSocket = null;
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        currentTransport = "none";
        state("STANDBY");
        startListening();
    }

    private void advertise() {
        if (registration != null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        String nodeName = callsign != null && !callsign.isEmpty() ? callsign : Build.MODEL;
        info.setServiceName("SWAR-" + nodeName.replaceAll("[^a-zA-Z0-9-]", "") + "-" + session.toString().substring(0, 4));
        info.setServiceType("_swar._tcp.");
        info.setPort(8988);
        registration = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "mDNS Service registered: " + i.getServiceName()); }
            public void onRegistrationFailed(NsdServiceInfo i, int error) { event("error", "message", "Discovery advertising unavailable; use host address."); }
            public void onServiceUnregistered(NsdServiceInfo i) {}
            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            event("error", "message", "Use host address; discovery registration failed.");
        }
    }

    public void discover() {
        startListening();
        startBtServer();
        broadcastUdpBeacon();
        discoverP2pPeers();
        discoverBluetoothPeers();
        if (discovery != null) return;
        try {
            multicast.acquire();
            discovery = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String type) {
                    event("discovery", "state", "Scanning local network");
                    Log.i(TAG, "mDNS Discovery started");
                }
                public void onServiceFound(NsdServiceInfo info) {
                    if (info.getServiceName().contains(session.toString().substring(0, 4)) || !resolving.add(info.getServiceName())) return;
                    Log.i(TAG, "mDNS Service found: " + info.getServiceName() + ", resolving...");
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int e) {
                            resolving.remove(i.getServiceName());
                        }
                        public void onServiceResolved(NsdServiceInfo i) {
                            resolving.remove(i.getServiceName());
                            String host = i.getHost().getHostAddress();
                            if (host != null && host.contains(".") && !isSelfAddress(host)) {
                                Log.i(TAG, "mDNS Service resolved: " + i.getServiceName() + " -> " + host + ":" + i.getPort());
                                event("peer", "name", i.getServiceName(), "serviceName", i.getServiceName(), "address", host, "port", i.getPort(), "source", "mdns");
                            }
                        }
                    });
                }
                public void onServiceLost(NsdServiceInfo info) {
                    event("peerLost", "name", info.getServiceName(), "serviceName", info.getServiceName());
                }
                public void onDiscoveryStopped(String t) { Log.i(TAG, "mDNS Discovery stopped"); }
                public void onStartDiscoveryFailed(String t, int e) { event("error", "message", "Discovery failed; use the host address shown on the other phone."); }
                public void onStopDiscoveryFailed(String t, int e) {}
            };
            nsd.discoverServices("_swar._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            event("error", "message", "Discovery unavailable: " + e.getMessage());
        }
    }

    private JSONArray addresses() {
        JSONArray result = new JSONArray();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress ip : Collections.list(network.getInetAddresses())) {
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                        result.put(ip.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void closeServer() {
        ServerSocket old = server;
        server = null;
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        DatagramSocket oldUdp = udpSocket;
        udpSocket = null;
        if (oldUdp != null) try { oldUdp.close(); } catch (Exception ignored) {}
        if (registration != null) {
            try { nsd.unregisterService(registration); } catch (Exception ignored) {}
            registration = null;
        }
    }

    public void close() {
        closed = true;
        disconnect();
        closeServer();
        closeBtServer();
        stopP2pDiscovery();
        disconnectP2pGroup();
        unregisterP2pReceiver();
        unregisterBtReceiver();
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        if (multicast.isHeld()) multicast.release();
        sender.shutdownNow();
        io.shutdownNow();
        clock.shutdownNow();
    }
}
