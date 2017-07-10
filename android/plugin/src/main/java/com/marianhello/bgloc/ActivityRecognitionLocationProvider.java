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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
//import com.google.android.gms.location.LocationListener;

import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.marianhello.logging.LoggerManager;

import java.util.ArrayList;
import java.util.List;

public class ActivityRecognitionLocationProvider extends AbstractLocationProvider implements GoogleApiClient.ConnectionCallbacks,
  GoogleApiClient.OnConnectionFailedListener, LocationListener {

  private static final String TAG = ActivityRecognitionLocationProvider.class.getSimpleName();
  private static final String P_NAME = " com.marianhello.bgloc";
  private static final String DETECTED_ACTIVITY_UPDATE = P_NAME + ".DETECTED_ACTIVITY_UPDATE";

  private PowerManager.WakeLock wakeLock;
  private GoogleApiClient googleApiClient;
  private PendingIntent detectedActivitiesPI;

  private Integer minBatteryLevel = 10;

  private Boolean startRecordingOnConnect = true;
  private Boolean isTracking = false;
  private Boolean isWatchingActivity = false;
  private DetectedActivity lastActivity = new DetectedActivity(DetectedActivity.UNKNOWN, 100);

  private org.slf4j.Logger log;
  private LocationManager locationManager;
  private PrisGpsListener mGpsListener;

  public ActivityRecognitionLocationProvider(LocationService locationService) {
    super(locationService);
    PROVIDER_ID = 1;
  }

  public Location getLastBestLocation() {
    Location bestResult = null;
    String bestProvider = null;
    float bestAccuracy = Float.MAX_VALUE;
    long bestTime = Long.MIN_VALUE;
    long minTime = System.currentTimeMillis() - config.getInterval();

    log.info("Fetching last best location: radius={} minTime={}", config.getStationaryRadius(), minTime);

    try {
      // Iterate through all the providers on the system, keeping
      // note of the most accurate result within the acceptable time limit.
      // If no result is found within maxTime, return the newest Location.
      List<String> matchingProviders = locationManager.getAllProviders();
      for (String provider: matchingProviders) {
        Location location = locationManager.getLastKnownLocation(provider);
        if (location != null) {
          log.debug("Test provider={} lat={} lon={} acy={} v={}m/s time={}", provider, location.getLatitude(), location.getLongitude(), location.getAccuracy(), location.getSpeed(), location.getTime());
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
        log.debug("Best result found provider={} lat={} lon={} acy={} v={}m/s time={}", bestProvider, bestResult.getLatitude(), bestResult.getLongitude(), bestResult.getAccuracy(), bestResult.getSpeed(), bestResult.getTime());
      }
    } catch (SecurityException e) {
      log.error("Security exception: {}", e.getMessage());
      this.handleSecurityException(e);
    }

    return bestResult;
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
      log.info("Battery level min: " + minBatteryLevel);
      if(minBatteryLevel >= level)
      {
        stopRecording();
      }
      log.info("Battery Level Remaining: " + level + "%");
    }
  };


  public void onProviderDisabled(String provider) {
    // TODO Auto-generated method stub
    log.debug("Provider {} was disabled", provider);
  }

  public void onProviderEnabled(String provider) {
    // TODO Auto-generated method stub
    log.debug("Provider {} was enabled", provider);
  }

  public void onStatusChanged(String provider, int status, Bundle extras) {
    // TODO Auto-generated method stub
    log.debug("Provider {} status changed: {}", provider, status);
  }

  public void onCreate() {
    super.onCreate();

    log = LoggerManager.getLogger(ActivityRecognitionLocationProvider.class);
    log.info("Creating ActivityRecognitionLocationProvider");
    this.minBatteryLevel = config.getMinBattery();
    PowerManager pm = (PowerManager) locationService.getSystemService(Context.POWER_SERVICE);
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
    wakeLock.acquire();

    locationManager = (LocationManager) locationService.getSystemService(Context.LOCATION_SERVICE);



    Intent detectedActivitiesIntent = new Intent(DETECTED_ACTIVITY_UPDATE);
    detectedActivitiesPI = PendingIntent.getBroadcast(locationService, 9002, detectedActivitiesIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    registerReceiver(detectedActivitiesReceiver, new IntentFilter(DETECTED_ACTIVITY_UPDATE));
    registerReceiver(batteryLevelReceiver,  new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
  }

  @Override
  public void onLocationChanged(Location location) {
    log.debug("Location change: {}", location.toString());
/*
    if (lastActivity.getType() == DetectedActivity.STILL) {
      handleStationary(location);

      stopTracking();

      return;
    }
*/
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

  public void startRecording() {
    log.info("Start recording");
    this.startRecordingOnConnect = true;
    attachRecorder();
  }

  public void stopRecording() {
    log.info("Stop recording");
    this.startRecordingOnConnect = false;
    detachRecorder();
    stopTracking();
  }

  public void startTracking() {
    if (isTracking) { return; }

    Integer priority = translateDesiredAccuracy(config.getDesiredAccuracy());
    LocationRequest locationRequest = LocationRequest.create()
      .setPriority(priority) // this.accuracy
      .setFastestInterval(config.getFastestInterval())
      .setInterval(config.getInterval()).setSmallestDisplacement(config.getStationaryRadius());
    try {


      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, config.getInterval(), config.getDistanceFilter(), this);

      mGpsListener = new PrisGpsListener();
      locationManager.addGpsStatusListener(mGpsListener);
      Location loc = this.getLastBestLocation();
      if(loc != null)
        this.onLocationChanged(loc);

  // LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
      isTracking = true;
      log.debug("Start tracking with priority={} fastestInterval={} interval={} activitiesInterval={} stopOnStillActivity={}", priority, config.getFastestInterval(), config.getInterval(), config.getActivitiesInterval(), config.getStopOnStillActivity());
    } catch (SecurityException e) {
      log.error("Security exception: {}", e.getMessage());
      this.handleSecurityException(e);
    }
  }

  public void stopTracking() {
    if (!isTracking) { return; }

   // LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
    isTracking = false;
  }

  private void connectToPlayAPI() {
    log.debug("Connecting to Google Play Services");
    googleApiClient =  new GoogleApiClient.Builder(locationService)
      .addApi(LocationServices.API)
      .addApi(ActivityRecognition.API)
      .addConnectionCallbacks(this)
      //.addOnConnectionFailedListener(this)
      .build();
    googleApiClient.connect();
  }

  private void disconnectFromPlayAPI() {
    if (googleApiClient != null && googleApiClient.isConnected()) {
      googleApiClient.disconnect();
    }
  }

  private void attachRecorder() {
    if (googleApiClient == null) {
      connectToPlayAPI();
    } else if (googleApiClient.isConnected()) {
      //on pourrait tenter de garder iswatching a true;
      if (isWatchingActivity) { return; }
      startTracking();
      if (config.getStopOnStillActivity()) {
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
          googleApiClient,
          config.getActivitiesInterval(),
          detectedActivitiesPI
        );
        isWatchingActivity = true;
      }
    } else {
      googleApiClient.connect();
    }
  }

  private void detachRecorder() {
    if (isWatchingActivity) {
      log.debug("Detaching recorder");
      ActivityRecognition.ActivityRecognitionApi.removeActivityUpdates(googleApiClient, detectedActivitiesPI);
      isWatchingActivity = false;
    }
  }

  @Override
  public void onConnected(Bundle connectionHint) {
    log.debug("Connected to Google Play Services");
    if (this.startRecordingOnConnect) {
      attachRecorder();
    }
  }

  @Override
  public void onConnectionSuspended(int cause) {
    // googleApiClient.connect();
    log.info("Connection to Google Play Services suspended");
  }

  @Override
  public void onConnectionFailed(ConnectionResult connectionResult) {
    log.error("Connection to Google Play Services failed");
  }

  /**
   * Translates a number representing desired accuracy of Geolocation system from set [0, 10, 100, 1000].
   * 0:  most aggressive, most accurate, worst battery drain
   * 1000:  least aggressive, least accurate, best for battery.
   */
  private Integer translateDesiredAccuracy(Integer accuracy) {
    if (accuracy >= 10000) {
      return LocationRequest.PRIORITY_NO_POWER;
    }
    if (accuracy >= 1000) {
      return LocationRequest.PRIORITY_LOW_POWER;
    }
    if (accuracy >= 100) {
      return LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
    }
    if (accuracy >= 10) {
      return LocationRequest.PRIORITY_HIGH_ACCURACY;
    }
    if (accuracy >= 0) {
      return LocationRequest.PRIORITY_HIGH_ACCURACY;
    }

    return LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
  }


  public static DetectedActivity getProbableActivity(ArrayList<DetectedActivity> detectedActivities) {
    int highestConfidence = 0;
    DetectedActivity mostLikelyActivity = new DetectedActivity(0, DetectedActivity.UNKNOWN);

    for(DetectedActivity da: detectedActivities) {
      if(da.getType() != DetectedActivity.TILTING || da.getType() != DetectedActivity.UNKNOWN) {
        Log.w(TAG, "Received a Detected Activity that was not tilting / unknown");
        if (highestConfidence < da.getConfidence()) {
          highestConfidence = da.getConfidence();
          mostLikelyActivity = da;
        }
      }
    }
    return mostLikelyActivity;
  }

  public static String getActivityString(int detectedActivityType) {
    switch(detectedActivityType) {
      case DetectedActivity.IN_VEHICLE:
        return "IN_VEHICLE";
      case DetectedActivity.ON_BICYCLE:
        return "ON_BICYCLE";
      case DetectedActivity.ON_FOOT:
        return "ON_FOOT";
      case DetectedActivity.RUNNING:
        return "RUNNING";
      case DetectedActivity.STILL:
        return "STILL";
      case DetectedActivity.TILTING:
        return "TILTING";
      case DetectedActivity.UNKNOWN:
        return "UNKNOWN";
      case DetectedActivity.WALKING:
        return "WALKING";
      default:
        return "Unknown";
    }
  }

  private BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
      ArrayList<DetectedActivity> detectedActivities = (ArrayList) result.getProbableActivities();

      //Find the activity with the highest percentage
      lastActivity = getProbableActivity(detectedActivities);

      log.debug("Detected activity={} confidence={}", getActivityString(lastActivity.getType()), lastActivity.getConfidence());

      if (lastActivity.getType() == DetectedActivity.STILL) {
        if (config.isDebugging()) {
          Toast.makeText(context, "Detected STILL Activity", Toast.LENGTH_SHORT).show();
        }
        // stopTracking();
        // we will delay stop tracking after position is found
        // startTracking();
      } else {
        if (config.isDebugging()) {
          Toast.makeText(context, "Detected ACTIVE Activity", Toast.LENGTH_SHORT).show();
        }
        startTracking();
      }
      //else do nothing
    }
  };

  public void onDestroy() {
    super.onDestroy();
    log.info("Destroying ActivityRecognitionLocationProvider");
    stopRecording();
    disconnectFromPlayAPI();
    unregisterReceiver(detectedActivitiesReceiver);
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

