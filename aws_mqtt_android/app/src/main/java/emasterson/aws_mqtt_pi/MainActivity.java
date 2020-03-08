package emasterson.aws_mqtt_pi;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttLastWillAndTestament;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.iot.AWSIotClient;
import com.amazonaws.services.iot.model.AttachPrincipalPolicyRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateRequest;
import com.amazonaws.services.iot.model.CreateKeysAndCertificateResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.KeyStore;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {
    EditText txtSubscribe;
    TextView tvLastMessage, tvClientId, tvStatus, tvTime, tvLed, tvTemp, tvHum,tvBuzzer, tvSound;
    ToggleButton btnConnect, btnSubscribe, btnLed, btnTemp, btnHum, btnBuzzer, btnSound, btnAll;
    private float time;

    static final String LOG_TAG = MainActivity.class.getCanonicalName();
    // --- Constants to modify per your configuration ---

    // IoT endpoint
    // AWS Iot CLI describe-endpoint call returns: XXXXXXXXXX.iot.<region>.amazonaws.com
    private static final String CUSTOMER_SPECIFIC_ENDPOINT = "";
    // Cognito pool ID. For this app, pool needs to be unauthenticated pool with
    // AWS IoT permissions.
    private static final String COGNITO_POOL_ID = "";
    // Name of the AWS IoT policy to attach to a newly created certificate
    private static final String AWS_IOT_POLICY_NAME = "mqtt_policy";
    // Region of AWS IoT
    private static final Regions MY_REGION = Regions.EU_WEST_1;
    // Filename of KeyStore file on the filesystem
    private static final String KEYSTORE_NAME = "iot_keystore";
    // Password for the private key in the KeyStore
    private static final String KEYSTORE_PASSWORD = "password";
    // Certificate and key aliases in the KeyStore
    private static final String CERTIFICATE_ID = "default";

    private static final String PI_TOPIC = "mqtt_message";

    AWSIotClient mIotAndroidClient;
    AWSIotMqttManager mqttManager;
    String clientId;
    String keystorePath;
    String keystoreName;
    String keystorePassword;

    KeyStore clientKeyStore = null;
    String certificateId;

    CognitoCachingCredentialsProvider credentialsProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtSubscribe = findViewById(R.id.txtSubscribe);
        tvLastMessage = findViewById(R.id.tvLastMessage);
        tvClientId = findViewById(R.id.tvClientId);
        tvStatus = findViewById(R.id.tvStatus);
        tvLed = findViewById(R.id.tvLed);
        tvTemp = findViewById(R.id.tvTemp);
        tvHum = findViewById(R.id.tvHum);
        tvBuzzer = findViewById(R.id.tvBuzzer);
        tvSound = findViewById(R.id.tvSound);

        btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnCheckedChangeListener(this);
        btnConnect.setEnabled(false);

        btnSubscribe = findViewById(R.id.btnSubscribe);
        btnSubscribe.setOnCheckedChangeListener(this);

        btnLed = findViewById(R.id.btnLed);
        btnLed.setOnCheckedChangeListener(this);

        btnTemp = findViewById(R.id.btnTemp);
        btnTemp.setOnCheckedChangeListener(this);

        btnHum = findViewById(R.id.btnHum);
        btnHum.setOnCheckedChangeListener(this);

        btnBuzzer = findViewById(R.id.btnBuzzer);
        btnBuzzer.setOnCheckedChangeListener(this);

        btnSound = findViewById(R.id.btnSound);
        btnSound.setOnCheckedChangeListener(this);

        btnAll = findViewById(R.id.btnAll);
        btnAll.setOnCheckedChangeListener(this);

        // MQTT client IDs are required to be unique per AWS IoT account.
        // This UUID is "practically unique" but does not _guarantee_
        // uniqueness.
        clientId = UUID.randomUUID().toString();
        tvClientId.setText(clientId);

        // Initialize the AWS Cognito credentials provider
        credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(), // context
                COGNITO_POOL_ID, // Identity Pool ID
                MY_REGION // Region
        );

        Region region = Region.getRegion(MY_REGION);
        // MQTT Client
        mqttManager = new AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT);

        // Set keepalive to 10 seconds.  Will recognize disconnects more quickly but will also send
        // MQTT pings every 10 seconds.
        mqttManager.setKeepAlive(10);

        // Set Last Will and Testament for MQTT.  On an unclean disconnect (loss of connection)
        // AWS IoT will publish this message to alert other clients.
        AWSIotMqttLastWillAndTestament lwt = new AWSIotMqttLastWillAndTestament("my/lwt/topic",
                "Android client lost connection", AWSIotMqttQos.QOS0);
        mqttManager.setMqttLastWillAndTestament(lwt);

        // IoT Client (for creation of certificate if needed)
        mIotAndroidClient = new AWSIotClient(credentialsProvider);
        mIotAndroidClient.setRegion(region);

        keystorePath = getFilesDir().getPath();
        keystoreName = KEYSTORE_NAME;
        keystorePassword = KEYSTORE_PASSWORD;
        certificateId = CERTIFICATE_ID;

        // To load cert/key from keystore on filesystem
        try {
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, keystoreName)) {
                if (AWSIotKeystoreHelper.keystoreContainsAlias(certificateId, keystorePath,
                        keystoreName, keystorePassword)) {
                    Log.i(LOG_TAG, "Certificate " + certificateId
                            + " found in keystore - using for MQTT.");
                    // load keystore from file into memory to pass on connection
                    clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                            keystorePath, keystoreName, keystorePassword);
                    btnConnect.setEnabled(true);
                } else {
                    Log.i(LOG_TAG, "Key/cert " + certificateId + " not found in keystore.");
                }
            } else {
                Log.i(LOG_TAG, "Keystore " + keystorePath + "/" + keystoreName + " not found.");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "An error occurred retrieving cert/key from keystore.", e);
        }

        if (clientKeyStore == null) {
            Log.i(LOG_TAG, "Cert/key was not found in keystore - creating new key and certificate.");

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Create a new private key and certificate. This call
                        // creates both on the server and returns them to the
                        // device.
                        CreateKeysAndCertificateRequest createKeysAndCertificateRequest =
                                new CreateKeysAndCertificateRequest();
                        createKeysAndCertificateRequest.setSetAsActive(true);
                        final CreateKeysAndCertificateResult createKeysAndCertificateResult;
                        createKeysAndCertificateResult =
                                mIotAndroidClient.createKeysAndCertificate(createKeysAndCertificateRequest);
                        Log.i(LOG_TAG,
                                "Cert ID: " +
                                        createKeysAndCertificateResult.getCertificateId() +
                                        " created.");

                        // store in keystore for use in MQTT client
                        // saved as alias "default" so a new certificate isn't
                        // generated each run of this application
                        AWSIotKeystoreHelper.saveCertificateAndPrivateKey(certificateId,
                                createKeysAndCertificateResult.getCertificatePem(),
                                createKeysAndCertificateResult.getKeyPair().getPrivateKey(),
                                keystorePath, keystoreName, keystorePassword);

                        // load keystore from file into memory to pass on
                        // connection
                        clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(certificateId,
                                keystorePath, keystoreName, keystorePassword);

                        // Attach a policy to the newly created certificate.
                        // This flow assumes the policy was already created in
                        // AWS IoT and we are now just attaching it to the
                        // certificate.
                        AttachPrincipalPolicyRequest policyAttachRequest =
                                new AttachPrincipalPolicyRequest();
                        policyAttachRequest.setPolicyName(AWS_IOT_POLICY_NAME);
                        policyAttachRequest.setPrincipal(createKeysAndCertificateResult
                                .getCertificateArn());
                        mIotAndroidClient.attachPrincipalPolicy(policyAttachRequest);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnConnect.setEnabled(true);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(LOG_TAG,
                                "Exception occurred when generating new private key and certificate.",
                                e);
                    }
                }
            }).start();
        }

        // Get value from seekbar to send time constraint in message
        SeekBar seekBar = findViewById(R.id.seekBar);
        tvTime = findViewById(R.id.tvTime);
        tvTime.setText("Time: "+String.valueOf((float) seekBar.getProgress()/10));
        time = (float) seekBar.getProgress()/10;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                tvTime.setText("Time: "+String.valueOf((float) seekBar.getProgress()/10));
                time = (float) seekBar.getProgress()/10;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    // Send message to topic on button press
    public void sendMessage(String message, Float time){
        JSONObject json = new JSONObject();
        try{
            json.put("message", message);
            json.put("time", time);
        } catch (JSONException e){
            e.printStackTrace();
        }

        try {
            mqttManager.publishString(json.toString(), PI_TOPIC, AWSIotMqttQos.QOS0);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Publish error.", e);
        }
    }

    // On successful connection, we subscribe to all topics to retrieve sensor readings
    public void subscribe(String topic){
        try {
            mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        String message = new String(data, "UTF-8");
                                        Log.d(LOG_TAG, "Message arrived:");
                                        Log.d(LOG_TAG, "   Topic: " + topic);
                                        Log.d(LOG_TAG, " Message: " + message);
                                        if(topic.contains("temp")) {
                                            tvTemp.setText(message);
                                        } else if(topic.contains("hum")) {
                                            tvHum.setText(message);
                                        } else if(topic.contains("led")) {
                                            tvLed.setText(message);
                                        } else if(topic.contains("buzzer")) {
                                            tvBuzzer.setText(message);
                                        } else if(topic.contains("sound")){
                                            tvSound.setText(message);
                                        }
                                    } catch (UnsupportedEncodingException e) {
                                        Log.e(LOG_TAG, "Message encoding error.", e);
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(LOG_TAG, "Subscription error.", e);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        JSONObject message = new JSONObject();
        switch(buttonView.getId()){
            case R.id.btnConnect:
                if(isChecked){
                    Log.d(LOG_TAG, "clientId = " + clientId);

                    try {
                        mqttManager.connect(clientKeyStore, new AWSIotMqttClientStatusCallback() {
                            @Override
                            public void onStatusChanged(final AWSIotMqttClientStatus status,
                                                        final Throwable throwable) {
                                Log.d(LOG_TAG, "Status = " + String.valueOf(status));

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (status == AWSIotMqttClientStatus.Connecting) {
                                            tvStatus.setText("Connecting...");

                                        } else if (status == AWSIotMqttClientStatus.Connected) {
                                            tvStatus.setText("Connected");
                                            subscribe("mqtt_pi/temp");
                                            subscribe("mqtt_pi/hum");
                                            subscribe("mqtt_pi/led");
                                            subscribe("mqtt_pi/buzzer");
                                            subscribe("mqtt_pi/sound");
                                        } else if (status == AWSIotMqttClientStatus.Reconnecting) {
                                            if (throwable != null) {
                                                Log.e(LOG_TAG, "Connection error.", throwable);
                                            }
                                            tvStatus.setText("Reconnecting");
                                        } else if (status == AWSIotMqttClientStatus.ConnectionLost) {
                                            if (throwable != null) {
                                                Log.e(LOG_TAG, "Connection error.", throwable);
                                            }
                                            tvStatus.setText("Disconnected");
                                        } else {
                                            tvStatus.setText("Disconnected");

                                        }
                                    }
                                });
                            }
                        });
                    } catch (final Exception e) {
                        Log.e(LOG_TAG, "Connection error.", e);
                        tvStatus.setText("Error! " + e.getMessage());
                    }
                    break;
                } else {
                    try {
                        mqttManager.disconnect();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Disconnect error.", e);
                    }
                    break;
                }
            case R.id.btnSubscribe:
                final String topic = txtSubscribe.getText().toString();
                if(!tvStatus.getText().equals("Connected")) {
                    Toast.makeText(getApplicationContext(), "Connect to Subscribe", Toast.LENGTH_LONG).show();
                    btnSubscribe.setChecked(false);
                    break;
                }
                else if(isChecked){
                    Log.d(LOG_TAG, "topic = " + topic);

                    try {
                        if(topic == null){
                            Toast.makeText(getApplicationContext(), "Enter Topic to Subscribe", Toast.LENGTH_LONG).show();
                            break;
                        }
                        mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0,
                                new AWSIotMqttNewMessageCallback() {
                                    @Override
                                    public void onMessageArrived(final String topic, final byte[] data) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    String message = new String(data, "UTF-8");
                                                    Log.d(LOG_TAG, "Message arrived:");
                                                    Log.d(LOG_TAG, "   Topic: " + topic);
                                                    Log.d(LOG_TAG, " Message: " + message);

                                                    tvLastMessage.setText(message);

                                                } catch (UnsupportedEncodingException e) {
                                                    Log.e(LOG_TAG, "Message encoding error.", e);
                                                }
                                            }
                                        });
                                    }
                                });
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Subscription error.", e);
                    }
                    break;
                } else {
                    try {
                        mqttManager.unsubscribeTopic(topic);
                        tvLastMessage.setText("Unsubscribed");
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Disconnect error.", e);
                    }
                    break;
                }
            case R.id.btnLed:
                if(isChecked){
                    sendMessage("led_on", time);
                    break;
                } else {
                    sendMessage("led_off", null);
                    break;
                }
            case R.id.btnTemp:
                if(isChecked){
                    sendMessage("temp_on", time);
                    break;
                } else {
                    sendMessage("temp_off", null);
                    break;
                }
            case R.id.btnHum:
                if(isChecked){
                    sendMessage("hum_on", time);
                    break;
                } else {
                    sendMessage("hum_off", null);
                    break;
                }
            case R.id.btnBuzzer:
                if(isChecked){
                    sendMessage("buzzer_on", time);
                    break;
                } else {
                    sendMessage("buzzer_off", null);
                    break;
                }
            case R.id.btnSound:
                if(isChecked){
                    sendMessage("sound_on", time);
                    break;
                } else {
                    sendMessage("sound_off", null);
                    break;
                }
            case R.id.btnAll:
                if(isChecked){
                    sendMessage("all_on", time);
                    break;
                } else {
                    sendMessage("all_off", null);
                    break;
                }
        }
    }
}
