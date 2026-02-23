package net.greblus;

import org.qtproject.example.AspeQt.R;
import net.greblus.SerialDevice;

import android.widget.Toast;
import android.os.Bundle;
import java.lang.String;
import java.util.List;
import android.util.Log;

import org.qtproject.qt.android.bindings.QtActivity;

import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;

import android.bluetooth.BluetoothAdapter;

import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.widget.Toast;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Insets;

import android.os.Build;
import android.content.pm.PackageManager;
import android.Manifest;

public class SerialActivity extends QtActivity
{
        private static final int REQUEST_BLUETOOTH_CONNECT = 2;
        private static final int REQUEST_ENABLE_BT = 3;
        private static final int REQUEST_FILE_OPEN = 4;
        private static final int REQUEST_FILE_SAVE = 5;
        private static final int REQUEST_DIR_CHOOSE = 6;
        private static final Object bluetoothEnableLock = new Object();
        private static volatile boolean bluetoothEnableComplete = false;
        private static boolean debug = false;
        protected static byte rb[] = new byte [65535];
        protected static byte wb[] = new byte [65535];
        protected static byte t[] = new byte [1024];
        public static native void sendBufAddr(ByteBuffer rbuf, ByteBuffer wbuf);
        protected static ByteBuffer rbuf = ByteBuffer.allocateDirect(65535);
        protected static ByteBuffer wbuf = ByteBuffer.allocateDirect(65535);
        public static String m_chosen;
        private static int m_filter;
        private static String m_action;
        private static String m_dir;
        protected static SerialActivity s_activity = null;
        private static SerialDevice m_device = null;
        private static int m_serial = 0;
        public static String bluetoothName;
        private static Uri m_pendingSaveUri = null;
        private static String m_pendingSaveCachePath = null;

        @Override
	public void onCreate(Bundle savedInstanceState)
 	{            
            s_activity = this;
            super.onCreate(savedInstanceState);
            if (m_serial == 0)
                m_device = new SIO2PCUS4A();
            else
                m_device = new SIO2BT();

            registerBroadcastReceiver();

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            sendBufAddr(rbuf, wbuf);

            // Edge-to-edge compliance: On API 35+ edge-to-edge is enforced, and on
            // API 36+ the opt-out flag (windowOptOutEdgeToEdgeEnforcement) is removed.
            // Qt 5.x does not handle system bar insets automatically (Qt 6.9+ adds
            // Safe Areas). We pad the content view to keep the Qt rendering surface
            // clear of status bar, navigation bar, and display cutout.
            if (Build.VERSION.SDK_INT >= 30) {
                View contentView = getWindow().getDecorView().findViewById(android.R.id.content);
                contentView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        Insets systemBars = insets.getInsets(
                                WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout());
                        v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                        return WindowInsets.CONSUMED;
                    }
                });
            }
        }

        @Override
        public void onPause() {
           m_chosen = "Cancelled";
           super.onPause();
        }

        public static void runFileChooser(int filter, int action, String dir) {
            flushPendingSave();
            Log.i("ASPEQT:", "DIR:" + dir);
            m_chosen = "None";
            m_filter = filter;
            m_dir = dir;

            if (action == 0)
                m_action = "FileOpen";
            else
                m_action = "FileSave";

            SerialActivity.s_activity.runOnUiThread( new FileChooser() );

         }

         public static void runDirChooser(String dir) {
            flushPendingSave();
            m_dir = dir;
            m_chosen = "None";
            m_filter = 0;
            SerialActivity.s_activity.runOnUiThread( new DirChooser() );

          }

        @Override
	protected void onDestroy()
	{
                flushPendingSave();
                super.onDestroy();
                s_activity = null;
                m_device.closeDevice();
	}

        public void fileChooser()
        {
            if ("FileOpen".equals(m_action)) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_FILE_OPEN);
            } else {
                flushPendingSave();
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_FILE_SAVE);
            }
        }

        public void dirChooser()
        {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_DIR_CHOOSE);
        }

        public static void registerBroadcastReceiver() {
                if (SerialActivity.s_activity != null) {
                        // Qt is running on a different thread than Android.
                        // In order to register the receiver we need to execute it in the UI thread
                        SerialActivity.s_activity.runOnUiThread( new RegisterReceiverRunnable());
                        Log.i("USB", "Broadcast Receiver registered");
                }
        }

        public static void changeDevice(int type)
        {
            m_device.closeDevice();
            if (type == 1) {
                requestBluetoothPermission();
                m_device = new SIO2BT();
            } else
                m_device = new SIO2PCUS4A();
        }

        public static void requestBluetoothPermission() {
            if (Build.VERSION.SDK_INT >= 31) {
                if (s_activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    s_activity.requestPermissions(
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_CONNECT);
                }
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i("BT", "BLUETOOTH_CONNECT permission granted");
                } else {
                    Log.w("BT", "BLUETOOTH_CONNECT permission denied");
                    s_activity.runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(s_activity, "Bluetooth permission is required for SIO2BT", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }

        public static void requestBluetoothEnable() {
            if (s_activity == null) return;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) return;

            synchronized (bluetoothEnableLock) {
                bluetoothEnableComplete = false;
            }

            s_activity.runOnUiThread(new Runnable() {
                public void run() {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    s_activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }
            });
        }

        public static boolean waitForBluetoothEnabled(long timeoutMs) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) return true;

            synchronized (bluetoothEnableLock) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!bluetoothEnableComplete) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        bluetoothEnableLock.wait(remaining);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            return adapter != null && adapter.isEnabled();
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == REQUEST_ENABLE_BT) {
                synchronized (bluetoothEnableLock) {
                    bluetoothEnableComplete = true;
                    bluetoothEnableLock.notifyAll();
                }
            } else if (requestCode == REQUEST_FILE_OPEN) {
                if (resultCode == RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.w("SAF", "Could not take persistable permission", e);
                        }
                        String cachedPath = copyUriToCacheDir(uri);
                        if (cachedPath != null) {
                            m_chosen = cachedPath;
                            Toast.makeText(this, getResources().getString(R.string.file_chosen) +
                                    m_chosen, Toast.LENGTH_LONG).show();
                        } else {
                            m_chosen = "Cancelled";
                        }
                    } else {
                        m_chosen = "Cancelled";
                    }
                } else {
                    m_chosen = "Cancelled";
                }
            } else if (requestCode == REQUEST_DIR_CHOOSE) {
                if (resultCode == RESULT_OK && data != null) {
                    Uri treeUri = data.getData();
                    if (treeUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.w("SAF", "Could not take persistable tree permission", e);
                        }
                        String path = getPathFromTreeUri(treeUri);
                        if (path != null) {
                            m_chosen = path;
                            Toast.makeText(this, getResources().getString(R.string.dir_chosen) +
                                    m_chosen, Toast.LENGTH_LONG).show();
                        } else {
                            m_chosen = "Cancelled";
                            Toast.makeText(this, "Cannot access this folder directly", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        m_chosen = "Cancelled";
                    }
                } else {
                    m_chosen = "Cancelled";
                }
            } else if (requestCode == REQUEST_FILE_SAVE) {
                if (resultCode == RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.w("SAF", "Could not take persistable write permission", e);
                        }
                        String fileName = getFileNameFromUri(uri);
                        if (fileName == null) fileName = "saved_file";
                        File cacheFile = new File(getCacheDir(), fileName);
                        m_pendingSaveUri = uri;
                        m_pendingSaveCachePath = cacheFile.getAbsolutePath();
                        m_chosen = m_pendingSaveCachePath;
                        Toast.makeText(this, getResources().getString(R.string.file_chosen) +
                                fileName, Toast.LENGTH_LONG).show();
                    } else {
                        m_chosen = "Cancelled";
                    }
                } else {
                    m_chosen = "Cancelled";
                }
            }
        }

        private static void flushPendingSave() {
            if (m_pendingSaveUri == null || m_pendingSaveCachePath == null || s_activity == null) return;
            File cacheFile = new File(m_pendingSaveCachePath);
            if (!cacheFile.exists() || cacheFile.length() == 0) {
                m_pendingSaveUri = null;
                m_pendingSaveCachePath = null;
                return;
            }
            InputStream is = null;
            OutputStream os = null;
            try {
                is = new FileInputStream(cacheFile);
                os = s_activity.getContentResolver().openOutputStream(m_pendingSaveUri);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                Log.i("SAF", "Flushed save to content URI: " + m_pendingSaveUri);
            } catch (Exception e) {
                Log.e("SAF", "Failed to flush save to content URI", e);
            } finally {
                try { if (is != null) is.close(); } catch (Exception e) {}
                try { if (os != null) os.close(); } catch (Exception e) {}
            }
            m_pendingSaveUri = null;
            m_pendingSaveCachePath = null;
        }

        private String copyUriToCacheDir(Uri uri) {
            String fileName = getFileNameFromUri(uri);
            if (fileName == null) fileName = "temp_file";
            File cacheFile = new File(getCacheDir(), fileName);
            InputStream is = null;
            OutputStream os = null;
            try {
                is = getContentResolver().openInputStream(uri);
                os = new FileOutputStream(cacheFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                return cacheFile.getAbsolutePath();
            } catch (Exception e) {
                Log.e("SAF", "Failed to copy file to cache", e);
                return null;
            } finally {
                try { if (is != null) is.close(); } catch (Exception e) {}
                try { if (os != null) os.close(); } catch (Exception e) {}
            }
        }

        private String getFileNameFromUri(Uri uri) {
            String result = null;
            if ("content".equals(uri.getScheme())) {
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (index >= 0) result = cursor.getString(index);
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
            if (result == null) {
                result = uri.getLastPathSegment();
            }
            return result;
        }

        private String getPathFromTreeUri(Uri treeUri) {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) return null;
            String[] split = docId.split(":");
            if (split.length < 2) return null;
            String type = split[0];
            String relativePath = split[1];
            if ("primary".equalsIgnoreCase(type)) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + relativePath;
            } else {
                File storageDir = new File("/storage/" + type + "/" + relativePath);
                if (storageDir.exists()) {
                    return storageDir.getAbsolutePath();
                }
            }
            return null;
        }

        public static int openDevice() {
            return m_device.openDevice();
        }

        public static void closeDevice() {
            m_device.closeDevice();
        }

        public static int setSpeed(int speed) {
            return m_device.setSpeed(speed);
        }

        public static int read(int size, int total)
        {
            int rd = m_device.read(size, total);
            return rd;
        }

        public static int write(int size, int total)
        {
            m_device.write(size, total);
            return size;
        }

        public static boolean purge() {
            return m_device.purge();
        }

        public static boolean purgeTX() {
            return m_device.purgeTX();
        }

        public static boolean purgeRX() {
            return m_device.purgeRX();
        }

        public static void qLog(String msg) {
            if (debug) Log.i("USB", msg);
        }

        public static int getModemStatus() {
            return m_device.getModemStatus();
        }

        public static int getSWCommandFrame() {
            return m_device.getSWCommandFrame();
        }

        public static int getHWCommandFrame(int mMethod) {
             return m_device.getHWCommandFrame(mMethod);
        }
}
    class FileChooser implements Runnable
    {
        @Override
        public void run() {
            SerialActivity.s_activity.fileChooser();
        }
    }

    class DirChooser implements Runnable
    {
        @Override
        public void run() {
            SerialActivity.s_activity.dirChooser();
        }
    }

    class RegisterReceiverRunnable implements Runnable
    {
            // this method is called on Android Ui Thread
            @Override
            public void run() {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction("com.android.example.USB_PERMISSION");
                    // this method must be called on Android Ui Thread
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SerialActivity.s_activity.registerReceiver(new USBReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
                    } else {
                        SerialActivity.s_activity.registerReceiver(new USBReceiver(), filter);
                    }
                    }
    }

    class USBReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.android.example.USB_PERMISSION"))
            synchronized (this) {
                UsbDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.v("USB", "Received permission result OK");
                    if(device != null){
                        Log.i("USB", "Device OK");
                    }
                    else {
                        Log.i("USB", "Permission denied for device " + device);
                        SerialActivity.s_activity.runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(SerialActivity.s_activity, SerialActivity.s_activity.getResources().getString(R.string.sio2pc_no_permissions), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
        }
    }
