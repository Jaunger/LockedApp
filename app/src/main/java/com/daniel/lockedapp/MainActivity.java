package com.daniel.lockedapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private View volumeStatus, noiseStatus, chargingStatus, brightnessStatus, wifiStatus;
    private EditText passwordInput;
    private Button loginButton;
    private BroadcastReceiver batteryReceiver;
    private String password;
    boolean isQuiet;
    boolean isWifiCorrect;
    // ContentObserver for brightness changes
    private ContentObserver brightnessObserver;

    private Thread noiseDetectionThread;
    private boolean isNoiseDetectionRunning;
    private AudioManager audioManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Initialize system services
        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        registerListeners();

        // Get initial password based on battery percentage
        password = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "";

        // Initialize UI elements
        initViews();

        // Start noise detection
        startNoiseDetection();

    }

    private void registerListeners() {
        registerVolumeListener();
        registerBatteryListener();
        registerWifiListener();


        // Register brightness observer
        registerBrightnessObserver();
    }

    private void registerWifiListener() {
        BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                isWifiCorrect = checkWifiName();
                checkConditionsUpdate();

            }
        };

        IntentFilter wifiFilter = new IntentFilter();
        wifiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiReceiver, wifiFilter);
    }

    private boolean checkWifiName() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        String wifiName = wifiManager.getConnectionInfo().getSSID();
        if (wifiName == null || wifiName.isEmpty()) {
            return false;
        }

        // Some devices return SSID with quotes (e.g., "OpenSesame"), so trim quotes
        wifiName = wifiName.replaceAll("^\"|\"$", "");
        return wifiName.equals("OpenSesame");
    }


    private void registerVolumeListener() {
        BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkConditionsUpdate();
            }
        };

        IntentFilter volumeFilter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeReceiver, volumeFilter);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the brightness observer when the activity is destroyed
        if (brightnessObserver != null) {
            getContentResolver().unregisterContentObserver(brightnessObserver);
        }
        // Unregister battery receiver
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
        }
        // Stop noise detection
        stopNoiseDetection();
    }

    private void registerBatteryListener() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkConditionsUpdate();
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
    }

    private void registerBrightnessObserver() {
        brightnessObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                // Brightness has changed, recheck conditions
                checkConditionsUpdate();
            }
        };

        // Register the observer for brightness changes
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                true,
                brightnessObserver
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && !isNoiseDetectionRunning) {
            startNoiseDetection();
        }
        isWifiCorrect = checkWifiName();
    }

    private void startNoiseDetection() {
        isNoiseDetectionRunning = true;

        noiseDetectionThread = new Thread(() -> {
            int bufferSize = AudioRecord.getMinBufferSize(
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );


            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopNoiseDetection();
                return;
            }

            AudioRecord audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            short[] buffer = new short[bufferSize];
            audioRecord.startRecording();

            while (isNoiseDetectionRunning) {
                int sound = audioRecord.read(buffer, 0, bufferSize);
                Log.d("sound","Sound: " + sound);
                // Calculate the root mean square of the audio buffer to get the noise level in dB
                if (sound > 0) {
                    double sum = 0;
                    for (short sample : buffer) {
                        sum += sample * sample;
                    }
                    double rms = Math.sqrt(sum / sound);
                    double db = 20 * Math.log10(rms);

                    // Update UI if noise level exceeds threshold
                    isQuiet = db < 50;
                    runOnUiThread(this::checkConditionsUpdate);

                }

                try {
                    Thread.sleep(500); // Adjust sampling rate as needed
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            audioRecord.stop();
            audioRecord.release();
        });

        noiseDetectionThread.start();
    }

    private void stopNoiseDetection() {
        isNoiseDetectionRunning = false;
        if (noiseDetectionThread != null) {
            try {
                noiseDetectionThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkConditionsUpdate() {
        boolean isCharging = checkChargeStatus();
        boolean isBrightnessLow = getScreenBrightness() < 75;
        boolean isVolumeLow = getVolumeLevel() < 50;  // Example threshold for low volume

        if (isCharging && isBrightnessLow && isVolumeLow && isQuiet && isWifiCorrect) {
            passwordInput.setEnabled(true);
            loginButton.setEnabled(true);
            loginButton.setBackgroundTintList(getColorStateList(R.color.colorPrimary));
        } else {
            passwordInput.setEnabled(false);
            loginButton.setEnabled(false);
            loginButton.setBackgroundTintList(getColorStateList(R.color.colorDisabled));
        }

        // Update UI elements
        updateStatus(wifiStatus, isWifiCorrect);
        updateStatus(chargingStatus, isCharging);
        updateStatus(brightnessStatus, isBrightnessLow);
        updateStatus(volumeStatus,isVolumeLow);
        updateStatus(noiseStatus, isQuiet);
    }

    private boolean checkChargeStatus() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING;
    }

    private int getScreenBrightness() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
            return 0; // Default to 0 if brightness cannot be retrieved
        }
    }

    private int getVolumeLevel() {
        // Get the current volume level of the media stream
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        // Get the maximum volume level for the media stream
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return (int) (((float) currentVolume / maxVolume) * 100);  // Convert to percentage
    }

    private void initViews() {
        volumeStatus = findViewById(R.id.volumeStatus);
        noiseStatus = findViewById(R.id.noiseStatus);
        wifiStatus = findViewById(R.id.wifiStatus);
        chargingStatus = findViewById(R.id.chargingStatus);
        brightnessStatus = findViewById(R.id.brightnessStatus);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);
        loginButton.setOnClickListener(v -> onLoginClick());

    }

    private void onLoginClick() {
        String enteredPassword = passwordInput.getText().toString();

        if (enteredPassword.equals(password)) {
            // Correct password
            Toast.makeText(this, "You unlocked the app!", Toast.LENGTH_SHORT).show();
            // Proceed with the app's functionality
        } else {
            // Incorrect password
            Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatus(View status, boolean isSuccess) {
        int color = isSuccess ? R.drawable.green_circle : R.drawable.red_circle;
        status.setBackgroundResource(color);
    }

}
