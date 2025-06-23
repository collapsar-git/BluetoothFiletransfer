package com.example.bluetoothfiletransfer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import android.content.ContentValues;
import android.content.ContentResolver;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity_Bluetooth";
    private static final String APP_NAME = "BluetoothFileTransfer";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // UI 组件
    private Button btnListen, btnListDevices, btnSelectFile;
    private ListView lvDevices;
    private TextView tvStatus;

    // 蓝牙相关
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice[] btArray;

    // 文件相关
    private Uri selectedFileUri;

    private ActivityResultLauncher<String[]> requestBluetoothPermissionsLauncher;
    private ActivityResultLauncher<String> requestStoragePermissionLauncher;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBluetooth();
        initLaunchers();
        implementListeners();

        // 启动时就请求蓝牙权限，为后续操作做准备
        requestBluetoothPermissions();
    }

    /**
     * 初始化UI视图
     */
    private void initViews() {
        btnListen = findViewById(R.id.btn_listen);
        btnListDevices = findViewById(R.id.btn_list_devices);
        btnSelectFile = findViewById(R.id.btn_select_file);
        lvDevices = findViewById(R.id.lv_devices);
        tvStatus = findViewById(R.id.tv_status);
        tvStatus.setText("状态: 准备就绪");
    }

    /**
     * 初始化蓝牙适配器
     */
    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * 初始化所有的 ActivityResultLauncher
     */
    private void initLaunchers() {
        // 1. 蓝牙权限请求 Launcher
        requestBluetoothPermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean granted : permissions.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        Toast.makeText(this, "蓝牙权限已获取", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "需要蓝牙权限才能运行", Toast.LENGTH_LONG).show();
                    }
                });

        // 2. 存储权限请求 Launcher
        requestStoragePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        openFilePicker();
                    } else {
                        Toast.makeText(this, "需要文件读取权限来选择文件", Toast.LENGTH_LONG).show();
                    }
                });

        // 3. 文件选择器 Launcher
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        selectedFileUri = result.getData().getData();
                        Toast.makeText(this, "已选择文件: " + selectedFileUri.getPath(), Toast.LENGTH_SHORT).show();
                        tvStatus.setText("文件已选择，请选择设备发送");
                    }
                });

        // 4. 启用蓝牙 Launcher
        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Toast.makeText(this, "蓝牙已开启", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "必须开启蓝牙才能使用此功能", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    /**
     * 设置所有按钮和列表的监听器
     */
    private void implementListeners() {
        btnListen.setOnClickListener(v -> {
            if (checkAndEnableBluetooth()) {
                ServerClass serverClass = new ServerClass();
                serverClass.start();
            }
        });

        btnListDevices.setOnClickListener(v -> {
            if (checkAndEnableBluetooth()) {
                listPairedDevices();
            }
        });

        btnSelectFile.setOnClickListener(v -> {
            checkAndRequestStoragePermission();
        });

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (selectedFileUri == null) {
                Toast.makeText(this, "请先选择要发送的文件", Toast.LENGTH_SHORT).show();
                return;
            }
            if (btArray != null && btArray.length > position) {
                ClientClass clientClass = new ClientClass(btArray[position]);
                clientClass.start();
                tvStatus.setText("正在连接: " + btArray[position].getName());
            }
        });
    }

    /**
     * 动态请求蓝牙权限的核心方法
     */
    private void requestBluetoothPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else { // Android 11 及以下
            permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        // 扫描需要位置权限
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);

        ArrayList<String> permissionsNotGranted = new ArrayList<>();
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNotGranted.add(permission);
            }
        }

        if (!permissionsNotGranted.isEmpty()) {
            requestBluetoothPermissionsLauncher.launch(permissionsNotGranted.toArray(new String[0]));
        }
    }

    /**
     * 动态请求存储权限
     */
    private void checkAndRequestStoragePermission() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            permission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openFilePicker();
        } else {
            requestStoragePermissionLauncher.launch(permission);
        }
    }

    /**
     * 检查蓝牙是否开启，如果没有则提示用户开启
     * @return 如果蓝牙已开启则返回 true，否则返回 false
     */
    @SuppressLint("MissingPermission")
    private boolean checkAndEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
            return false;
        }
        return true;
    }

    /**
     * 列出已配对的设备
     */
    @SuppressLint("MissingPermission")
    private void listPairedDevices() {
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(this, "没有已配对的设备", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> deviceNames = new ArrayList<>();
        btArray = new BluetoothDevice[bondedDevices.size()];
        int index = 0;
        for (BluetoothDevice device : bondedDevices) {
            // Android 12+ 需要 BLUETOOTH_CONNECT 权限才能获取设备名称
            deviceNames.add(device.getName());
            btArray[index] = device;
            index++;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        lvDevices.setAdapter(adapter);
    }

    /**
     * 打开文件选择器
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        filePickerLauncher.launch(intent);
    }

    // --- 服务端和客户端线程，以及文件传输逻辑 ---

    /**
     * 服务端线程，用于监听和接收文件
     */
    private class ServerClass extends Thread {
        private BluetoothServerSocket serverSocket;

        @SuppressLint("MissingPermission")
        public ServerClass() {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "ServerSocket's listen() method failed", e);
            }
        }

        public void run() {
            BluetoothSocket socket = null;
            try {
                runOnUiThread(() -> tvStatus.setText("状态: 等待连接..."));
                socket = serverSocket.accept(); // 阻塞调用
                runOnUiThread(() -> tvStatus.setText("状态: 已连接，正在接收文件..."));
                receiveFile(socket);
            } catch (IOException e) {
                Log.e(TAG, "Socket's accept() method failed", e);
                runOnUiThread(() -> tvStatus.setText("状态: 连接失败"));
            } finally {
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Could not close the server socket", e);
                    }
                }
            }
        }
    }

    /**
     * 客户端线程，用于发起连接和发送文件
     */
    private class ClientClass extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket socket;

        @SuppressLint("MissingPermission")
        public ClientClass(BluetoothDevice device1) {
            device = device1;
            try {
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
            }
        }

        @SuppressLint("MissingPermission")
        public void run() {
            try {
                socket.connect(); // 阻塞调用
                runOnUiThread(() -> tvStatus.setText("状态: 连接成功，正在发送文件..."));
                sendFile(socket);
            } catch (IOException connectException) {
                runOnUiThread(() -> tvStatus.setText("状态: 连接失败"));
                try {
                    socket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
            }
        }
    }

    /**
     * 通过 Socket 发送文件
     */
    private void sendFile(BluetoothSocket socket) {
        if (selectedFileUri == null || socket == null) return;
        try (InputStream inputStream = getContentResolver().openInputStream(selectedFileUri);
             OutputStream outputStream = socket.getOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            runOnUiThread(() -> {
                tvStatus.setText("状态: 文件发送成功");
                Toast.makeText(MainActivity.this, "文件发送成功", Toast.LENGTH_SHORT).show();
            });
        } catch (IOException e) {
            Log.e(TAG, "Error sending file", e);
            runOnUiThread(() -> tvStatus.setText("状态: 文件发送失败"));
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the socket", e);
            }
        }
    }

    /**
     * 通过 Socket 接收文件
     */
    private void receiveFile(BluetoothSocket socket) {
        if (socket == null) return;

        String savedFilePath = "Downloads 文件夹";

        try (InputStream inputStream = socket.getInputStream()) {
            String fileName = "received_image_" + System.currentTimeMillis() + ".jpg";
            OutputStream fileOutputStream;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri imageUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (imageUri == null) throw new IOException("无法创建 MediaStore entry");
                fileOutputStream = resolver.openOutputStream(imageUri);
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadsDir, fileName);
                savedFilePath = file.getAbsolutePath();
                fileOutputStream = new FileOutputStream(file);
            }

            if (fileOutputStream == null) {
                throw new IOException("创建文件输出流失败");
            }

            // --- 核心的读写循环 ---
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
            }

            final String finalPath = savedFilePath;
            runOnUiThread(() -> {
                tvStatus.setText("状态: 文件接收成功 (循环正常结束)");
                Toast.makeText(MainActivity.this, "文件已保存到: " + finalPath, Toast.LENGTH_LONG).show();
            });

        } catch (IOException e) {
            Log.e(TAG, "捕获到IO异常", e);
            if (e.getMessage() != null && e.getMessage().contains("bt socket closed, read return: -1")) {
                final String finalPath = "Downloads 文件夹";
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 文件接收成功");
                    Toast.makeText(MainActivity.this, "文件已保存到: " + finalPath, Toast.LENGTH_LONG).show();
                });
            } else {
                runOnUiThread(() -> {
                    tvStatus.setText("状态: 文件接收失败");
                });
            }
        } finally {
            // 确保socket最后被关闭
            try {
                if (socket != null && socket.isConnected()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "关闭最终socket时出错", e);
            }
        }
    }
}