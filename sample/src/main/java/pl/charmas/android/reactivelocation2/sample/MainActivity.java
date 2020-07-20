package pl.charmas.android.reactivelocation2.sample;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentSender;
import android.location.Address;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import pl.charmas.android.reactivelocation2.ReactiveLocationProvider;
import pl.charmas.android.reactivelocation2.ReactiveLocationProviderConfiguration;
import pl.charmas.android.reactivelocation2.sample.utils.AddressToStringFunc;
import pl.charmas.android.reactivelocation2.sample.utils.DetectedActivityToString;
import pl.charmas.android.reactivelocation2.sample.utils.DisplayTextOnViewAction;
import pl.charmas.android.reactivelocation2.sample.utils.LocationToStringFunc;
import pl.charmas.android.reactivelocation2.sample.utils.ToMostProbableActivity;

import java.util.List;
import java.util.Locale;

import static pl.charmas.android.reactivelocation2.sample.utils.UnsubscribeIfPresent.dispose;

public class MainActivity extends BaseActivity {
    private final static int REQUEST_CHECK_SETTINGS = 0;
    private final static String TAG = "MainActivity";
    private ReactiveLocationProvider locationProvider;

    private TextView lastKnownLocationView;
    private TextView updatableLocationView;
    private TextView addressLocationView;
    private TextView currentActivityView;

    private Observable<Location> lastKnownLocationObservable;
    private Observable<Location> locationUpdatesObservable;
    private Observable<ActivityRecognitionResult> activityObservable;

    private Disposable lastKnownLocationDisposable;
    private Disposable updatableLocationDisposable;
    private Disposable addressDisposable;
    private Disposable activityDisposable;
    private Observable<String> addressObservable;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lastKnownLocationView = findViewById(R.id.last_known_location_view);
        updatableLocationView = findViewById(R.id.updated_location_view);
        addressLocationView = findViewById(R.id.address_for_location_view);
        currentActivityView = findViewById(R.id.activity_recent_view);

        locationProvider = new ReactiveLocationProvider(getApplicationContext(), ReactiveLocationProviderConfiguration
                .builder()
                .setRetryOnConnectionSuspended(true)
                .build()
        );

        lastKnownLocationObservable = locationProvider
                .getLastKnownLocation()
                .observeOn(AndroidSchedulers.mainThread());

        final LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(5)
                .setInterval(100);
        locationUpdatesObservable = locationProvider
                .checkLocationSettings(
                        new LocationSettingsRequest.Builder()
                                .addLocationRequest(locationRequest)
                                .setAlwaysShow(true)  //Refrence: http://stackoverflow.com/questions/29824408/google-play-services-locationservices-api-new-option-never
                                .build()
                )
                .doOnNext(locationSettingsResult -> {
                    Status status = locationSettingsResult.getStatus();
                    if (status.getStatusCode() == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                        try {
                            status.startResolutionForResult(MainActivity.this, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException th) {
                            Log.e("MainActivity", "Error opening settings activity.", th);
                        }
                    }
                })
                .flatMap((Function<LocationSettingsResult, Observable<Location>>) locationSettingsResult -> locationProvider.getUpdatedLocation(locationRequest))
                .observeOn(AndroidSchedulers.mainThread());

        addressObservable = locationProvider.getUpdatedLocation(locationRequest)
                .flatMap((Function<Location, Observable<List<Address>>>) location -> locationProvider.getReverseGeocodeObservable(Locale.getDefault(), location.getLatitude(), location.getLongitude(), 1))
                .map(addresses -> addresses != null && !addresses.isEmpty() ? addresses.get(0) : null)
                .map(new AddressToStringFunc())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());

        activityObservable = locationProvider
                .getDetectedActivity(50)
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    protected void onLocationPermissionGranted() {
        lastKnownLocationDisposable = lastKnownLocationObservable
                .map(new LocationToStringFunc())
                .subscribe(new DisplayTextOnViewAction(lastKnownLocationView), new ErrorHandler());

        updatableLocationDisposable = locationUpdatesObservable
                .map(new LocationToStringFunc())
                .map(new Function<String, String>() {
                    int count = 0;

                    @Override
                    public String apply(String s) {
                        return s + " " + count++;
                    }
                })
                .subscribe(new DisplayTextOnViewAction(updatableLocationView), new ErrorHandler());


        addressDisposable = addressObservable
                .subscribe(new DisplayTextOnViewAction(addressLocationView), new ErrorHandler());

        activityDisposable = activityObservable
                .map(new ToMostProbableActivity())
                .map(new DetectedActivityToString())
                .subscribe(new DisplayTextOnViewAction(currentActivityView), new ErrorHandler());
    }

    @Override
    protected void onStop() {
        super.onStop();
        dispose(updatableLocationDisposable);
        dispose(addressDisposable);
        dispose(lastKnownLocationDisposable);
        dispose(activityDisposable);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Geofencing").setOnMenuItemClickListener(item -> {
            startActivity(new Intent(MainActivity.this, GeofenceActivity.class));
            return true;
        });
        menu.add("Places").setOnMenuItemClickListener(item -> {
            if (TextUtils.isEmpty(getString(R.string.API_KEY))) {
                Toast.makeText(MainActivity.this, "First you need to configure your API Key - see README.md", Toast.LENGTH_SHORT).show();
            } else {
                startActivity(new Intent(MainActivity.this, PlacesActivity.class));
            }
            return true;
        });
        menu.add("Mock Locations").setOnMenuItemClickListener(item -> {
            startActivity(new Intent(MainActivity.this, MockLocationsActivity.class));
            return true;
        });
        return true;
    }

    private class ErrorHandler implements Consumer<Throwable> {
        @Override
        public void accept(Throwable throwable) {
            Toast.makeText(MainActivity.this, "Error occurred.", Toast.LENGTH_SHORT).show();
            Log.d("MainActivity", "Error occurred", throwable);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final LocationSettingsStates states = LocationSettingsStates.fromIntent(data);//intent);
        switch (requestCode) {
            case REQUEST_CHECK_SETTINGS:
                //Reference: https://developers.google.com/android/reference/com/google/android/gms/location/SettingsApi
                switch (resultCode) {
                    case RESULT_OK:
                        // All required changes were successfully made
                        Log.d(TAG, "User enabled location");
                        break;
                    case RESULT_CANCELED:
                        // The user was asked to change settings, but chose not to
                        Log.d(TAG, "User Cancelled enabling location");
                        break;
                    default:
                        break;
                }
                break;
        }
    }

}
