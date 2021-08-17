/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.btservice;

import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Attributable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.pan.PanService;
import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

// Describes the phone policy
//
// The policy should be as decoupled from the stack as possible. In an ideal world we should not
// need to have this policy talk with any non-public APIs and one way to enforce that would be to
// keep this file outside the Bluetooth process. Unfortunately, keeping a separate process alive is
// an expensive and a tedious task.
//
// Best practices:
// a) PhonePolicy should be ALL private methods
//    -- Use broadcasts which can be listened in on the BroadcastReceiver
// b) NEVER call from the PhonePolicy into the Java stack, unless public APIs. It is OK to call into
// the non public versions as long as public versions exist (so that a 3rd party policy can mimick)
// us.
//
// Policy description:
//
// Policies are usually governed by outside events that may warrant an action. We talk about various
// events and the resulting outcome from this policy:
//
// 1. Adapter turned ON: At this point we will try to auto-connect the (device, profile) pairs which
// have PRIORITY_AUTO_CONNECT. The fact that we *only* auto-connect Headset and A2DP is something
// that is hardcoded and specific to phone policy (see autoConnect() function)
// 2. When the profile connection-state changes: At this point if a new profile gets CONNECTED we
// will try to connect other profiles on the same device. This is to avoid collision if devices
// somehow end up trying to connect at same time or general connection issues.
class PhonePolicy {
    private static final boolean DBG = true;
    private static final String TAG = "BluetoothPhonePolicy";

    // Message types for the handler (internal messages generated by intents or timeouts)
    private static final int MESSAGE_PROFILE_CONNECTION_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_INIT_PRIORITIES = 2;
    private static final int MESSAGE_CONNECT_OTHER_PROFILES = 3;
    private static final int MESSAGE_ADAPTER_STATE_TURNED_ON = 4;
    private static final int MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED = 5;
    private static final int MESSAGE_DEVICE_CONNECTED = 6;

    // Timeouts
    @VisibleForTesting static int sConnectOtherProfilesTimeoutMillis = 6000; // 6s

    private DatabaseManager mDatabaseManager;
    private final AdapterService mAdapterService;
    private final ServiceFactory mFactory;
    private final Handler mHandler;
    private final HashSet<BluetoothDevice> mHeadsetRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mA2dpRetrySet = new HashSet<>();
    private final HashSet<BluetoothDevice> mConnectOtherProfilesDeviceSet = new HashSet<>();

    // Broadcast receiver for all changes to states of various profiles
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                errorLog("Received intent with null action");
                return;
            }
            switch (action) {
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.HEADSET, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.A2DP, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                            BluetoothProfile.LE_AUDIO, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED,
                            BluetoothProfile.A2DP, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED,
                            BluetoothProfile.HEADSET, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED,
                            BluetoothProfile.HEARING_AID, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED,
                            BluetoothProfile.LE_AUDIO, -1, // No-op argument
                            intent).sendToTarget();
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    // Only pass the message on if the adapter has actually changed state from
                    // non-ON to ON. NOTE: ON is the state depicting BREDR ON and not just BLE ON.
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (newState == BluetoothAdapter.STATE_ON) {
                        mHandler.obtainMessage(MESSAGE_ADAPTER_STATE_TURNED_ON).sendToTarget();
                    }
                    break;
                case BluetoothDevice.ACTION_UUID:
                    mHandler.obtainMessage(MESSAGE_PROFILE_INIT_PRIORITIES, intent).sendToTarget();
                    break;
                case BluetoothDevice.ACTION_ACL_CONNECTED:
                    mHandler.obtainMessage(MESSAGE_DEVICE_CONNECTED, intent).sendToTarget();
                default:
                    Log.e(TAG, "Received unexpected intent, action=" + action);
                    break;
            }
        }
    };

    @VisibleForTesting
    BroadcastReceiver getBroadcastReceiver() {
        return mReceiver;
    }

    // Handler to handoff intents to class thread
    class PhonePolicyHandler extends Handler {
        PhonePolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PROFILE_INIT_PRIORITIES: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    debugLog("Received ACTION_UUID for device " + device);
                    if (uuids != null) {
                        ParcelUuid[] uuidsToSend = new ParcelUuid[uuids.length];
                        for (int i = 0; i < uuidsToSend.length; i++) {
                            uuidsToSend[i] = (ParcelUuid) uuids[i];
                            debugLog("index=" + i + "uuid=" + uuidsToSend[i]);
                        }
                        processInitProfilePriorities(device, uuidsToSend);
                    }
                }
                break;

                case MESSAGE_PROFILE_CONNECTION_STATE_CHANGED: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                    int nextState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    processProfileStateChanged(device, msg.arg1, nextState, prevState);
                }
                break;

                case MESSAGE_PROFILE_ACTIVE_DEVICE_CHANGED: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice activeDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    processActiveDeviceChanged(activeDevice, msg.arg1);
                }
                break;

                case MESSAGE_CONNECT_OTHER_PROFILES: {
                    // Called when we try connect some profiles in processConnectOtherProfiles but
                    // we send a delayed message to try connecting the remaining profiles
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    Attributable.setAttributionSource(device,
                            ActivityThread.currentAttributionSource());
                    processConnectOtherProfiles(device);
                    mConnectOtherProfilesDeviceSet.remove(device);
                    break;
                }
                case MESSAGE_ADAPTER_STATE_TURNED_ON:
                    // Call auto connect when adapter switches state to ON
                    resetStates();
                    autoConnect();
                    break;
                case MESSAGE_DEVICE_CONNECTED:
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    processDeviceConnected(device);
            }
        }
    }

    ;

    // Policy API functions for lifecycle management (protected)
    protected void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);
        mAdapterService.registerReceiver(mReceiver, filter);
    }

    protected void cleanup() {
        mAdapterService.unregisterReceiver(mReceiver);
        resetStates();
    }

    PhonePolicy(AdapterService service, ServiceFactory factory) {
        mAdapterService = service;
        mDatabaseManager = Objects.requireNonNull(mAdapterService.getDatabase(),
                "DatabaseManager cannot be null when PhonePolicy starts");
        mFactory = factory;
        mHandler = new PhonePolicyHandler(service.getMainLooper());
    }

    // Policy implementation, all functions MUST be private
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void processInitProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids) {
        debugLog("processInitProfilePriorities() - device " + device);
        HidHostService hidService = mFactory.getHidHostService();
        A2dpService a2dpService = mFactory.getA2dpService();
        HeadsetService headsetService = mFactory.getHeadsetService();
        PanService panService = mFactory.getPanService();
        HearingAidService hearingAidService = mFactory.getHearingAidService();

        // Set profile priorities only for the profiles discovered on the remote device.
        // This avoids needless auto-connect attempts to profiles non-existent on the remote device
        if ((hidService != null) && (Utils.arrayContains(uuids, BluetoothUuid.HID)
                || Utils.arrayContains(uuids, BluetoothUuid.HOGP)) && (
                hidService.getConnectionPolicy(device)
                        == BluetoothProfile.CONNECTION_POLICY_UNKNOWN)) {
            mAdapterService.getDatabase().setProfileConnectionPolicy(device,
                    BluetoothProfile.HID_HOST, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }

        // If we do not have a stored priority for HFP/A2DP (all roles) then default to on.
        if ((headsetService != null) && ((Utils.arrayContains(uuids, BluetoothUuid.HSP)
                || Utils.arrayContains(uuids, BluetoothUuid.HFP)) && (
                headsetService.getConnectionPolicy(device)
                        == BluetoothProfile.CONNECTION_POLICY_UNKNOWN))) {
            mAdapterService.getDatabase().setProfileConnectionPolicy(device,
                    BluetoothProfile.HEADSET, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }

        if ((a2dpService != null) && (Utils.arrayContains(uuids, BluetoothUuid.A2DP_SINK)
                || Utils.arrayContains(uuids, BluetoothUuid.ADV_AUDIO_DIST)) && (
                a2dpService.getConnectionPolicy(device)
                        == BluetoothProfile.CONNECTION_POLICY_UNKNOWN)) {
            mAdapterService.getDatabase().setProfileConnectionPolicy(device,
                    BluetoothProfile.A2DP, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }

        if ((panService != null) && (Utils.arrayContains(uuids, BluetoothUuid.PANU) && (
                panService.getConnectionPolicy(device)
                        == BluetoothProfile.CONNECTION_POLICY_UNKNOWN)
                && mAdapterService.getResources()
                .getBoolean(R.bool.config_bluetooth_pan_enable_autoconnect))) {
            mAdapterService.getDatabase().setProfileConnectionPolicy(device,
                    BluetoothProfile.PAN, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }

        if ((hearingAidService != null) && Utils.arrayContains(uuids,
                BluetoothUuid.HEARING_AID) && (hearingAidService.getConnectionPolicy(device)
                == BluetoothProfile.CONNECTION_POLICY_UNKNOWN)) {
            debugLog("setting hearing aid profile priority for device " + device);
            mAdapterService.getDatabase().setProfileConnectionPolicy(device,
                    BluetoothProfile.HEARING_AID, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private void processProfileStateChanged(BluetoothDevice device, int profileId, int nextState,
            int prevState) {
        debugLog("processProfileStateChanged, device=" + device + ", profile=" + profileId + ", "
                + prevState + " -> " + nextState);
        if (((profileId == BluetoothProfile.A2DP) || (profileId == BluetoothProfile.HEADSET)
                || (profileId == BluetoothProfile.LE_AUDIO))) {
            if (nextState == BluetoothProfile.STATE_CONNECTED) {
                switch (profileId) {
                    case BluetoothProfile.A2DP:
                        mA2dpRetrySet.remove(device);
                        break;
                    case BluetoothProfile.HEADSET:
                        mHeadsetRetrySet.remove(device);
                        break;
                }
                connectOtherProfile(device);
            }
            if (nextState == BluetoothProfile.STATE_DISCONNECTED) {
                if (profileId == BluetoothProfile.A2DP) {
                    mDatabaseManager.setDisconnection(device);
                }
                handleAllProfilesDisconnected(device);
            }
        }
    }

    /**
     * Updates the last connection date in the connection order database for the newly active device
     * if connected to a2dp profile
     *
     * @param device is the device we just made the active device
     */
    private void processActiveDeviceChanged(BluetoothDevice device, int profileId) {
        debugLog("processActiveDeviceChanged, device=" + device + ", profile=" + profileId);

        if (device != null) {
            mDatabaseManager.setConnection(device, profileId == BluetoothProfile.A2DP);
        }
    }

    private void processDeviceConnected(BluetoothDevice device) {
        debugLog("processDeviceConnected, device=" + device);
        mDatabaseManager.setConnection(device, false);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private boolean handleAllProfilesDisconnected(BluetoothDevice device) {
        boolean atLeastOneProfileConnectedForDevice = false;
        boolean allProfilesEmpty = true;
        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();

        if (hsService != null) {
            List<BluetoothDevice> hsConnDevList = hsService.getConnectedDevices();
            allProfilesEmpty &= hsConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= hsConnDevList.contains(device);
        }
        if (a2dpService != null) {
            List<BluetoothDevice> a2dpConnDevList = a2dpService.getConnectedDevices();
            allProfilesEmpty &= a2dpConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= a2dpConnDevList.contains(device);
        }
        if (panService != null) {
            List<BluetoothDevice> panConnDevList = panService.getConnectedDevices();
            allProfilesEmpty &= panConnDevList.isEmpty();
            atLeastOneProfileConnectedForDevice |= panConnDevList.contains(device);
        }

        if (!atLeastOneProfileConnectedForDevice) {
            // Consider this device as fully disconnected, don't bother connecting others
            debugLog("handleAllProfilesDisconnected: all profiles disconnected for " + device);
            mHeadsetRetrySet.remove(device);
            mA2dpRetrySet.remove(device);
            if (allProfilesEmpty) {
                debugLog("handleAllProfilesDisconnected: all profiles disconnected for all"
                        + " devices");
                // reset retry status so that in the next round we can start retrying connections
                resetStates();
            }
            return true;
        }
        return false;
    }

    private void resetStates() {
        mHeadsetRetrySet.clear();
        mA2dpRetrySet.clear();
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    private void autoConnect() {
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            errorLog("autoConnect: BT is not ON. Exiting autoConnect");
            return;
        }

        if (!mAdapterService.isQuietModeEnabled()) {
            debugLog("autoConnect: Initiate auto connection on BT on...");
            final BluetoothDevice mostRecentlyActiveA2dpDevice =
                    mDatabaseManager.getMostRecentlyConnectedA2dpDevice();
            if (mostRecentlyActiveA2dpDevice == null) {
                errorLog("autoConnect: most recently active a2dp device is null");
                return;
            }
            debugLog("autoConnect: Device " + mostRecentlyActiveA2dpDevice
                    + " attempting auto connection");
            autoConnectHeadset(mostRecentlyActiveA2dpDevice);
            autoConnectA2dp(mostRecentlyActiveA2dpDevice);
        } else {
            debugLog("autoConnect() - BT is in quiet mode. Not initiating auto connections");
        }
    }

    private void autoConnectA2dp(BluetoothDevice device) {
        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            warnLog("autoConnectA2dp: service is null, failed to connect to " + device);
            return;
        }
        int a2dpConnectionPolicy = a2dpService.getConnectionPolicy(device);
        if (a2dpConnectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            debugLog("autoConnectA2dp: connecting A2DP with " + device);
            a2dpService.connect(device);
        } else {
            debugLog("autoConnectA2dp: skipped auto-connect A2DP with device " + device
                    + " connectionPolicy " + a2dpConnectionPolicy);
        }
    }

    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    private void autoConnectHeadset(BluetoothDevice device) {
        final HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            warnLog("autoConnectHeadset: service is null, failed to connect to " + device);
            return;
        }
        int headsetConnectionPolicy = hsService.getConnectionPolicy(device);
        if (headsetConnectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            debugLog("autoConnectHeadset: Connecting HFP with " + device);
            hsService.connect(device);
        } else {
            debugLog("autoConnectHeadset: skipped auto-connect HFP with device " + device
                    + " connectionPolicy " + headsetConnectionPolicy);
        }
    }

    private void connectOtherProfile(BluetoothDevice device) {
        if (mAdapterService.isQuietModeEnabled()) {
            debugLog("connectOtherProfile: in quiet mode, skip connect other profile " + device);
            return;
        }
        if (mConnectOtherProfilesDeviceSet.contains(device)) {
            debugLog("connectOtherProfile: already scheduled callback for " + device);
            return;
        }
        mConnectOtherProfilesDeviceSet.add(device);
        Message m = mHandler.obtainMessage(MESSAGE_CONNECT_OTHER_PROFILES);
        m.obj = device;
        mHandler.sendMessageDelayed(m, sConnectOtherProfilesTimeoutMillis);
    }

    // This function is called whenever a profile is connected.  This allows any other bluetooth
    // profiles which are not already connected or in the process of connecting to attempt to
    // connect to the device that initiated the connection.  In the event that this function is
    // invoked and there are no current bluetooth connections no new profiles will be connected.
    @RequiresPermission(allOf = {
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            android.Manifest.permission.MODIFY_PHONE_STATE,
    })
    private void processConnectOtherProfiles(BluetoothDevice device) {
        debugLog("processConnectOtherProfiles, device=" + device);
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            warnLog("processConnectOtherProfiles, adapter is not ON " + mAdapterService.getState());
            return;
        }
        if (handleAllProfilesDisconnected(device)) {
            debugLog("processConnectOtherProfiles: all profiles disconnected for " + device);
            return;
        }

        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();

        if (hsService != null) {
            if (!mHeadsetRetrySet.contains(device) && (hsService.getConnectionPolicy(device)
                    == BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                    && (hsService.getConnectionState(device)
                    == BluetoothProfile.STATE_DISCONNECTED)) {
                debugLog("Retrying connection to Headset with device " + device);
                mHeadsetRetrySet.add(device);
                hsService.connect(device);
            }
        }
        if (a2dpService != null) {
            if (!mA2dpRetrySet.contains(device) && (a2dpService.getConnectionPolicy(device)
                    == BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                    && (a2dpService.getConnectionState(device)
                    == BluetoothProfile.STATE_DISCONNECTED)) {
                debugLog("Retrying connection to A2DP with device " + device);
                mA2dpRetrySet.add(device);
                a2dpService.connect(device);
            }
        }
        if (panService != null) {
            List<BluetoothDevice> panConnDevList = panService.getConnectedDevices();
            // TODO: the panConnDevList.isEmpty() check below should be removed once
            // Multi-PAN is supported.
            if (panConnDevList.isEmpty() && (panService.getConnectionPolicy(device)
                    == BluetoothProfile.CONNECTION_POLICY_ALLOWED)
                    && (panService.getConnectionState(device)
                    == BluetoothProfile.STATE_DISCONNECTED)) {
                debugLog("Retrying connection to PAN with device " + device);
                panService.connect(device);
            }
        }
    }

    private static void debugLog(String msg) {
        if (DBG) {
            Log.i(TAG, msg);
        }
    }

    private static void warnLog(String msg) {
        Log.w(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
