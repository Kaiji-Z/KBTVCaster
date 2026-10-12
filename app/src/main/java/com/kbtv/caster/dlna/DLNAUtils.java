package com.kbtv.caster.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.zxt.dlna.dmr.ZxtMediaPlayer;
import com.zxt.dlna.dmr.ZxtMediaRenderer;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;

import com.kbtv.caster.R;

public class DLNAUtils {
    private static String TAG = "DLNAUtils";
    private static volatile ZxtMediaRenderer mMediaRenderer = null;
    private static volatile ZxtMediaPlayer.PlaybackListener mPlaybackListener = null;
    private static ServiceConnection mServiceConnection = null;
    private static Context mAppContext = null;

    public static void setDLNANameSuffix(Context context, String nameSuffix){
        SharedPreferences sharedPreferences = context.getApplicationContext().getSharedPreferences("dlna_settings", 0);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("nameSuffix", nameSuffix);
        editor.commit();
        startDLNAService(context);
    }

    public static String getDLNANameSuffix(Context context){
        SharedPreferences sharedPreferences = context.getApplicationContext().getSharedPreferences("dlna_settings", 0);
        return sharedPreferences.getString("nameSuffix", "");
    }

    /**
     * Set playback listener for media playback control
     */
    public static void setPlaybackListener(ZxtMediaPlayer.PlaybackListener listener) {
        mPlaybackListener = listener;
        if (mMediaRenderer != null) {
            // Set listener on all players
            for (ZxtMediaPlayer player : mMediaRenderer.getMediaPlayers().values()) {
                player.setPlaybackListener(listener);
            }
        }
    }

    public static void startDLNAService(final Context context){
        // Store app context for unbinding later
        mAppContext = context.getApplicationContext();

        // Unbind existing connection to prevent leak on restart
        if (mServiceConnection != null) {
            try {
                context.unbindService(mServiceConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Previous service not registered: " + e.getMessage());
            }
            mServiceConnection = null;
        }

        ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                AndroidUpnpService upnpService = (AndroidUpnpService) service;
                Log.i(TAG, "DLNA: onServiceConnected");
                String dlnaName = context.getString(R.string.app_name);
                String dlnaNameSuffix = getDLNANameSuffix(context);
                if(!TextUtils.isEmpty(dlnaNameSuffix)) dlnaName += "(" + dlnaNameSuffix + ")";
                if(mMediaRenderer != null){
                    mMediaRenderer.stopAllMediaPlayers();
                    upnpService.getRegistry().removeAllLocalDevices();
                    mMediaRenderer = null;
                }
                mMediaRenderer = new ZxtMediaRenderer(1, dlnaName , context);
                upnpService.getRegistry().addDevice(mMediaRenderer.getDevice());

                // Set playback listener if available
                if (mPlaybackListener != null) {
                    for (ZxtMediaPlayer player : mMediaRenderer.getMediaPlayers().values()) {
                        player.setPlaybackListener(mPlaybackListener);
                    }
                }

                Log.i(TAG, context.getString(R.string.app_name) + " DLNA服务已启动");
                // Expose the device UDN for the host-side control-point script
                // (androidTest assets, dlna_control_point.py): it probes
                // /dev/<UDN>/desc to find the Cling stream port, then drives
                // SOAP through adb forward.
                try {
                    String udn = mMediaRenderer.getDevice().getIdentity().getUdn().toString();
                    Log.i(TAG, "UPnP device registered, UDN=" + udn);
                    com.kbtv.caster.trace.TraceRecorder.log(
                        "upnp_registered",
                        new kotlin.Pair[]{ new kotlin.Pair<>("udn", udn) }
                    );
                    // Ground truth for e2e tooling: the descriptor path Cling itself expects.
                    try {
                        java.net.URI descPath = upnpService.getConfiguration()
                                .getNamespace().getDescriptorPath(mMediaRenderer.getDevice());
                        Log.i(TAG, "UPnP descriptor path: " + descPath);
                        com.kbtv.caster.trace.TraceRecorder.log(
                            "upnp_descriptor_path",
                            new kotlin.Pair[]{ new kotlin.Pair<>("path", String.valueOf(descPath)) }
                        );
                    } catch (Throwable t2) {
                        Log.w(TAG, "descriptor path unavailable: " + t2);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to log UDN: " + t);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "DLNA: onServiceDisconnected");
            }
        };
        mServiceConnection = serviceConnection;
        context.bindService(
                new Intent(context, KbtvUpnpServiceImpl.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public static void stopDLNAService(){
        if(mMediaRenderer != null){
            mMediaRenderer.stopAllMediaPlayers();
            mMediaRenderer = null;
        }
        if(mServiceConnection != null && mAppContext != null){
            try {
                mAppContext.unbindService(mServiceConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Service not registered: " + e.getMessage());
            }
            mServiceConnection = null;
        }
    }
}
