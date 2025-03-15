package com.htetznaing.adbotg;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;
import com.cgutman.adblib.UsbChannel;
import com.htetznaing.adbotg.Adapter.SliderAdapterExample;
import com.htetznaing.adbotg.Model.SliderItem;
import com.htetznaing.adbotg.UI.SpinnerDialog;
import com.smarteist.autoimageslider.IndicatorView.animation.type.IndicatorAnimationType;
import com.smarteist.autoimageslider.SliderAnimations;
import com.smarteist.autoimageslider.SliderView;

import java.io.File;
import java.io.IOException;
import static com.htetznaing.adbotg.Message.CONNECTING;
import static com.htetznaing.adbotg.Message.DEVICE_FOUND;
import static com.htetznaing.adbotg.Message.DEVICE_NOT_FOUND;
import static com.htetznaing.adbotg.Message.FLASHING;
import static com.htetznaing.adbotg.Message.INSTALLING_PROGRESS;

public class MainActivity extends AppCompatActivity implements TextView.OnEditorActionListener, View.OnKeyListener {
    private EditText edCommand;
    private ScrollView scrollView;
    private TextView logs;
    private SpinnerDialog waitingDialog;

    private UsbManager mManager;
    private Handler handler;
    private AdbCrypto adbCrypto;

    private UsbDevice mDevice;
    private AdbConnection adbConnection;
    private AdbStream stream;

    private boolean isShell;
    private boolean doubleBackToExitPressedOnce;

    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(Const.TAG, "mUsbReceiver onReceive => " + action);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(Const.TAG, "UsbReceiver: device removed");
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if ((device != null) && (mDevice != null)) {
                    String deviceName = device.getDeviceName();
                    if ((deviceName != null) && !deviceName.isEmpty() && deviceName.equals(mDevice.getDeviceName())) {
                        try {
                            Log.d(Const.TAG, "setAdbInterface(null, null)");
                            setAdbInterface(null, null);
                        } catch (Exception e) {
                            Log.w(Const.TAG, "setAdbInterface(null,null) failed", e);
                        }
                    }
                }
            }
            else if (Message.USB_PERMISSION.equals(action)){
                Log.d(Const.TAG, "UsbReceiver: device added");
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    handler.sendEmptyMessage(CONNECTING);
                    if (mManager.hasPermission(device))
                        asyncRefreshAdbConnection(device);
                    else
                        mManager.requestPermission(device,PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(Message.USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        edCommand = findViewById(R.id.edCommand);
        scrollView = findViewById(R.id.scrollView);
        logs = findViewById(R.id.logs);
        waitingDialog = null;

        final TextView tvStatus = findViewById(R.id.tv_status);
        final ImageView usb_icon = findViewById(R.id.usb_icon);
        final RelativeLayout terminalView = findViewById(R.id.terminalView);
        final LinearLayout checkContainer = findViewById(R.id.checkContainer);
        final SliderView sliderView = findViewById(R.id.imageSlider);
        final Button btnRun = findViewById(R.id.btnRun);

        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull android.os.Message msg) {
                switch (msg.what) {
                    case DEVICE_FOUND:
                        closeWaiting();
                        tvStatus.setText(getString(R.string.adb_device_connected));
                        usb_icon.setColorFilter(Color.parseColor("#4CAF50"));
                        checkContainer.setVisibility(View.GONE);
                        terminalView.setVisibility(View.VISIBLE);
                        initCommand();
                        showKeyboard();
                        break;

                    case CONNECTING:
                        waitingDialog();
                        closeKeyboard();
                        tvStatus.setText(getString(R.string.waiting_device));
                        usb_icon.setColorFilter(Color.BLUE);
                        checkContainer.setVisibility(View.VISIBLE);
                        terminalView.setVisibility(View.GONE);
                        break;

                    case DEVICE_NOT_FOUND:
                        closeWaiting();
                        closeKeyboard();
                        tvStatus.setText(getString(R.string.adb_device_not_connected));
                        usb_icon.setColorFilter(Color.RED);
                        checkContainer.setVisibility(View.VISIBLE);
                        terminalView.setVisibility(View.GONE);
                        break;

                    case FLASHING:
                        Toast.makeText(MainActivity.this, getString(R.string.toast_flashing), Toast.LENGTH_SHORT).show();
                        break;

                    case INSTALLING_PROGRESS:
                        Toast.makeText(MainActivity.this, getString(R.string.toast_installing_progress), Toast.LENGTH_SHORT).show();
                        break;

                }
            }
        };

        AdbBase64 base64 = new MyAdbBase64();
        try {
            adbCrypto = AdbCrypto.loadAdbKeyPair(base64, new File(getFilesDir(), "private_key"), new File(getFilesDir(), "public_key"));
        } catch (Exception e) {
            Log.w(Const.TAG, "AdbCrypto.loadAdbKeyPair() failed", e);
        }

        if (adbCrypto == null) {
            try {
                adbCrypto = AdbCrypto.generateAdbKeyPair(base64);
                adbCrypto.saveAdbKeyPair(new File(getFilesDir(), "private_key"), new File(getFilesDir(), "public_key"));
            } catch (Exception e) {
                Log.w(Const.TAG, "failed to generate and save key-pair", e);
            }
        }

        mDevice = null;
        adbConnection = null;
        stream = null;

        isShell = false;
        doubleBackToExitPressedOnce = false;

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(Message.USB_PERMISSION);

        ContextCompat.registerReceiver(this, mUsbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        //Check USB
        UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) {
            Log.d(Const.TAG, "From Intent!");
            asyncRefreshAdbConnection(device);
        }
        else {
            Log.d(Const.TAG, "From onCreate!");
            for (String k : mManager.getDeviceList().keySet()) {
                UsbDevice usbDevice = mManager.getDeviceList().get(k);
                handler.sendEmptyMessage(CONNECTING);
                if (mManager.hasPermission(usbDevice)) { ;
                    asyncRefreshAdbConnection(usbDevice);
                }
                else {
                    mManager.requestPermission(
                        usbDevice,
                        PendingIntent.getBroadcast(
                            getApplicationContext(),
                            0,
                            new Intent(Message.USB_PERMISSION),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    );
                }
            }
        }

        //Slider
        final SliderAdapterExample adapter = new SliderAdapterExample(this);
        sliderView.setSliderAdapter(adapter);
        sliderView.setIndicatorAnimation(IndicatorAnimationType.WORM); //set indicator animation by using SliderLayout.IndicatorAnimations. :WORM or THIN_WORM or COLOR or DROP or FILL or NONE or SCALE or SCALE_DOWN or SLIDE and SWAP!!
        sliderView.setSliderTransformAnimation(SliderAnimations.SIMPLETRANSFORMATION);
        sliderView.setAutoCycleDirection(SliderView.AUTO_CYCLE_DIRECTION_BACK_AND_FORTH);
        sliderView.setIndicatorSelectedColor(Color.WHITE);
        sliderView.setIndicatorUnselectedColor(Color.GRAY);
        sliderView.setScrollTimeInSec(3);
        sliderView.setAutoCycle(true);
        sliderView.startAutoCycle();

        final SliderItem sliderItem1 = new SliderItem();
        sliderItem1.setImageUrl(R.drawable.p2p_howto);
        sliderItem1.setDescription(getString(R.string.slider_item1_description));
        adapter.addItem(sliderItem1);

        final SliderItem sliderItem2 = new SliderItem();
        sliderItem2.setImageUrl(R.drawable.deb);
        sliderItem2.setDescription(getString(R.string.slider_item2_description));
        adapter.addItem(sliderItem2);

        edCommand.setImeActionLabel(getString(R.string.button_label_run_command), EditorInfo.IME_ACTION_DONE);
        edCommand.setOnEditorActionListener(this);
        edCommand.setOnKeyListener(this);

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                putCommand();
            }
        });
    }

    private void clearLog() {
        logs.setText("");
    }

    private void closeWaiting(){
        if (waitingDialog != null) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }

    private void waitingDialog(){
        closeWaiting();
        waitingDialog = SpinnerDialog.displayDialog(this, "IMPORTANT ⚡",
                        "You may need to accept a prompt on the target device if you are connecting "+
                        "to it for the first time from this device.", false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(Const.TAG, "From onNewIntent");
        asyncRefreshAdbConnection((UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
    }

    @Override
    public void onDestroy() {
        releaseAll();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId()==R.id.go_to_github){
            startActivity(
                new Intent(Intent.ACTION_VIEW).setData(
                    Uri.parse(
                        getString(R.string.app_github_repo_url)
                    )
                )
            );
        }
        return super.onOptionsItemSelected(item);
    }

    public void asyncRefreshAdbConnection(final UsbDevice device) {
        if (device != null) {
            new Thread() {
                @Override
                public void run() {
                    final UsbInterface intf = findAdbInterface(device);
                    try {
                        setAdbInterface(device, intf);
                    } catch (Exception e) {
                        Log.w(Const.TAG, "setAdbInterface(device, intf) fail", e);
                    }
                }
            }.start();
        }
    }

    // searches for an adb interface on the given USB device
    private UsbInterface findAdbInterface(UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (
              (intf.getInterfaceClass()    == 255) &&
              (intf.getInterfaceSubclass() ==  66) &&
              (intf.getInterfaceProtocol() ==   1)
            ) {
                return intf;
            }
        }
        return null;
    }

    // Sets the current USB device and interface
    private synchronized boolean setAdbInterface(UsbDevice device, UsbInterface intf) throws IOException, InterruptedException {
        closeConnection();

        if (device != null && intf != null) {
            UsbDeviceConnection connection = mManager.openDevice(device);
            if (connection != null) {
                if (connection.claimInterface(intf, false)) {
                    handler.sendEmptyMessage(CONNECTING);
                    adbConnection = AdbConnection.create(new UsbChannel(connection, intf), adbCrypto);
                    adbConnection.connect();
                    //TODO: DO NOT DELETE IT, I CAN'T EXPLAIN WHY
                    adbConnection.open("shell:exec date");

                    mDevice = device;
                    handler.sendEmptyMessage(DEVICE_FOUND);
                    return true;
                }
                else {
                    connection.close();
                }
            }
        }

        handler.sendEmptyMessage(DEVICE_NOT_FOUND);

        mDevice = null;
        return false;
    }

    private void releaseAll() {
        unregisterReceiver(mUsbReceiver);
        closeConnection();
    }

    private void closeConnection() {
        try {
            if (adbConnection != null) {
                adbConnection.close();
                adbConnection = null;
            }
        } catch (IOException e) {
            Log.w(Const.TAG, "failed to close ADB connection", e);
        }
    }

    private void initCommand(){
        // Print output from open ADB connection until closed
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (adbConnection != null) {
                    try {
                        if (stream == null) {
                            Thread.sleep(1000);
                            continue;
                        }

                        final String output = new String(stream.read(), "US-ASCII");

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                logs.append(output);

                                scrollView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                                        edCommand.requestFocus();
                                    }
                                });
                            }
                        });
                    } catch (Exception e) {
                        Log.w(Const.TAG, "failed to read output from ADB connection", e);

                        if ((stream != null) && stream.isClosed())
                            stream = null;
                    }
                }
            }
        }).start();
    }

    private void putCommand() {
        if (adbConnection == null)
            return;

        String cmd = edCommand.getText().toString().trim();

        if (cmd.isEmpty())
            return;

        edCommand.setText("");

        if (cmd.equalsIgnoreCase("clear")) {
            clearLog();
            return;
        }

        if (isShell) {
            if (cmd.equalsIgnoreCase("exit")) {
                closeStream();
                isShell = false;
            }
            else {
                try {
                    writeToStream(cmd);
                }
                catch(Exception e) {
                    Log.w(Const.TAG, "failed to send shell command to ADB connection:" + "\n" + cmd, e);
                }
            }
        }
        else {
            if (cmd.equalsIgnoreCase("exit")) {
                releaseAll();
                finish();
            }
            else if (cmd.startsWith("adb ")) {
                cmd = cmd.substring(4).trim();

                if (cmd.isEmpty()) {
                    Toast.makeText(MainActivity.this, getString(R.string.toast_adb_invalid_command), Toast.LENGTH_SHORT).show();
                    return;
                }

                int index = cmd.indexOf(" ");

                cmd = (index == -1)
                    ? (cmd + ":")
                    : (cmd.substring(0, index) + ":" + cmd.substring(index + 1));

                isShell = cmd.equals("shell:");

                try {
                    openStream(cmd);
                }
                catch(Exception e) {
                    Log.w(Const.TAG, "failed to send command to ADB connection:" + "\n" + cmd, e);
                }
            }
            else {
                Toast.makeText(MainActivity.this, getString(R.string.toast_adb_invalid_command), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openStream(String destination) throws Exception {
        if (adbConnection == null)
            return;

        closeStream();
        stream = adbConnection.open(destination);

        if (!isShell)
            closeStream();
    }

    private void writeToStream(String cmd) throws Exception {
        if ((adbConnection == null) || (stream == null) || stream.isClosed())
            return;

        stream.write((cmd + "\n").getBytes("UTF-8"));
    }

    private void closeStream() {
        if ((adbConnection == null) || (stream == null) || stream.isClosed())
            return;

        try {
            stream.close();
        }
        catch(Exception e) {
        }
    }

    public void open(View view) {
    }

    public void showKeyboard(){
        edCommand.requestFocus();
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    public void closeKeyboard(){
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, getString(R.string.toast_click_back_again_to_exit), Toast.LENGTH_SHORT).show();

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                doubleBackToExitPressedOnce=false;
            }
        }, 2000);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        /* We always return false because we want to dismiss the keyboard */
        if (adbConnection != null && actionId == EditorInfo.IME_ACTION_DONE) {
            putCommand();
        }
        return true;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            /* Just call the onEditorAction function to handle this for us */
            return onEditorAction((TextView)v, EditorInfo.IME_ACTION_DONE, event);
        }
        else {
            return false;
        }
    }
}
