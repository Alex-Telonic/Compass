package com.aschlechter.compass;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SensorEventListener, BluetoothAdapter.LeScanCallback {
    private static final String TAG = "MainActivity";


    /* Vibration Service */
    private static final UUID VIBRATION_SERVICE = UUID.fromString("713d0000-503e-4c75-ba94-3148f18d941e");
    private static final UUID VIBRATION_CHARACTERISTIC = UUID.fromString("713d0003-503e-4c75-ba94-3148f18d941e");
    private static final int REQUEST_ENABLE_BT = 1;
    private static final String TECO_WEARABLE_2 = "EF:EA:F0:C2:F5:5E";
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTED = 0;


    private BluetoothAdapter bluetoothAdapter;
    private SparseArray<BluetoothDevice> mDevices;
    private Handler handler = new Handler();
    private BluetoothGatt mConnectedGatt;
    private BluetoothGattService service;
    private BluetoothGattCharacteristic characteristic;


    //private ProgressDialog mProgress;

    ImageView compass_img;
    TextView txt_compass;
    int mAzimuth;
    private SensorManager mSensorManager;
    private Sensor mRotationV, mAccelerometer, mMagnetometer;
    boolean haveSensor = false, haveSensor2 = false;
    float[] rMat = new float[9];
    float[] orientation = new float[3];
    private float[] mLastAccelerometer = new float[3];
    private float[] mLastMagnetometer = new float[3];
    private boolean mLastAccelerometerSet = false;
    private boolean mLastMagnetometerSet = false;
    private String where = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        compass_img = (ImageView) findViewById(R.id.img_compass);
        txt_compass = (TextView) findViewById(R.id.txt_azimuth);


        /*View.OnClickListener startListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        };
        */
        mDevices = new SparseArray<BluetoothDevice>();
        // Initializes Bluetooth adapter.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        /*
        mProgress = new ProgressDialog(this);
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);
        */


    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_scan, menu);

        for (int i=0; i < mDevices.size(); i++) {
            BluetoothDevice device = mDevices.valueAt(i);
            menu.add(0, mDevices.keyAt(i), 0, device.getName());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_disconnect:
                mConnectedGatt.disconnect();
                mConnectedGatt.close();
            case R.id.menu_scan:
                mDevices.clear();
                //start();
                startScan();
                Log.i(TAG, "onOptionsItemSelected: Scan Button selected");
                return true;
                default:
                    BluetoothDevice device = mDevices.get(item.getItemId());
                    Log.i(TAG, "Connecting to" + device.getName());
                    mConnectedGatt = device.connectGatt(this, false, gattCallback);
                    //mConnectedGatt = device.connectGatt(this, true, mGattCallback);
        }

        return super.onOptionsItemSelected(item);
    }

    private Runnable StopRunnable = new Runnable() {
        @Override
        public void run() {
            MainActivity.this.stopScan();
        }
    };
    private Runnable StartRunnable = new Runnable() {
        @Override
        public void run() {
            MainActivity.this.stopScan();
        }
    };

    private void startScan() {
        // Stops scanning after 2,5 seconds.
        final long SCAN_PERIOD = 2500;
        Log.i(TAG, "startScan: ");




        bluetoothAdapter.startLeScan(this);
        handler.postDelayed(StopRunnable, SCAN_PERIOD);


    }

    private void stopScan() {
        bluetoothAdapter.stopLeScan(this);
    }

    public void stop() {
        if (haveSensor && haveSensor2) {
            mSensorManager.unregisterListener(this, mAccelerometer);
            mSensorManager.unregisterListener(this, mMagnetometer);
        } else {
            if (haveSensor)
                mSensorManager.unregisterListener(this, mRotationV);
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause: disconnect from wearable");
        mConnectedGatt.disconnect();
        
        //mHandler.removeCallbacks(mStopRunnable);

        //stop();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);

        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support", Toast.LENGTH_SHORT);
        }

        //start();
    }

    private void start() {
        Log.d(TAG, "start: reading in sensor values");
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) { //try to get Rotation Vector
            if ((mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) //if there is not rotation vector, try to get accelerometer and magnetic field
                    || (mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) == null)) {
                noSensorsAlert();//no sensors available
            } else {
                mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                haveSensor = mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
                haveSensor2 = mSensorManager.registerListener(this, mMagnetometer, SensorManager.SENSOR_DELAY_UI);
            }
        } else {
            mRotationV = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            haveSensor = mSensorManager.registerListener(this, mRotationV, SensorManager.SENSOR_DELAY_UI);
        }



        Log.d(TAG, "start: finished reading in sensor values");

    }


    private void noSensorsAlert() {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setMessage("Your device doesn't support the Compass.")
                .setCancelable(false)
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        finish();
                    }
                });
        alertDialog.show();

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rMat, event.values); //create rotation matrix using rotate vector values
            mAzimuth = (int) (Math.toDegrees(SensorManager.getOrientation(rMat, orientation)[0]) + 360) % 360;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, mLastAccelerometer, 0, event.values.length);
            mLastAccelerometerSet = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, mLastMagnetometer, 0, event.values.length);
            mLastMagnetometerSet = true;
        }
        if (mLastAccelerometerSet && mLastMagnetometerSet) {
            SensorManager.getRotationMatrix(rMat, null, mLastAccelerometer, mLastMagnetometer);
            SensorManager.getOrientation(rMat, orientation);
            mAzimuth = (int) (Math.toDegrees(SensorManager.getOrientation(rMat, orientation)[0]) + 360) % 360;
        }

        mAzimuth = Math.round(mAzimuth);
        compass_img.setRotation(-mAzimuth);

        /*
        if (mConnectedGatt != null) {
            //BluetoothGattService service = mConnectedGatt.getService(VIBRATION_SERVICE);
            //BluetoothGattCharacteristic characteristic = service.getCharacteristic(VIBRATION_CHARACTERISTIC);
            System.out.println(mConnectedGatt.getServices().toString());
        }
        */
        //BluetoothGattService service = mConnectedGatt.getService(VIBRATION_SERVICE);
        //BluetoothGattCharacteristic characteristic = service.getCharacteristic(VIBRATION_CHARACTERISTIC);
        //Log.i(TAG, "onSensorChanged: new value set");

        //characteristic.setValue(42949672,BluetoothGattCharacteristic.FORMAT_UINT16, 0);
        //characteristic.setValue(new byte[]{0x11, 0x11, 0x11, 0x11});


        //characteristic.setValue(255,BluetoothGattCharacteristic.FORMAT_UINT16, 1);
        //characteristic.setValue(16777215,BluetoothGattCharacteristic.FORMAT_UINT16, 1);
        //System.out.println("Value is: " + characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16,1));
        // BluetoothGattService service = mConnectedGatt.getService(VIBRATION_SERVICE);
        //BluetoothGattCharacteristic characteristic = service.getCharacteristic(VIBRATION_CHARACTERISTIC);

        byte[] arrayleft = hexStringToByteArray("FF000000");
        byte[] arrayfrontleft = hexStringToByteArray("00FF0000");
        byte[] arrayfrontright = hexStringToByteArray("0000FF00");
        byte[] arrayright = hexStringToByteArray("000000FF"); //broken one
        byte[] arrayoff = hexStringToByteArray("00000000");
        /*
        byte[] array1 = {(byte)0x00, (byte)0x00, (byte)0xFF, (byte)0x00};
        byte[] array0 = {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        */

        //System.out.println("Value is: " + characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16,1));
        //System.out.println("Value 1 is " + characteristic.getValue().toString());




        if (mAzimuth >= 350 || mAzimuth <= 10) { //vorne links
            if (where.equals("N") == false) {
                System.out.println(where);
                characteristic.setValue(arrayfrontleft);
                mConnectedGatt.writeCharacteristic(characteristic);
                where = "N";
            }
            System.out.println("where already on North");
            //where = "N";
        }

            //characteristic.setValue(array);
        if (mAzimuth < 350 && mAzimuth > 280) {
            if (!where.equals("NW")) {
                characteristic.setValue(arrayoff);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "NW";
        }
        if (mAzimuth <= 280 && mAzimuth > 260) { //vorne Rechts vibrieren
            if (!where.equals("W")) {
                characteristic.setValue(arrayfrontright);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "W";
        }
        if (mAzimuth <= 260 && mAzimuth > 190) {
            if (!where.equals("SW")) {
                characteristic.setValue(arrayoff);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "SW";
        }
        if (mAzimuth <= 190 && mAzimuth > 170) { //Rechts vibrieren
            if (!where.equals("S")) {
                characteristic.setValue(arrayright);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "S";
        }

        if (mAzimuth <= 170 && mAzimuth > 100) {
            if (!where.equals("SE")) {
                characteristic.setValue(arrayoff);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "SE";
        }
        if (mAzimuth <= 100 && mAzimuth > 80) { //links vibrieren
            if (!where.equals("E")) {
                characteristic.setValue(arrayleft);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "E";
        }
        if (mAzimuth <= 80 && mAzimuth > 10)  {
            if (!where.equals("NE")) {
                characteristic.setValue(arrayoff);
                mConnectedGatt.writeCharacteristic(characteristic);
            }
            where = "NE";
        }


        txt_compass.setText(mAzimuth + "° " + where);

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }


    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

        //Log.i(TAG, "New LE Device: " + device.getName() + " @ " + rssi);
        if (device.getName() != null) {
            if (device.getAddress().equals(TECO_WEARABLE_2)) {
                mDevices.put(device.hashCode(), device);
            }
                //mDevices.put(device.hashCode(), device);

        }
        invalidateOptionsMenu();
    }

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.i(TAG, "onCharacteristicWrite: Value has changed");
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: Status changed " + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                Log.i(TAG, "Attempting to start service discovery:");
                mConnectedGatt.discoverServices();


                if (mConnectedGatt != null) {
                    //BluetoothGattService service = mConnectedGatt.getService(VIBRATION_SERVICE);
                    //BluetoothGattCharacteristic characteristic = service.getCharacteristic(VIBRATION_CHARACTERISTIC);
                    //System.out.println("Available Services" + mConnectedGatt.toString());
                    //System.out.println("Available Services" + mConnectedGatt.getServices().toString());
                }
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                stop();
            }

        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services successful discovered");
                start();
                service = mConnectedGatt.getService(VIBRATION_SERVICE);
                characteristic = service.getCharacteristic(VIBRATION_CHARACTERISTIC);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
        }
        
        
    };

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }


}
