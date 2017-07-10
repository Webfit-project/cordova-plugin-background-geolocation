package com.marianhello.bgloc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.marianhello.logging.LoggerManager;

import java.util.List;

/**
 * Created by Nicolas on 26/06/2017.
 */

public class WebfitLocationProvider extends AbstractLocationProvider implements LocationListener {

  private LocationManager locationManager;
  private LocationManager locationManagerConcurrent;
  private PrisGpsListener mGpsListener;

  private Integer minBatteryLevel = 10;
  private PowerManager.WakeLock wakeLock;
  private boolean isTracking;
  private  boolean aggressiveStart;

  protected WebfitLocationProvider(LocationService locationService) {
    super(locationService);
    PROVIDER_ID = 3;
  }

  public void onCreate() {
    super.onCreate();
    Log.d("WF","Creating Webfitlocationprovider");
    this.minBatteryLevel = config.getMinBattery();
    PowerManager pm = (PowerManager) locationService.getSystemService(Context.POWER_SERVICE);
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WF");
    wakeLock.acquire();

    locationManager = (LocationManager) locationService.getSystemService(Context.LOCATION_SERVICE);
    locationManagerConcurrent = (LocationManager) locationService.getSystemService(Context.LOCATION_SERVICE);

    registerReceiver(batteryLevelReceiver,  new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
  }

  private BroadcastReceiver batteryLevelReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {

      int rawlevel = intent.getIntExtra("level", -1);
      int scale = intent.getIntExtra("scale", -1);
      int level = -1;
      if (rawlevel >= 0 && scale > 0) {
        level = (rawlevel * 100) / scale;
      }
      Log.d("BATTERY","Battery level min: " + minBatteryLevel);
      if(minBatteryLevel >= level)
      {
        stopRecording();
      }
      Log.d("BATTERY","Battery Level Remaining: " + level + "%");
    }
  };
  @Override
  public void onLocationChanged(Location location) {

    if(aggressiveStart)
    {
      if(location.getAccuracy() <  config.getDesiredAccuracy() || (config.getDesiredAccuracy() < 20 && location.getAccuracy() < 35 ))
      {
        this.aggressiveToReal();
      }
    }


    if (config.isDebugging()) {
      Toast.makeText(locationService, "acy:" + location.getAccuracy() + ",v:" + location.getSpeed() + ",df:" + config.getDistanceFilter(), Toast.LENGTH_LONG).show();
    }

    if (lastLocation != null && location.distanceTo(lastLocation) < config.getDistanceFilter()) {
      return;
    }

    if (config.isDebugging()) {
      startTone(Tone.BEEP);
    }

    lastLocation = location;
    handleLocation(location);
  }

  public Location getLastBestLocation() {
    Location bestResult = null;
    String bestProvider = null;
    float bestAccuracy = Float.MAX_VALUE;
    long bestTime = Long.MIN_VALUE;
    long minTime = System.currentTimeMillis() - +300000;

    Log.d("WF","Fetching last best location: radius="+config.getStationaryRadius()+" minTime="+minTime+"");

    try {
      // Iterate through all the providers on the system, keeping
      // note of the most accurate result within the acceptable time limit.
      // If no result is found within maxTime, return the newest Location.
      List<String> matchingProviders = locationManager.getAllProviders();
      for (String provider: matchingProviders) {
        Location location = locationManager.getLastKnownLocation(provider);
        if (location != null) {
          Log.d("WF","Test provider="+provider+" lat="+location.getLatitude()+" lon="+location.getLongitude()+" acy="+location.getAccuracy()+" v="+location.getSpeed()+"m/s time="+location.getTime()+"");
          float accuracy = location.getAccuracy();
          long time = location.getTime();
          if ((time > minTime && accuracy < bestAccuracy)) {
            bestProvider = provider;
            bestResult = location;
            bestAccuracy = accuracy;
            bestTime = time;
          }
        }
      }

      if (bestResult != null) {
        Log.d("WF","Best result found provider="+bestProvider+" lat="+bestResult.getLatitude()+" lon="+bestResult.getLongitude()+" acy="+bestResult.getAccuracy()+" v="+bestResult.getSpeed()+"m/s time="+ bestResult.getTime());
      }
    } catch (SecurityException e) {
      Log.d("WF","Security exception: "+e.getMessage());
      this.handleSecurityException(e);
    }

    return bestResult;
  }
  public void aggressiveStart() {
    aggressiveStart = true;
    try {
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
      locationManagerConcurrent.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
      mGpsListener = new PrisGpsListener();
      locationManager.addGpsStatusListener(mGpsListener);
      Location loc = this.getLastBestLocation();
      if(loc != null)
        handleLocation(loc);
    }catch (SecurityException e) {
      Log.d("WF","Security exception: "+e.getMessage());
      this.handleSecurityException(e);
    }
  }

  public void aggressiveToReal() {
    aggressiveStart = false;
    locationManager.removeUpdates(this);
    locationManagerConcurrent.removeUpdates(this);
    try {


    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, config.getInterval(), config.getDistanceFilter(), this);
      Log.d("WF","Start Real tracking with fastestInterval="+config.getFastestInterval()+" interval="+ config.getInterval()+" activitiesInterval="+config.getActivitiesInterval()+" stopOnStillActivity="+config.getStopOnStillActivity());
    } catch (SecurityException e) {
      Log.d("WF","Security exception: "+e.getMessage());
      this.handleSecurityException(e);
    }
  }

  public void startTracking() {
    if (isTracking) { return; }

    try {

      this.aggressiveStart();

      isTracking = true;
      Log.d("WF","Start tracking with fastestInterval="+config.getFastestInterval()+" interval="+ config.getInterval()+" activitiesInterval="+config.getActivitiesInterval()+" stopOnStillActivity="+config.getStopOnStillActivity());
    } catch (SecurityException e) {
      Log.d("WF","Security exception: "+e.getMessage());
      this.handleSecurityException(e);
    }
  }

  public void stopTracking() {
    if (!isTracking) { return; }

    // LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
    isTracking = false;
    locationManager.removeUpdates(this);
   // unregisterReceiver(batteryLevelReceiver);

   // wakeLock.release();
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {

  }

  @Override
  public void onProviderEnabled(String provider) {

  }

  @Override
  public void onProviderDisabled(String provider) {

  }

  @Override
  public void startRecording() {
    this.startTracking();
  }

  @Override
  public void stopRecording() {
    this.stopTracking();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopRecording();

    unregisterReceiver(batteryLevelReceiver );
    wakeLock.release();
  }

  private class PrisGpsListener implements GpsStatus.Listener{



    @Override
    public void onGpsStatusChanged(int event) {
      Log.d("GPS", "onGpsStatusChanged: event=" + event);

    }

  }
}
