package com.example.geofare

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.core.content.ContextCompat

import androidx.lifecycle.compose.LocalLifecycleOwner

import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

import org.json.JSONObject

import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Hashtable
import java.util.Locale
import java.util.UUID

import kotlinx.coroutines.delay

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration


/* =========================================================
   FIREBASE
   Passenger app uses Anonymous Authentication and listens
   for driver confirmation from the website.
   ========================================================= */

val geoFareFirebaseAuth: FirebaseAuth
    get() = FirebaseAuth.getInstance()

val geoFareFirestore: FirebaseFirestore
    get() = FirebaseFirestore.getInstance()


fun ensureFirebaseAuthentication(
    onSuccess: (FirebaseUser) -> Unit,
    onFailure: (Exception) -> Unit
) {

    val auth =
        geoFareFirebaseAuth

    val currentUser =
        auth.currentUser

    if (currentUser != null) {

        onSuccess(
            currentUser
        )

        return
    }

    auth
        .signInAnonymously()

        .addOnSuccessListener { result ->

            val user = result.user

            if (user != null) {

                onSuccess(
                    user
                )

            } else {

                onFailure(
                    IllegalStateException(
                        "Firebase anonymous sign-in succeeded but returned no user."
                    )
                )
            }
        }

        .addOnFailureListener { exception ->

            onFailure(
                exception
            )
        }
}


fun saveTripToFirestore(
    tripId: String,
    plate: String,
    pickup: Location?,
    destination: GeoPoint?,
    distanceMeters: Double,
    fare: Double,
    timestamp: String,
    onSuccess: () -> Unit,
    onFailure: (Exception) -> Unit
) {

    ensureFirebaseAuthentication(

        onSuccess = { user ->

            val tripData =
                hashMapOf<String, Any?>(
                    "tripId" to tripId,
                    "passengerUid" to user.uid,
                    "plate" to plate.ifBlank {
                        "UNKNOWN"
                    },
                    "pickupLat" to (
                            pickup?.latitude ?: 0.0
                            ),
                    "pickupLng" to (
                            pickup?.longitude ?: 0.0
                            ),
                    "destinationLat" to (
                            destination?.latitude ?: 0.0
                            ),
                    "destinationLng" to (
                            destination?.longitude ?: 0.0
                            ),
                    "distanceKm" to (
                            distanceMeters / 1000.0
                            ),
                    "fare" to fare,
                    "passengerTimestamp" to timestamp,
                    "status" to "PENDING",
                    "routeStatus" to "WAITING_FOR_DRIVER",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

            geoFareFirestore
                .collection("trips")
                .document(tripId)
                .set(
                    tripData,
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .addOnSuccessListener {

                    onSuccess()
                }
                .addOnFailureListener { exception ->

                    onFailure(
                        exception
                    )
                }
        },

        onFailure = onFailure
    )
}


fun listenForDriverConfirmation(
    tripId: String,
    onConfirmed: () -> Unit,
    onDeclined: () -> Unit,
    onError: (Exception) -> Unit
): ListenerRegistration {

    return geoFareFirestore
        .collection("trips")
        .document(tripId)
        .addSnapshotListener { snapshot: DocumentSnapshot?, error: Exception? ->

            if (error != null) {

                onError(
                    error
                )

                return@addSnapshotListener
            }

            if (
                snapshot == null ||
                !snapshot.exists()
            ) {

                return@addSnapshotListener
            }

            val status =
                snapshot.getString(
                    "status"
                )

            when (status) {

                "CONFIRMED",
                "ACTIVE",
                "ON_ROUTE",
                "ROUTE_DEVIATION" -> {

                    onConfirmed()
                }

                "DECLINED" -> {

                    onDeclined()
                }
            }
        }
}


/* =========================================================
   TRIP HISTORY
   ========================================================= */

data class TripHistoryItem(
    val tripId: String,
    val plate: String,
    val distanceKm: Double,
    val fare: Double,
    val pickup: String,
    val destination: String,
    val status: String,
    val completedAt: String,
    val sortTime: Long
)


fun loadPassengerTripHistory(
    onSuccess: (List<TripHistoryItem>) -> Unit,
    onFailure: (Exception) -> Unit
) {

    ensureFirebaseAuthentication(

        onSuccess = { user ->

            geoFareFirestore
                .collection("trips")
                .whereEqualTo("passengerUid", user.uid)
                .get()
                .addOnSuccessListener { snapshot ->

                    val history =
                        snapshot.documents
                            .mapNotNull { document ->

                                val status =
                                    document.getString("status")
                                        ?: return@mapNotNull null

                                if (status != "COMPLETED") {
                                    return@mapNotNull null
                                }

                                val tripId =
                                    document.getString("tripId")
                                        ?: document.id

                                val plate =
                                    document.getString("plate")
                                        ?: "UNKNOWN"

                                val distanceKm =
                                    document.getDouble("distanceKm")
                                        ?: 0.0

                                val fare =
                                    document.getDouble("fare")
                                        ?: 0.0

                                val pickupLat =
                                    document.getDouble("pickupLat")
                                        ?: 0.0

                                val pickupLng =
                                    document.getDouble("pickupLng")
                                        ?: 0.0

                                val destinationLat =
                                    document.getDouble("destinationLat")
                                        ?: 0.0

                                val destinationLng =
                                    document.getDouble("destinationLng")
                                        ?: 0.0

                                val completedTimestamp =
                                    document.getTimestamp("completedAt")

                                val createdTimestamp =
                                    document.getTimestamp("createdAt")

                                val timestamp =
                                    completedTimestamp
                                        ?: createdTimestamp

                                val completedLabel =
                                    timestamp?.toDate()?.let { date ->
                                        SimpleDateFormat(
                                            "MMM dd, yyyy HH:mm",
                                            Locale.US
                                        ).format(date)
                                    } ?: "Not available"

                                val sortTime =
                                    timestamp?.toDate()?.time
                                        ?: 0L

                                TripHistoryItem(
                                    tripId = tripId,
                                    plate = plate,
                                    distanceKm = distanceKm,
                                    fare = fare,
                                    pickup = String.format(
                                        Locale.US,
                                        "%.6f, %.6f",
                                        pickupLat,
                                        pickupLng
                                    ),
                                    destination = String.format(
                                        Locale.US,
                                        "%.6f, %.6f",
                                        destinationLat,
                                        destinationLng
                                    ),
                                    status = status,
                                    completedAt = completedLabel,
                                    sortTime = sortTime
                                )
                            }
                            .sortedByDescending { it.sortTime }

                    onSuccess(history)
                }
                .addOnFailureListener { exception ->

                    onFailure(exception)
                }
        },

        onFailure = onFailure
    )
}



/* =========================================================
   PASSENGER REPORTING
   ========================================================= */

data class PassengerReportResult(
    val reportId: String
)


fun submitPassengerReport(
    tripId: String,
    plate: String,
    distanceKm: Double,
    fare: Double,
    reportType: String,
    description: String,
    onSuccess: (PassengerReportResult) -> Unit,
    onFailure: (Exception) -> Unit
) {

    ensureFirebaseAuthentication(

        onSuccess = { user ->

            val cleanType =
                reportType.trim()

            val cleanDescription =
                description.trim()

            if (cleanType.isBlank()) {
                onFailure(
                    IllegalArgumentException(
                        "Please select an issue type."
                    )
                )
                return@ensureFirebaseAuthentication
            }

            val reportId =
                "REP-" +
                        UUID.randomUUID()
                            .toString()
                            .take(8)
                            .uppercase()

            val reportData =
                hashMapOf<String, Any?>(
                    "reportId" to reportId,
                    "tripId" to tripId,
                    "passengerUid" to user.uid,
                    "plate" to plate.ifBlank {
                        "UNKNOWN"
                    },
                    "distanceKm" to distanceKm,
                    "fare" to fare,
                    "reportType" to cleanType,
                    "description" to cleanDescription,
                    "tripStatus" to "COMPLETED",
                    "status" to "OPEN",
                    "createdAt" to
                            com.google.firebase.firestore.FieldValue
                                .serverTimestamp()
                )

            geoFareFirestore
                .collection("reports")
                .document(reportId)
                .set(reportData)
                .addOnSuccessListener {
                    onSuccess(
                        PassengerReportResult(
                            reportId = reportId
                        )
                    )
                }
                .addOnFailureListener { exception ->
                    onFailure(exception)
                }
        },

        onFailure = onFailure
    )
}


/* =========================================================
   MAIN ACTIVITY
   ========================================================= */

/* =========================================================
   FARE RULE
   ========================================================= */

data class FareRule(
    val baseFare: Double,
    val baseDistanceKm: Double,
    val ratePerKm: Double,
    val effectiveDate: String = ""
)


/* =========================================================
   DEMO FARE RULE
   ========================================================= */

val DEMO_FARE_RULE = FareRule(
    baseFare = 15.00,
    baseDistanceKm = 1.00,
    ratePerKm = 5.00,
    effectiveDate = "DEMO ONLY"
)


/* =========================================================
   FARE CALCULATION
   ========================================================= */

fun calculateFare(
    distanceMeters: Double,
    fareRule: FareRule
): Double {

    val distanceKm = distanceMeters / 1000.0

    if (distanceKm <= fareRule.baseDistanceKm) {
        return fareRule.baseFare
    }

    val additionalDistanceKm =
        distanceKm - fareRule.baseDistanceKm

    val additionalStartedKm =
        kotlin.math.ceil(additionalDistanceKm)

    return fareRule.baseFare +
            (additionalStartedKm * fareRule.ratePerKm)
}


/* =========================================================
   MAIN ACTIVITY
   ========================================================= */

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration
            .getInstance()
            .setUserAgentValue("GeoFare/1.0")

        setContent {

            MaterialTheme {

                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {

                    GeoFareApp()
                }
            }
        }
    }
}


/* =========================================================
   MAIN APP
   ========================================================= */


@Composable
fun GeoFareApp() {

    var currentScreen by remember {
        mutableStateOf("HOME")
    }

    var detectedPlate by remember { mutableStateOf("") }
    var pickupLocation by remember { mutableStateOf<Location?>(null) }
    var destinationLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var routeDistanceMeters by remember { mutableStateOf<Double?>(null) }
    var routeDurationSeconds by remember { mutableStateOf<Double?>(null) }
    var calculatedFare by remember { mutableStateOf<Double?>(null) }
    var tripId by remember { mutableStateOf("") }
    var qrPayload by remember { mutableStateOf("") }
    var firebaseError by remember { mutableStateOf("") }
    var completionTimestamp by remember { mutableStateOf("") }
    var selectedHistoryTrip by remember { mutableStateOf<TripHistoryItem?>(null) }

    fun resetTrip() {
        detectedPlate = ""
        pickupLocation = null
        destinationLocation = null
        routeDistanceMeters = null
        routeDurationSeconds = null
        calculatedFare = null
        tripId = ""
        qrPayload = ""
        firebaseError = ""
        completionTimestamp = ""
    }

    when (currentScreen) {

        "HOME" -> {
            GeoFareHomeScreen(
                onStartTrip = {
                    resetTrip()
                    currentScreen = "START_TRIP"
                },
                onTripHistory = {
                    currentScreen = "TRIP_HISTORY"
                },
                onNotifications = {
                    currentScreen = "NOTIFICATIONS"
                },
                onProfile = {
                    currentScreen = "PROFILE"
                },
                onMore = {
                    currentScreen = "MORE"
                }
            )
        }

        "START_TRIP" -> {
            StartTripScreen(
                onBack = { currentScreen = "HOME" },
                onCapturePlate = { currentScreen = "PLATE_CAPTURE" },
                onTripHistory = { currentScreen = "TRIP_HISTORY" }
            )
        }

        "TRIP_HISTORY" -> {
            TripHistoryScreen(
                onBack = { currentScreen = "HOME" },
                onTripSelected = {
                    selectedHistoryTrip = it
                    currentScreen = "TRIP_DETAILS"
                },
                onStartTrip = {
                    resetTrip()
                    currentScreen = "START_TRIP"
                },
                onProfile = {
                    currentScreen = "PROFILE"
                },
                onMore = {
                    currentScreen = "MORE"
                }
            )
        }

        "TRIP_DETAILS" -> {
            val trip = selectedHistoryTrip
            if (trip == null) {
                currentScreen = "TRIP_HISTORY"
            } else {
                TripDetailsScreen(
                    trip = trip,
                    onBack = { currentScreen = "TRIP_HISTORY" }
                )
            }
        }

        "PLATE_CAPTURE" -> {
            PlateCaptureScreen(
                onBack = { currentScreen = "START_TRIP" },
                onContinue = { plate ->
                    detectedPlate = plate
                    currentScreen = "LOCATION"
                }
            )
        }

        "LOCATION" -> {
            LocationScreen(
                onBack = { currentScreen = "PLATE_CAPTURE" },
                onLocationConfirmed = { location ->
                    pickupLocation = location
                    currentScreen = "DESTINATION"
                }
            )
        }

        "DESTINATION" -> {
            DestinationScreen(
                pickupLatitude = pickupLocation?.latitude ?: 0.0,
                pickupLongitude = pickupLocation?.longitude ?: 0.0,
                onBack = { currentScreen = "LOCATION" },
                onRouteCalculated = { destination, distance, duration ->
                    destinationLocation = destination
                    routeDistanceMeters = distance
                    routeDurationSeconds = duration
                    calculatedFare = calculateFare(distance, DEMO_FARE_RULE)
                    currentScreen = "FARE"
                }
            )
        }

        "FARE" -> {
            FareEstimateScreen(
                distanceMeters = routeDistanceMeters ?: 0.0,
                durationSeconds = routeDurationSeconds ?: 0.0,
                fare = calculatedFare ?: 0.0,
                onBack = { currentScreen = "DESTINATION" },
                onConfirmFare = {
                    tripId = "GF-" + UUID.randomUUID().toString().take(8).uppercase()

                    val timestamp = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                    ).format(Date())

                    qrPayload = buildWebsiteTripUrl(
                        tripId = tripId,
                        plate = detectedPlate,
                        pickup = pickupLocation,
                        destination = destinationLocation,
                        distanceMeters = routeDistanceMeters ?: 0.0,
                        fare = calculatedFare ?: 0.0,
                        timestamp = timestamp
                    )

                    saveTripToFirestore(
                        tripId = tripId,
                        plate = detectedPlate,
                        pickup = pickupLocation,
                        destination = destinationLocation,
                        distanceMeters = routeDistanceMeters ?: 0.0,
                        fare = calculatedFare ?: 0.0,
                        timestamp = timestamp,
                        onSuccess = {
                            firebaseError = ""
                            currentScreen = "QR"
                        },
                        onFailure = { exception ->
                            firebaseError = exception.message
                                ?: "Unable to save trip to Firebase."
                            Log.e(
                                "GeoFareFirebase",
                                "Initial trip save failed",
                                exception
                            )
                            currentScreen = "QR"
                        }
                    )
                }
            )
        }

        "QR" -> {
            QrTripScreen(
                tripId = tripId,
                plate = detectedPlate,
                fare = calculatedFare ?: 0.0,
                qrPayload = qrPayload,
                firebaseError = firebaseError,
                onDriverConfirmed = {
                    currentScreen = "ROUTE_MONITORING"
                },
                onDriverDeclined = {
                    currentScreen = "HOME"
                },
                onFinish = {
                    currentScreen = "HOME"
                }
            )
        }

        "ROUTE_MONITORING" -> {
            RouteMonitoringScreen(
                tripId = tripId,
                pickupLocation = pickupLocation,
                destinationLocation = destinationLocation,
                onEndTrip = {
                    completionTimestamp = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                    ).format(Date())
                    currentScreen = "TRIP_COMPLETED"
                }
            )
        }

        "TRIP_COMPLETED" -> {
            TripCompletedScreen(
                tripId = tripId,
                plate = detectedPlate.ifBlank { "UNKNOWN" },
                distanceMeters = routeDistanceMeters ?: 0.0,
                durationSeconds = routeDurationSeconds ?: 0.0,
                fare = calculatedFare ?: 0.0,
                pickupLocation = pickupLocation,
                destinationLocation = destinationLocation,
                completedAt = completionTimestamp,
                onReportProblem = { currentScreen = "REPORT_ISSUE" },
                onDone = { currentScreen = "HOME" }
            )
        }

        "REPORT_ISSUE" -> {
            PassengerReportScreen(
                tripId = tripId,
                plate = detectedPlate.ifBlank { "UNKNOWN" },
                distanceKm = (routeDistanceMeters ?: 0.0) / 1000.0,
                fare = calculatedFare ?: 0.0,
                onCancel = { currentScreen = "TRIP_COMPLETED" },
                onSubmitted = {
                    currentScreen = "REPORT_SUBMITTED"
                }
            )
        }

        "REPORT_SUBMITTED" -> {
            ReportSubmittedScreen(
                onDone = { currentScreen = "HOME" },
                onViewHistory = { currentScreen = "TRIP_HISTORY" }
            )
        }

        "PROFILE" -> {
            ProfileScreen(
                onBack = { currentScreen = "HOME" },
                onEditProfile = { currentScreen = "EDIT_PROFILE" },
                onSettings = { currentScreen = "SETTINGS" },
                onTripHistory = { currentScreen = "TRIP_HISTORY" }
            )
        }

        "EDIT_PROFILE" -> {
            EditProfileScreen(
                onBack = { currentScreen = "PROFILE" }
            )
        }

        "SETTINGS" -> {
            SettingsScreen(
                onBack = { currentScreen = "PROFILE" },
                onNotifications = { currentScreen = "NOTIFICATIONS" },
                onHelp = { currentScreen = "HELP" },
                onAbout = { currentScreen = "ABOUT" }
            )
        }

        "NOTIFICATIONS" -> {
            NotificationsScreen(
                onBack = { currentScreen = "HOME" }
            )
        }

        "HELP" -> {
            HelpSupportScreen(
                onBack = { currentScreen = "SETTINGS" }
            )
        }

        "ABOUT" -> {
            AboutScreen(
                onBack = { currentScreen = "SETTINGS" }
            )
        }

        "MORE" -> {
            MoreScreen(
                onClose = { currentScreen = "HOME" },
                onHome = { currentScreen = "HOME" },
                onTrips = { currentScreen = "TRIP_HISTORY" },
                onProfile = { currentScreen = "PROFILE" },
                onSettings = { currentScreen = "SETTINGS" },
                onHelp = { currentScreen = "HELP" },
                onAbout = { currentScreen = "ABOUT" },
                onNotifications = { currentScreen = "NOTIFICATIONS" }
            )
        }

        /* Visual-only account screens for the design system.
           No authentication behavior is connected in this phase. */
        "SPLASH" -> {
            SplashScreen(onContinue = { currentScreen = "HOME" })
        }

        "LOGIN" -> {
            LoginScreen(
                onBack = { currentScreen = "HOME" },
                onCreateAccount = { currentScreen = "SIGN_UP" }
            )
        }

        "SIGN_UP" -> {
            SignUpScreen(
                onBack = { currentScreen = "HOME" },
                onSignIn = { currentScreen = "LOGIN" }
            )
        }
    }
}


/* =========================================================
   PROFILE / SETTINGS / SUPPORT / ABOUT / DRAWER UI
   Visual-only design screens for the UI phase.
   ========================================================= */

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit,
    onTripHistory: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 20.dp)
        ) {
            GeoFareSimpleTopBar("Profile", onBack)

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .background(Color(0xFF78A7D2), RoundedCornerShape(36.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("●", fontSize = 28.sp, color = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("GeoFare Passenger", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF14324A))
                            Text("Passenger account", fontSize = 12.sp, color = Color(0xFF71879B))
                        }
                        TextButton(onClick = onEditProfile) {
                            Text("Edit", color = Color(0xFF1476C9), fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(Modifier.fillMaxWidth()) {
                        GeoFareStat(
                            "—",
                            "Total Trips",
                            Modifier.weight(1f)
                        )
                        GeoFareStat(
                            "—",
                            "Total Fare",
                            Modifier.weight(1f)
                        )
                        GeoFareStat(
                            "—",
                            "This Month",
                            Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            GeoFareMenuCard(
                title = "My Trips",
                subtitle = "Review your completed trips",
                icon = "◷",
                onClick = onTripHistory
            )

            GeoFareMenuCard(
                title = "Settings",
                subtitle = "App preferences and privacy",
                icon = "⚙",
                onClick = onSettings
            )

            GeoFareMenuCard(
                title = "Help & Support",
                subtitle = "Get help using GeoFare",
                icon = "?",
                onClick = onSettings
            )
        }
    }
}

@Composable
fun GeoFareStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF0B4F8C)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = Color(0xFF71879B),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GeoFareMenuCard(
    title: String,
    subtitle: String,
    icon: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .height(70.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF14324A)
        )
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Color(0xFFEAF5FF), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 20.sp, color = Color(0xFF0B4F8C))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF71879B))
            }
            Text("›", fontSize = 24.sp, color = Color(0xFF7D92A4))
        }
    }
}

@Composable
fun EditProfileScreen(
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GeoFareSimpleTopBar("Edit Profile", onBack)

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                Column {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        placeholder = { Text("Passenger name") },
                        enabled = false
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        placeholder = { Text("Email address") },
                        enabled = false
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Profile editing will be connected later.",
                        fontSize = 12.sp,
                        color = Color(0xFF71879B)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GeoFareSimpleTopBar("Settings", onBack)

            Spacer(Modifier.height(12.dp))

            GeoFareSectionLabel("Account")
            GeoFareMenuCard("Personal Information", "Passenger profile", "♙") {}
            GeoFareMenuCard("Notification Settings", "Manage alerts", "◉", onNotifications)

            Spacer(Modifier.height(8.dp))

            GeoFareSectionLabel("App Preferences")
            GeoFareMenuCard("Language", "English", "◎") {}
            GeoFareMenuCard("Location Services", "GPS-based trip monitoring", "⌖") {}
            GeoFareMenuCard("Dark Mode", "Not connected in this UI phase", "◐") {}

            Spacer(Modifier.height(8.dp))

            GeoFareSectionLabel("About")
            GeoFareMenuCard("Help & Support", "FAQs and guidance", "?", onHelp)
            GeoFareMenuCard("App Information", "GeoFare v1.0", "ⓘ", onAbout)
        }
    }
}

@Composable
fun GeoFareSectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0B4F8C),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
fun NotificationsScreen(
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            GeoFareSimpleTopBar("Notifications", onBack)
            Spacer(Modifier.height(12.dp))
            GeoFareNotificationCard("Trip confirmed", "Your driver has confirmed the trip.", "Today")
            GeoFareNotificationCard("Trip completed", "Your recent trip was saved to Trip History.", "Today")
            GeoFareNotificationCard("GeoFare tip", "Keep the tricycle plate centered in the guide frame.", "Earlier")
        }
    }
}

@Composable
fun GeoFareNotificationCard(title: String, message: String, time: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(0xFFEAF5FF), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("•", fontSize = 24.sp, color = Color(0xFF1476C9))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14324A))
                Spacer(Modifier.height(3.dp))
                Text(message, fontSize = 12.sp, color = Color(0xFF71879B), lineHeight = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(time, fontSize = 10.sp, color = Color(0xFF98A9B8))
            }
        }
    }
}

@Composable
fun HelpSupportScreen(
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GeoFareSimpleTopBar("Help & Support", onBack)
            Spacer(Modifier.height(14.dp))
            GeoFareDetailCard(
                "Frequently Asked Questions",
                listOf(
                    "How do I start a trip?" to "Start a Trip → capture plate → choose destination → review fare.",
                    "How does the driver confirm?" to "The driver scans the QR with Google Lens and confirms the trip on the web page.",
                    "Where can I see completed trips?" to "Open Trip History from the home screen."
                )
            )
            Spacer(Modifier.height(12.dp))
            GeoFareDetailCard(
                "Support",
                listOf(
                    "System" to "GeoFare Passenger App",
                    "Purpose" to "GPS-based tricycle fare and trip monitoring"
                )
            )
        }
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GeoFareSimpleTopBar("About", onBack)
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(26.dp))
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .background(Color(0xFF0B4F8C), RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("GF", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("GeoFare", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B4F8C))
                    Text("Passenger App", fontSize = 12.sp, color = Color(0xFF71879B))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "GPS-Based Tricycle Fare & Trip Monitoring System",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF14324A)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "UI/UX design phase • v1.0",
                        fontSize = 11.sp,
                        color = Color(0xFF7B8FA1)
                    )
                }
            }
        }
    }
}

@Composable
fun MoreScreen(
    onClose: () -> Unit,
    onHome: () -> Unit,
    onTrips: () -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onNotifications: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.84f)
                .background(Color.White)
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Color(0xFF0B4F8C), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("GF", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GeoFare", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B4F8C))
                    Text("Passenger App", fontSize = 12.sp, color = Color(0xFF71879B))
                }
                TextButton(onClick = onClose) {
                    Text("×", fontSize = 28.sp, color = Color(0xFF0B4F8C))
                }
            }

            Spacer(Modifier.height(22.dp))

            GeoFareMenuCard("Home", "Return to dashboard", "⌂", onHome)
            GeoFareMenuCard("Trip History", "See completed trips", "◷", onTrips)
            GeoFareMenuCard("Profile", "Passenger information", "♙", onProfile)
            GeoFareMenuCard("Settings", "Preferences", "⚙", onSettings)
            GeoFareMenuCard("Notifications", "Recent alerts", "◉", onNotifications)
            GeoFareMenuCard("Help & Support", "Need assistance?", "?", onHelp)
            GeoFareMenuCard("About GeoFare", "App information", "ⓘ", onAbout)

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEAF5FF), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Text(
                    "The account screens, login, and signup visuals are prepared as UI-only placeholders for the design phase.",
                    fontSize = 11.sp,
                    color = Color(0xFF4C667B),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onContinue: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B4F8C)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(96.dp)
                    .background(Color.White, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("GF", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B4F8C))
            }
            Spacer(Modifier.height(18.dp))
            Text("GeoFare", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Passenger App", fontSize = 13.sp, color = Color.White.copy(alpha = 0.82f))
            Spacer(Modifier.height(28.dp))
            TextButton(onClick = onContinue) {
                Text("CONTINUE", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onCreateAccount: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(20.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GeoFareSimpleTopBar("Sign In", onBack)
            Spacer(Modifier.height(20.dp))

            Box(
                Modifier
                    .size(72.dp)
                    .background(Color(0xFF0B4F8C), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("GF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
            Text("Welcome back", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B4F8C))
            Text("UI-only sign in screen", fontSize = 12.sp, color = Color(0xFF71879B))
            Spacer(Modifier.height(22.dp))

            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Email") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Password") })
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1476C9))
            ) { Text("SIGN IN", fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCreateAccount) {
                Text("Create an account", color = Color(0xFF1476C9), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(20.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GeoFareSimpleTopBar("Create Account", onBack)
            Spacer(Modifier.height(16.dp))
            Text("Set up your GeoFare account", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B4F8C))
            Spacer(Modifier.height(6.dp))
            Text("UI-only signup screen for the design phase.", fontSize = 12.sp, color = Color(0xFF71879B))
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Full name") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Email") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(), label = { Text("Password") })
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1476C9))
            ) { Text("CREATE ACCOUNT", fontWeight = FontWeight.Bold) }

            TextButton(onClick = onSignIn) {
                Text("Already have an account? Sign in", color = Color(0xFF1476C9), fontWeight = FontWeight.Bold)
            }
        }
    }
}


/* =========================================================
   TRIP COMPLETED SCREEN
   ========================================================= */

@Composable
fun TripCompletedScreen(

    tripId: String,

    plate: String,

    distanceMeters: Double,

    durationSeconds: Double,

    fare: Double,

    pickupLocation: Location?,

    destinationLocation: GeoPoint?,

    completedAt: String,

    onReportProblem: () -> Unit,

    onDone: () -> Unit

) {

    val geoFareBlue =
        Color(0xFF0D4F8B)

    val successGreen =
        Color(0xFF2E7D32)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(0xFFF5F9FC)
                )
                .padding(20.dp)
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 90.dp),
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier =
                    Modifier.height(25.dp)
            )

            Box(
                modifier =
                    Modifier
                        .size(82.dp)
                        .background(
                            successGreen,
                            RoundedCornerShape(50.dp)
                        ),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text = "✓",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(
                modifier =
                    Modifier.height(18.dp)
            )

            Text(
                text = "Trip Completed",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = geoFareBlue,
                textAlign = TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            Text(
                text = "Your GeoFare trip record has been saved.",
                fontSize = 15.sp,
                color = Color.DarkGray,
                textAlign = TextAlign.Center
            )

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White,
                            RoundedCornerShape(18.dp)
                        )
                        .padding(18.dp)
            ) {

                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        text = "TRIP RECORD",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Spacer(
                        modifier =
                            Modifier.height(10.dp)
                    )

                    CompletedTripRow("Trip ID", tripId)
                    CompletedTripRow("Plate Number", plate)

                    CompletedTripRow(
                        "Distance",
                        String.format(
                            Locale.US,
                            "%.2f km",
                            distanceMeters / 1000.0
                        )
                    )

                    CompletedTripRow(
                        "Planned Route Time",
                        String.format(
                            Locale.US,
                            "%d min",
                            (durationSeconds / 60.0).toInt()
                        )
                    )

                    CompletedTripRow(
                        "Fare",
                        String.format(
                            Locale.US,
                            "₱%.2f",
                            fare
                        )
                    )

                    CompletedTripRow(
                        "Completed At",
                        completedAt.ifBlank {
                            "Not available"
                        }
                    )

                    pickupLocation?.let { pickup ->
                        CompletedTripRow(
                            "Pickup",
                            String.format(
                                Locale.US,
                                "%.6f, %.6f",
                                pickup.latitude,
                                pickup.longitude
                            )
                        )
                    }

                    destinationLocation?.let { destination ->
                        CompletedTripRow(
                            "Destination",
                            String.format(
                                Locale.US,
                                "%.6f, %.6f",
                                destination.latitude,
                                destination.longitude
                            )
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(18.dp)
            )

            Button(
                onClick = onReportProblem,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                shape =
                    RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
            ) {

                Text(
                    text = "REPORT A PROBLEM",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onDone,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                shape =
                    RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = geoFareBlue
                    )
            ) {

                Text(
                    text = "DONE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text =
                    "The completed trip remains available in Firestore for trip history and reporting.",
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun CompletedTripRow(
    label: String,
    value: String
) {

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp)
    ) {

        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Gray,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(
            modifier =
                Modifier.height(2.dp)
        )

        Text(
            text = value,
            fontSize = 16.sp,
            color = Color(0xFF202124),
            fontWeight = FontWeight.Medium
        )
    }
}


/* =========================================================
   PASSENGER REPORT SCREEN
   ========================================================= */

@Composable
fun PassengerReportScreen(

    tripId: String,
    plate: String,
    distanceKm: Double,
    fare: Double,
    onCancel: () -> Unit,
    onSubmitted: (String) -> Unit

) {

    val issueTypes = listOf(
        "Fare issue",
        "Route deviation",
        "Driver behavior",
        "Wrong vehicle / plate",
        "Other"
    )

    var selectedIssue by remember {
        mutableStateOf("")
    }

    var description by remember {
        mutableStateOf("")
    }

    var isSubmitting by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf("")
    }

    val scrollState = rememberScrollState()

    fun submitReport() {

        if (selectedIssue.isBlank()) {
            errorMessage =
                "Please select an issue type."
            return
        }

        isSubmitting = true
        errorMessage = ""

        submitPassengerReport(

            tripId = tripId,
            plate = plate,
            distanceKm = distanceKm,
            fare = fare,
            reportType = selectedIssue,
            description = description,

            onSuccess = { result ->

                isSubmitting = false
                onSubmitted(result.reportId)
            },

            onFailure = { exception ->

                isSubmitting = false
                errorMessage =
                    exception.message
                        ?: "Unable to submit report."
            }
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F9FC))
                .padding(20.dp)
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = 150.dp)
        ) {

            Text(
                text = "Report a Problem",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D4F8B)
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Tell us about an issue with this completed trip.",
                fontSize = 14.sp,
                color = Color.Gray
            )

            Spacer(Modifier.height(16.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
            ) {

                Column {

                    Text(
                        text = "TRIP DETAILS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Spacer(Modifier.height(8.dp))

                    CompletedTripRow("Trip ID", tripId)
                    CompletedTripRow("Plate Number", plate)
                    CompletedTripRow(
                        "Distance",
                        String.format(Locale.US, "%.2f km", distanceKm)
                    )
                    CompletedTripRow(
                        "Fare",
                        String.format(Locale.US, "₱%.2f", fare)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Issue Type",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )

            Spacer(Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                issueTypes.forEach { type ->

                    Button(
                        onClick = {
                            selectedIssue = type
                            errorMessage = ""
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor =
                                if (selectedIssue == type) {
                                    Color(0xFF0D4F8B)
                                } else {
                                    Color.White
                                },
                            contentColor =
                                if (selectedIssue == type) {
                                    Color.White
                                } else {
                                    Color(0xFF202124)
                                }
                        )
                    ) {
                        Text(
                            text = type,
                            fontSize = 14.sp,
                            fontWeight =
                                if (selectedIssue == type) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Description (optional)",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = description,
                onValueChange = {
                    if (it.length <= 500) {
                        description = it
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                placeholder = {
                    Text("Describe what happened...")
                },
                maxLines = 5
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${description.length}/500",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(130.dp))
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(18.dp)
                    )
                    .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            if (errorMessage.isNotBlank()) {

                Text(
                    text = errorMessage,
                    fontSize = 13.sp,
                    color = Color(0xFFD32F2F),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))
            }

            Button(
                onClick = ::submitReport,
                enabled = !isSubmitting,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                )
            ) {

                if (isSubmitting) {

                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White
                    )

                } else {

                    Text(
                        text = "SUBMIT REPORT",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            TextButton(
                onClick = onCancel,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CANCEL")
            }
        }
    }
}


/* =========================================================
   REPORT SUBMITTED SCREEN
   ========================================================= */

@Composable
fun ReportSubmittedScreen(
    onDone: () -> Unit,
    onViewHistory: () -> Unit
) {

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F9FC))
                .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Box(
                modifier =
                    Modifier
                        .size(82.dp)
                        .background(
                            Color(0xFF2E7D32),
                            RoundedCornerShape(50.dp)
                        ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Report Submitted",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D4F8B),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text =
                    "Your concern has been recorded and linked to the completed trip.",
                fontSize = 15.sp,
                color = Color.DarkGray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onViewHistory,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0D4F8B)
                )
            ) {
                Text(
                    text = "VIEW TRIP HISTORY",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onDone,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF455A64)
                )
            ) {
                Text(
                    text = "BACK TO HOME",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


/* =========================================================
   BUILD WEBSITE URL
   =========================================================
   The QR contains this public URL.
   Google Lens can scan it and open the website.
   ========================================================= */

fun buildWebsiteTripUrl(

    tripId: String,

    plate: String,

    pickup: Location?,

    destination: GeoPoint?,

    distanceMeters: Double,

    fare: Double,

    timestamp: String

): String {

    val pickupLatitude =
        pickup?.latitude ?: 0.0

    val pickupLongitude =
        pickup?.longitude ?: 0.0

    val destinationLatitude =
        destination?.latitude ?: 0.0

    val destinationLongitude =
        destination?.longitude ?: 0.0

    val distanceKm =
        String.format(
            Locale.US,
            "%.2f",
            distanceMeters / 1000.0
        )

    val fareString =
        String.format(
            Locale.US,
            "%.2f",
            fare
        )

    return Uri.Builder()
        .scheme("https")
        .authority("geofare-79da3.web.app")
        .path("/")
        .appendQueryParameter(
            "tripId",
            tripId
        )
        .appendQueryParameter(
            "plate",
            plate.ifBlank { "UNKNOWN" }
        )
        .appendQueryParameter(
            "pickupLat",
            String.format(
                Locale.US,
                "%.6f",
                pickupLatitude
            )
        )
        .appendQueryParameter(
            "pickupLng",
            String.format(
                Locale.US,
                "%.6f",
                pickupLongitude
            )
        )
        .appendQueryParameter(
            "destLat",
            String.format(
                Locale.US,
                "%.6f",
                destinationLatitude
            )
        )
        .appendQueryParameter(
            "destLng",
            String.format(
                Locale.US,
                "%.6f",
                destinationLongitude
            )
        )
        .appendQueryParameter(
            "distance",
            distanceKm
        )
        .appendQueryParameter(
            "fare",
            fareString
        )
        .appendQueryParameter(
            "timestamp",
            timestamp
        )
        .build()
        .toString()
}


/* =========================================================
   QR GENERATOR
   ========================================================= */

fun generateQrBitmap(
    content: String,
    size: Int = 800
): Bitmap {

    val hints =
        Hashtable<EncodeHintType, Any>().apply {

            put(
                EncodeHintType.MARGIN,
                1
            )
        }

    val matrix =
        MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

    val bitmap =
        Bitmap.createBitmap(
            matrix.width,
            matrix.height,
            Bitmap.Config.RGB_565
        )

    for (x in 0 until matrix.width) {

        for (y in 0 until matrix.height) {

            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            )
        }
    }

    return bitmap
}


/* =========================================================
   HOME SCREEN
   ========================================================= */


@Composable
fun GeoFareHomeScreen(
    onStartTrip: () -> Unit,
    onTripHistory: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    onMore: () -> Unit
) {
    val navy = Color(0xFF0B4F8C)
    val blue = Color(0xFF1476C9)
    val paleBlue = Color(0xFFEAF5FF)
    val background = Color(0xFFF5FAFE)
    val text = Color(0xFF14324A)
    val muted = Color(0xFF6C8294)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .padding(bottom = 92.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(navy, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "GF",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "GeoFare",
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = navy
                    )
                    Text(
                        "Passenger App",
                        fontSize = 12.sp,
                        color = muted
                    )
                }

                TextButton(onClick = onNotifications) {
                    Text(
                        "◉",
                        fontSize = 24.sp,
                        color = navy
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(paleBlue, RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        "Plan your trip with confidence.",
                        fontSize = 25.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = navy
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Capture the tricycle plate, choose your destination, review the fare, and keep a digital trip record.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = text
                    )

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = onStartTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(76.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = blue
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "▣   START A TRIP  ›",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Capture plate • Pick destination • Get fare",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = onTripHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = navy
                        )
                    ) {
                        Text(
                            "◷   TRIP HISTORY  ›",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        "How it works",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = navy
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GeoFareMiniStep("01", "Capture", "Plate")
                        Text("→", modifier = Modifier.padding(top = 14.dp), color = blue, fontWeight = FontWeight.Bold)
                        GeoFareMiniStep("02", "Choose", "Destination")
                        Text("→", modifier = Modifier.padding(top = 14.dp), color = blue, fontWeight = FontWeight.Bold)
                        GeoFareMiniStep("03", "Review", "Fare + QR")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFFEAF5FF), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("i", color = navy, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "For the driver",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = navy
                        )
                        Text(
                            "The driver scans your trip QR using Google Lens.",
                            fontSize = 13.sp,
                            color = muted,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "GPS-Based Tricycle Fare & Trip Monitoring System",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp,
                color = muted,
                textAlign = TextAlign.Center
            )
        }

        GeoFareBottomNav(
            active = "HOME",
            onHome = {},
            onTrips = onTripHistory,
            onStart = onStartTrip,
            onProfile = onProfile,
            onMore = onMore,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun GeoFareMiniStep(number: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Color(0xFFEAF5FF), RoundedCornerShape(21.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color(0xFF0B4F8C), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14324A), textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 10.sp, color = Color(0xFF6C8294), textAlign = TextAlign.Center)
    }
}

@Composable
fun GeoFareBottomNav(
    active: String,
    onHome: () -> Unit,
    onTrips: () -> Unit,
    onStart: () -> Unit,
    onProfile: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(22.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GeoFareNavItem("⌂", "Home", active == "HOME", onHome)
            GeoFareNavItem("◷", "Trips", active == "TRIPS", onTrips)

            Button(
                onClick = onStart,
                modifier = Modifier.size(58.dp),
                shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0B6CC4)
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("GF", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            GeoFareNavItem("♙", "Profile", active == "PROFILE", onProfile)
            GeoFareNavItem("⋯", "More", active == "MORE", onMore)
        }
    }
}

@Composable
fun GeoFareNavItem(
    icon: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(58.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                icon,
                fontSize = 20.sp,
                color = if (active) Color(0xFF0B6CC4) else Color(0xFF71879B)
            )
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) Color(0xFF0B6CC4) else Color(0xFF71879B)
            )
        }
    }
}

@Composable
fun StartTripScreen(
    onBack: () -> Unit,
    onCapturePlate: () -> Unit,
    onTripHistory: () -> Unit
) {
    val navy = Color(0xFF0B4F8C)
    val blue = Color(0xFF1476C9)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            GeoFareSimpleTopBar("Start a Trip", onBack)

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFFEAF5FF), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(116.dp)
                            .border(3.dp, navy, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▦", fontSize = 58.sp, color = blue)
                    }
                    Spacer(Modifier.width(22.dp))
                    Text("🛺", fontSize = 58.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Capture the tricycle plate",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                color = navy
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Use the camera to identify the tricycle before you choose your destination.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFF5E7487)
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onCapturePlate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = blue)
            ) {
                Text("▣   CAPTURE TRICYCLE PLATE", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onTripHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = navy
                )
            ) {
                Text("◷   VIEW TRIP HISTORY", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GeoFareMiniStep("01", "Scan", "Plate")
                Text("→", modifier = Modifier.padding(top = 12.dp), color = blue)
                GeoFareMiniStep("02", "Choose", "Destination")
                Text("→", modifier = Modifier.padding(top = 12.dp), color = blue)
                GeoFareMiniStep("03", "Get", "Fare")
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Your trip information is recorded as part of the GeoFare trip flow.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = Color(0xFF7B8FA1)
            )
        }
    }
}

@Composable
fun GeoFareSimpleTopBar(
    title: String,
    onBack: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("‹", fontSize = 34.sp, color = Color(0xFF0B4F8C))
            }
        } else {
            Spacer(Modifier.width(42.dp))
        }

        Text(
            title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF0B4F8C)
        )

        Spacer(Modifier.width(42.dp))
    }
}

@Composable
fun TripHistoryScreen(
    onBack: () -> Unit,
    onTripSelected: (TripHistoryItem) -> Unit,
    onStartTrip: () -> Unit,
    onProfile: () -> Unit,
    onMore: () -> Unit
) {
    var trips by remember { mutableStateOf<List<TripHistoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    fun loadHistory() {
        isLoading = true
        errorMessage = ""
        loadPassengerTripHistory(
            onSuccess = {
                trips = it
                isLoading = false
            },
            onFailure = {
                errorMessage = it.message ?: "Unable to load trip history."
                isLoading = false
            }
        )
    }

    LaunchedEffect(Unit) { loadHistory() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .padding(bottom = 86.dp)
        ) {
            GeoFareSimpleTopBar("Trip History", onBack)

            Spacer(Modifier.height(6.dp))

            Text(
                "Your recent GeoFare trips",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                color = Color(0xFF71879B)
            )

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(18.dp))
                    .padding(4.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(Color(0xFF1476C9), RoundedCornerShape(14.dp))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("All", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.weight(1f).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Completed", color = Color(0xFF0B4F8C), fontSize = 12.sp)
                }
                Box(
                    Modifier.weight(1f).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Pending", color = Color(0xFF0B4F8C), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF1476C9))
                    }
                }

                errorMessage.isNotBlank() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Unable to load trip history.",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            errorMessage,
                            fontSize = 13.sp,
                            color = Color(0xFF6C8294),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = ::loadHistory,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1476C9)
                            )
                        ) { Text("RETRY") }
                    }
                }

                trips.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(70.dp)
                                .background(Color(0xFFEAF5FF), RoundedCornerShape(35.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("◷", fontSize = 30.sp, color = Color(0xFF0B4F8C))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "No completed trips yet.",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF14324A)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Completed GeoFare trips will appear here.",
                            fontSize = 13.sp,
                            color = Color(0xFF71879B)
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = trips,
                            key = { it.tripId }
                        ) { trip ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(20.dp))
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(52.dp)
                                            .background(Color(0xFFEAF5FF), RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🛺", fontSize = 24.sp)
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "Plate No. ${trip.plate}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF14324A)
                                            )
                                            Text(
                                                String.format(Locale.US, "₱ %.2f", trip.fare),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF0B4F8C)
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            trip.completedAt,
                                            fontSize = 11.sp,
                                            color = Color(0xFF71879B)
                                        )
                                        Spacer(Modifier.height(7.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "Completed",
                                                modifier = Modifier
                                                    .background(Color(0xFFE4F8EE), RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1B8A59)
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "›",
                                                fontSize = 24.sp,
                                                color = Color(0xFF7D92A4)
                                            )
                                        }

                                        Spacer(Modifier.height(5.dp))

                                        TextButton(
                                            onClick = { onTripSelected(trip) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "VIEW TRIP DETAILS",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1476C9)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        GeoFareBottomNav(
            active = "TRIPS",
            onHome = onBack,
            onTrips = {},
            onStart = onStartTrip,
            onProfile = onProfile,
            onMore = onMore,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun TripDetailsScreen(
    trip: TripHistoryItem,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5FAFE))
            .padding(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GeoFareSimpleTopBar("Trip Details", onBack)

            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEAF5FF), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        "Trip Completed",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B8A59)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        trip.tripId,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0B4F8C)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        trip.completedAt,
                        fontSize = 12.sp,
                        color = Color(0xFF6C8294)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            GeoFareDetailCard(
                title = "Trip Summary",
                rows = listOf(
                    "Plate Number" to trip.plate,
                    "Distance" to String.format(Locale.US, "%.2f km", trip.distanceKm),
                    "Fare" to String.format(Locale.US, "₱ %.2f", trip.fare),
                    "Status" to trip.status
                )
            )

            Spacer(Modifier.height(12.dp))

            GeoFareDetailCard(
                title = "Locations",
                rows = listOf(
                    "Pickup" to trip.pickup,
                    "Destination" to trip.destination
                )
            )
        }
    }
}

@Composable
fun GeoFareDetailCard(
    title: String,
    rows: List<Pair<String, String>>
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B4F8C))
            Spacer(Modifier.height(8.dp))
            rows.forEach { row ->
                Column(Modifier.padding(vertical = 7.dp)) {
                    Text(row.first, fontSize = 11.sp, color = Color(0xFF7A8FA1))
                    Spacer(Modifier.height(2.dp))
                    Text(row.second, fontSize = 14.sp, color = Color(0xFF14324A), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
@Composable
fun PlateCaptureScreen(

    onBack: () -> Unit,

    onContinue: (String) -> Unit

) {

    val context =
        LocalContext.current

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var imageCapture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    var isProcessing by remember {
        mutableStateOf(false)
    }

    var detectedText by remember {
        mutableStateOf("")
    }

    var validatedPlate by remember {
        mutableStateOf("")
    }

    var plateDetectionConfidence by remember {
        mutableStateOf(0f)
    }

    var ocrConfidence by remember {
        mutableStateOf(0f)
    }

    var validationMessage by remember {
        mutableStateOf("")
    }

    var captureCompleted by remember {
        mutableStateOf(false)
    }

    var manualPlate by remember {
        mutableStateOf("")
    }

    var manualError by remember {
        mutableStateOf("")
    }

    var camera by remember {
        mutableStateOf<Camera?>(null)
    }

    var torchEnabled by remember {
        mutableStateOf(false)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            cameraPermissionGranted = granted
        }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val geoFareBlue = Color(0xFF0D4F8B)
    val lightBlue = Color(0xFFEAF3FA)
    val darkText = Color(0xFF202124)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F9FC))
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            geoFareBlue,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "GF",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Vehicle Identification",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = darkText
                    )
                    Text(
                        text = "Step 1 of 4 • Capture the license plate",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        lightBlue,
                        RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1",
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                geoFareBlue,
                                RoundedCornerShape(15.dp)
                            )
                            .wrapContentSize(Alignment.Center),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.width(10.dp))

                    Text(
                        text = "Align the plate inside the guide before capturing.",
                        fontSize = 13.sp,
                        color = Color(0xFF35546E),
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PLATE_GUIDE_ASPECT_RATIO)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {

                if (cameraPermissionGranted) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onImageCaptureReady = {
                            imageCapture = it
                        },
                        onCameraReady = {
                            camera = it
                        }
                    )
                } else {
                    Text(
                        text = "Camera permission is required\nfor license plate capture.",
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 3.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(22.dp)
                        )
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(
                            Color(0xB3000000),
                            RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (torchEnabled) "FLASH ON" else "FLASH",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .background(
                            Color(0xC9000000),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "PLACE THE ENTIRE PLATE INSIDE THIS AREA",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Button(
                    onClick = {
                        camera?.let { activeCamera ->
                            if (activeCamera.cameraInfo.hasFlashUnit()) {
                                torchEnabled = !torchEnabled
                                activeCamera.cameraControl.enableTorch(torchEnabled)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xB3000000),
                        contentColor = Color.White
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    enabled = camera?.cameraInfo?.hasFlashUnit() == true
                ) {
                    Text(
                        text = if (torchEnabled) "●" else "○",
                        fontSize = 22.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Use a clear, front-facing view of the plate.",
                fontSize = 13.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    val capture = imageCapture

                    if (capture == null) {
                        detectedText = "Camera is not ready yet."
                        return@Button
                    }

                    isProcessing = true

                    val photoFile = File(
                        context.cacheDir,
                        "geofare_plate_${System.currentTimeMillis()}.jpg"
                    )

                    val outputOptions =
                        ImageCapture.OutputFileOptions
                            .Builder(photoFile)
                            .build()

                    capture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {

                            override fun onError(
                                exception: ImageCaptureException
                            ) {
                                isProcessing = false
                                detectedText =
                                    "Photo capture failed: " +
                                            (exception.message ?: "Unknown error")
                                Log.e(
                                    "GeoFareCamera",
                                    "Capture failed",
                                    exception
                                )
                            }

                            override fun onImageSaved(
                                outputFileResults: ImageCapture.OutputFileResults
                            ) {
                                processPlateImage(
                                    context = context,
                                    imageUri = Uri.fromFile(photoFile),
                                    onSuccess = { result ->
                                        isProcessing = false
                                        validatedPlate = result.normalizedPlate
                                        plateDetectionConfidence = result.plateDetectionConfidence
                                        ocrConfidence = result.ocrConfidence
                                        detectedText = result.detectedText
                                        validationMessage = result.message
                                        captureCompleted = result.isValid
                                    },
                                    onFailure = { error ->
                                        isProcessing = false
                                        captureCompleted = false
                                        validatedPlate = ""
                                        detectedText = ""
                                        validationMessage =
                                            error.message ?: "Unable to scan the plate."
                                        Log.e(
                                            "GeoFareOCR",
                                            "Plate scan failed",
                                            error
                                        )
                                    }
                                )
                            }
                        }
                    )
                },
                enabled = cameraPermissionGranted && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = geoFareBlue
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = "CAPTURE PLATE",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (validationMessage.isNotBlank()) {
                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (captureCompleted) Color(0xFFEAF7EF) else Color(0xFFFFF4F4),
                            RoundedCornerShape(18.dp)
                        )
                        .border(
                            1.dp,
                            if (captureCompleted) Color(0xFFB7DFC4) else Color(0xFFFFC9C9),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (captureCompleted) "✓ PLATE DETECTED" else "PLATE SCAN REJECTED",
                            fontSize = 12.sp,
                            color = if (captureCompleted) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(7.dp))

                        Text(
                            text = if (captureCompleted) validatedPlate else "No valid Philippine plate detected",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (captureCompleted) geoFareBlue else Color(0xFFD32F2F),
                            textAlign = TextAlign.Center
                        )

                        if (detectedText.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "OCR: $detectedText",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Plate Detection %.0f%%  •  OCR %.0f%%".format(
                                Locale.US,
                                plateDetectionConfidence * 100f,
                                ocrConfidence * 100f
                            ),
                            fontSize = 12.sp,
                            color = Color(0xFF4E6577),
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = validationMessage,
                            fontSize = 12.sp,
                            color = if (captureCompleted) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            captureCompleted = false
                            validatedPlate = ""
                            detectedText = ""
                            validationMessage = ""
                            plateDetectionConfidence = 0f
                            ocrConfidence = 0f
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = geoFareBlue
                        )
                    ) {
                        Text("SCAN AGAIN", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { onContinue(validatedPlate) },
                        enabled = captureCompleted && validatedPlate.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            disabledContainerColor = Color(0xFFD9DEE3)
                        )
                    ) {
                        Text("CONFIRM PLATE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "!",
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                Color(0xFFFFEBEE),
                                RoundedCornerShape(14.dp)
                            )
                            .wrapContentSize(Alignment.Center),
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "SAFETY FIRST",
                            color = Color(0xFFD32F2F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Take the photo only when it is safe to do so. Avoid capturing while the vehicle is moving.",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "ENTER PLATE NUMBER MANUALLY",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF35546E)
            )

            Spacer(Modifier.height(7.dp))

            OutlinedTextField(
                value = manualPlate,
                onValueChange = {
                    manualPlate = it.take(12)
                    manualError = ""
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. ABC 123 or ABC 1234") }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val normalized = normalizePhilippinePlateInput(manualPlate)
                    if (normalized != null) {
                        validatedPlate = normalized
                        plateDetectionConfidence = 1f
                        ocrConfidence = 1f
                        detectedText = normalized
                        validationMessage = "Manual plate format verified. Confirm the plate before continuing."
                        captureCompleted = true
                        manualError = ""
                    } else {
                        captureCompleted = false
                        manualError = "Invalid Philippine plate format. Use 3 letters + 3 digits, 3 letters + 4 digits, or 3 digits + 3 letters."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = geoFareBlue)
            ) {
                Text("VALIDATE PLATE", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            if (manualError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = manualError,
                    color = Color(0xFFD32F2F),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))

            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "BACK",
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}


/* =========================================================
   CAMERA PREVIEW
   ========================================================= */

@Composable
fun CameraPreview(

    modifier: Modifier = Modifier,

    onImageCaptureReady:
        (ImageCapture) -> Unit,

    onCameraReady:
        (Camera) -> Unit

) {

    val lifecycleOwner =
        LocalLifecycleOwner.current

    AndroidView(

        modifier = modifier,

        factory = { context ->

            val previewView =
                PreviewView(context)

            previewView.scaleType =
                PreviewView.ScaleType.FILL_CENTER

            val cameraProviderFuture =
                ProcessCameraProvider.getInstance(
                    context
                )

            cameraProviderFuture.addListener({

                val cameraProvider =
                    cameraProviderFuture.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also { previewUseCase ->

                            previewUseCase
                                .surfaceProvider =
                                previewView.surfaceProvider
                        }

                val imageCapture =
                    ImageCapture.Builder()
                        .setCaptureMode(
                            ImageCapture
                                .CAPTURE_MODE_MINIMIZE_LATENCY
                        )
                        .build()

                val cameraSelector =
                    CameraSelector.DEFAULT_BACK_CAMERA

                try {

                    cameraProvider.unbindAll()

                    val boundCamera =
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )

                    onImageCaptureReady(
                        imageCapture
                    )

                    onCameraReady(
                        boundCamera
                    )

                } catch (exception: Exception) {

                    Log.e(
                        "GeoFareCamera",
                        "Camera initialization failed",
                        exception
                    )
                }

            }, ContextCompat.getMainExecutor(context))

            previewView
        }
    )
}


/* =========================================================
   OCR
   ========================================================= */

private const val PLATE_GUIDE_WIDTH_RATIO = 0.90f
private const val PLATE_GUIDE_ASPECT_RATIO = 3.05f
private const val PLATE_ROI_LEFT_RATIO = 0.05f
private const val PLATE_ROI_RIGHT_RATIO = 0.95f
private const val PLATE_ROI_TOP_RATIO = 0.28f
private const val PLATE_ROI_BOTTOM_RATIO = 0.72f
private const val MIN_PLATE_DETECTION_CONFIDENCE = 0.60f
private const val MIN_OCR_CONFIDENCE = 0.70f


data class PlateRecognitionResult(
    val normalizedPlate: String,
    val detectedText: String,
    val plateDetectionConfidence: Float,
    val ocrConfidence: Float,
    val isValid: Boolean,
    val message: String
)

private data class PlateVisualAnalysis(
    val confidence: Float,
    val sharpness: Float,
    val contrast: Float,
    val brightnessScore: Float
)

fun rotateBitmapUsingExif(
    bitmap: Bitmap,
    orientation: Int
): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
    )
}

fun cropPlateRegion(bitmap: Bitmap): Bitmap {
    val left = (bitmap.width * PLATE_ROI_LEFT_RATIO).toInt().coerceIn(0, bitmap.width - 1)
    val top = (bitmap.height * PLATE_ROI_TOP_RATIO).toInt().coerceIn(0, bitmap.height - 1)
    val right = (bitmap.width * PLATE_ROI_RIGHT_RATIO).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = (bitmap.height * PLATE_ROI_BOTTOM_RATIO).toInt().coerceIn(top + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}

private fun analyzePlateVisualQuality(bitmap: Bitmap): PlateVisualAnalysis {
    val stepX = maxOf(1, bitmap.width / 160)
    val stepY = maxOf(1, bitmap.height / 60)
    var count = 0
    var sum = 0.0
    var sumSq = 0.0
    var edges = 0
    var edgeStrength = 0.0
    var supportedSurfacePixels = 0

    var y = 0
    while (y < bitmap.height - stepY) {
        var x = 0
        while (x < bitmap.width - stepX) {
            val p = bitmap.getPixel(x, y)
            val pRight = bitmap.getPixel(x + stepX, y)
            val pDown = bitmap.getPixel(x, y + stepY)

            val red = android.graphics.Color.red(p)
            val green = android.graphics.Color.green(p)
            val blue = android.graphics.Color.blue(p)

            val maxChannel = maxOf(red, green, blue)
            val minChannel = minOf(red, green, blue)
            val saturation =
                if (maxChannel == 0) 0f
                else (maxChannel - minChannel).toFloat() / maxChannel.toFloat()

            val isLightNeutralPlateSurface =
                maxChannel >= 150 && saturation <= 0.25f

            val isGreenLegacyPlateSurface =
                green >= red * 1.08f &&
                        green >= blue * 1.05f &&
                        green >= 45

            if (isLightNeutralPlateSurface || isGreenLegacyPlateSurface) {
                supportedSurfacePixels++
            }

            val gray =
                (0.299 * red +
                        0.587 * green +
                        0.114 * blue)

            val grayRight =
                (0.299 * android.graphics.Color.red(pRight) +
                        0.587 * android.graphics.Color.green(pRight) +
                        0.114 * android.graphics.Color.blue(pRight))

            val grayDown =
                (0.299 * android.graphics.Color.red(pDown) +
                        0.587 * android.graphics.Color.green(pDown) +
                        0.114 * android.graphics.Color.blue(pDown))

            val diff =
                kotlin.math.abs(gray - grayRight) +
                        kotlin.math.abs(gray - grayDown)

            if (diff > 32.0) edges++
            edgeStrength += diff
            sum += gray
            sumSq += gray * gray
            count++
            x += stepX
        }
        y += stepY
    }

    if (count == 0) {
        return PlateVisualAnalysis(0f, 0f, 0f, 0f)
    }

    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    val standardDeviation = kotlin.math.sqrt(maxOf(0.0, variance))
    val edgeDensity = edges.toFloat() / count.toFloat()
    val averageEdgeStrength = edgeStrength / count.toDouble()

    val sharpness = (edgeDensity / 0.10f).coerceIn(0f, 1f)
    val contrast = (standardDeviation / 55.0).toFloat().coerceIn(0f, 1f)
    val brightnessScore = when {
        mean in 45.0..235.0 -> 1f
        mean in 25.0..245.0 -> 0.65f
        else -> 0.25f
    }
    val edgeStrengthScore = (averageEdgeStrength / 55.0).toFloat().coerceIn(0f, 1f)
    val supportedSurfaceScore =
        (supportedSurfacePixels.toFloat() / count.toFloat()).coerceIn(0f, 1f)

    val confidence =
        (0.30f * sharpness +
                0.25f * contrast +
                0.15f * brightnessScore +
                0.15f * edgeStrengthScore +
                0.15f * supportedSurfaceScore).coerceIn(0f, 1f)

    return PlateVisualAnalysis(
        confidence = confidence,
        sharpness = sharpness,
        contrast = contrast,
        brightnessScore = brightnessScore
    )
}

private fun normalizePhilippinePlateInput(raw: String): String? {
    val compact = raw.uppercase(Locale.US).filter { it.isLetterOrDigit() }

    if (compact.length < 6) return null

    fun mapLetter(c: Char): Char = when (c) {
        '0' -> 'O'
        '1' -> 'I'
        '5' -> 'S'
        '8' -> 'B'
        '2' -> 'Z'
        else -> c
    }

    fun mapDigit(c: Char): Char = when (c) {
        'O' -> '0'
        'Q' -> '0'
        'I' -> '1'
        'L' -> '1'
        'S' -> '5'
        'B' -> '8'
        'Z' -> '2'
        else -> c
    }

    // Supported Philippine plate patterns for this scanner:
    // 1) ABC 123   (legacy / older private-vehicle style)
    // 2) ABC 1234  (newer four-wheel format)
    // 3) 123 ABC   (motorcycle/tricycle numeric-first format)
    // The input is normalized before validation so spaces/hyphens and
    // common OCR character confusions do not prevent a valid match.

    for (length in listOf(7, 6)) {
        if (compact.length < length) continue

        for (start in 0..(compact.length - length)) {
            val candidate = compact.substring(start, start + length)

            // ABC 123 / ABC 1234
            val letterCount = 3
            val lettersFirst = candidate
                .substring(0, letterCount)
                .map(::mapLetter)
                .joinToString("")

            val digitsAfterLetters = candidate
                .substring(letterCount)
                .map(::mapDigit)
                .joinToString("")

            if (lettersFirst.matches(Regex("[A-Z]{3}")) &&
                digitsAfterLetters.matches(
                    Regex(if (length == 7) "[0-9]{4}" else "[0-9]{3}")
                )
            ) {
                return lettersFirst + " " + digitsAfterLetters
            }

            // 123 ABC -- important for Philippine motorcycle/tricycle plates
            // such as 685YDR. LTO materials also show the 123 ABC arrangement
            // on motorcycle temporary-plate layouts.
            val digitsFirst = candidate
                .substring(0, 3)
                .map(::mapDigit)
                .joinToString("")

            val lettersAfterDigits = candidate
                .substring(3)
                .map(::mapLetter)
                .joinToString("")

            if (length == 6 &&
                digitsFirst.matches(Regex("[0-9]{3}")) &&
                lettersAfterDigits.matches(Regex("[A-Z]{3}"))
            ) {
                return digitsFirst + " " + lettersAfterDigits
            }
        }
    }

    return null
}

private fun averageOcrConfidence(visionText: com.google.mlkit.vision.text.Text): Float {
    val lines = visionText.textBlocks.flatMap { it.lines }
    val values = lines.mapNotNull { line ->
        try {
            val value = line.confidence
            if (value != null && value >= 0f) value else null
        } catch (_: Exception) {
            null
        }
    }
    return if (values.isNotEmpty()) {
        values.average().toFloat().coerceIn(0f, 1f)
    } else {
        // Older ML Kit releases may not expose confidence on every line.
        0.75f
    }
}

private fun findPlateCandidate(
    visionText: com.google.mlkit.vision.text.Text
): Pair<String?, Float> {
    val lines = visionText.textBlocks.flatMap { it.lines }
    val lineCandidates = mutableListOf<Pair<String, Float>>()

    lines.forEach { line ->
        val candidate = normalizePhilippinePlateInput(line.text)
        if (candidate != null) {
            val box = line.boundingBox
            val layoutScore = if (box != null && visionText.text.isNotBlank()) {
                0.75f +
                        (((box.width().toFloat() / 1200f).coerceIn(0f, 1f)) * 0.15f)
            } else {
                0.75f
            }
            lineCandidates += candidate to layoutScore.coerceIn(0f, 1f)
        }
    }

    if (lineCandidates.isNotEmpty()) {
        return lineCandidates.first()
    }

    val combined = normalizePhilippinePlateInput(visionText.text)
    return combined to if (combined != null) 0.72f else 0f
}

fun processPlateImage(
    context: android.content.Context,
    imageUri: Uri,
    onSuccess: (PlateRecognitionResult) -> Unit,
    onFailure: (Exception) -> Unit
) {
    var originalBitmap: Bitmap? = null
    var orientedBitmap: Bitmap? = null
    var plateBitmap: Bitmap? = null

    try {
        val imagePath = imageUri.path
            ?: throw IllegalArgumentException("Captured image path is unavailable.")

        originalBitmap = BitmapFactory.decodeFile(imagePath)
            ?: throw IllegalStateException("Unable to decode captured plate image.")

        val exif = ExifInterface(imagePath)
        orientedBitmap = rotateBitmapUsingExif(
            originalBitmap,
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        )

        plateBitmap = cropPlateRegion(orientedBitmap)

        val visual = analyzePlateVisualQuality(plateBitmap)

        if (visual.sharpness < 0.18f || visual.contrast < 0.16f) {
            onSuccess(
                PlateRecognitionResult(
                    normalizedPlate = "",
                    detectedText = "",
                    plateDetectionConfidence = visual.confidence,
                    ocrConfidence = 0f,
                    isValid = false,
                    message = "Plate is unclear. Move closer and make sure the entire plate is visible."
                )
            )
            return
        }

        if (visual.confidence < MIN_PLATE_DETECTION_CONFIDENCE) {
            onSuccess(
                PlateRecognitionResult(
                    normalizedPlate = "",
                    detectedText = "",
                    plateDetectionConfidence = visual.confidence,
                    ocrConfidence = 0f,
                    isValid = false,
                    message = "No valid Philippine plate region detected. Please capture the plate again."
                )
            )
            return
        }

        val image = InputImage.fromBitmap(plateBitmap, 0)
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val ocrConfidence = averageOcrConfidence(visionText)
                val (candidate, layoutScore) = findPlateCandidate(visionText)
                val detectionConfidence =
                    (visual.confidence * 0.80f + layoutScore * 0.20f).coerceIn(0f, 1f)

                if (candidate == null) {
                    onSuccess(
                        PlateRecognitionResult(
                            normalizedPlate = "",
                            detectedText = visionText.text.trim(),
                            plateDetectionConfidence = detectionConfidence,
                            ocrConfidence = ocrConfidence,
                            isValid = false,
                            message = "This does not appear to be a valid Philippine plate number."
                        )
                    )
                } else if (detectionConfidence < MIN_PLATE_DETECTION_CONFIDENCE) {
                    onSuccess(
                        PlateRecognitionResult(
                            normalizedPlate = candidate,
                            detectedText = visionText.text.trim(),
                            plateDetectionConfidence = detectionConfidence,
                            ocrConfidence = ocrConfidence,
                            isValid = false,
                            message = "Plate detection confidence is too low. Please capture the entire plate clearly."
                        )
                    )
                } else if (ocrConfidence < MIN_OCR_CONFIDENCE) {
                    onSuccess(
                        PlateRecognitionResult(
                            normalizedPlate = candidate,
                            detectedText = visionText.text.trim(),
                            plateDetectionConfidence = detectionConfidence,
                            ocrConfidence = ocrConfidence,
                            isValid = false,
                            message = "Plate text confidence is too low. Please capture the plate again."
                        )
                    )
                } else {
                    onSuccess(
                        PlateRecognitionResult(
                            normalizedPlate = candidate,
                            detectedText = visionText.text.trim(),
                            plateDetectionConfidence = detectionConfidence,
                            ocrConfidence = ocrConfidence,
                            isValid = true,
                            message = "Philippine plate format verified. Confirm the plate before continuing."
                        )
                    )
                }
                recognizer.close()
            }
            .addOnFailureListener { exception ->
                recognizer.close()
                onFailure(exception)
            }
    } catch (exception: Exception) {
        onFailure(exception)
    }
}


/* =========================================================
   LOCATION SCREEN
   ========================================================= */

@SuppressLint("MissingPermission")
@Composable
fun LocationScreen(

    onBack: () -> Unit,

    onLocationConfirmed:
        (Location) -> Unit

) {

    val context =
        LocalContext.current

    var locationPermissionGranted by remember {

        mutableStateOf(

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var currentLocation by remember {
        mutableStateOf<Location?>(null)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var locationError by remember {
        mutableStateOf("")
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()

        ) { permissions ->

            locationPermissionGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true ||
                        permissions[
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ] == true
        }

    LaunchedEffect(Unit) {

        if (!locationPermissionGranted) {

            permissionLauncher.launch(

                arrayOf(

                    Manifest.permission
                        .ACCESS_FINE_LOCATION,

                    Manifest.permission
                        .ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val geoFareBlue =
        Color(0xFF0D4F8B)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(0xFFF5F9FC)
                )
                .padding(24.dp)
    ) {

        Column(

            modifier =
                Modifier.fillMaxSize(),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = "Pickup Location",

                fontSize = 30.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    geoFareBlue
            )

            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )

            Text(
                text = "Step 3",
                fontSize = 16.sp,
                color = Color.Gray
            )

            Spacer(
                modifier =
                    Modifier.height(30.dp)
            )

            if (!locationPermissionGranted) {

                Text(
                    text =
                        "Location permission is required\n" +
                                "to determine your pickup point.",

                    fontSize = 16.sp,

                    color =
                        Color.DarkGray,

                    textAlign =
                        TextAlign.Center
                )

                Spacer(
                    modifier =
                        Modifier.height(20.dp)
                )

                Button(
                    onClick = {

                        permissionLauncher.launch(

                            arrayOf(

                                Manifest.permission
                                    .ACCESS_FINE_LOCATION,

                                Manifest.permission
                                    .ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) {

                    Text(
                        text = "ALLOW LOCATION"
                    )
                }

            } else {

                Text(
                    text = "Your current location",

                    fontSize = 18.sp,

                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    modifier =
                        Modifier.height(20.dp)
                )

                Button(

                    onClick = {

                        isLoading = true
                        locationError = ""

                        val locationClient =
                            LocationServices
                                .getFusedLocationProviderClient(
                                    context
                                )

                        locationClient
                            .getCurrentLocation(

                                Priority
                                    .PRIORITY_HIGH_ACCURACY,

                                null

                            )
                            .addOnSuccessListener { location ->

                                isLoading = false

                                if (location != null) {

                                    currentLocation =
                                        location

                                } else {

                                    locationError =
                                        "Unable to obtain a location."
                                }
                            }
                            .addOnFailureListener { exception ->

                                isLoading = false

                                locationError =
                                    exception.message
                                        ?: "Location request failed."
                            }
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(55.dp),

                    shape =
                        RoundedCornerShape(16.dp),

                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                geoFareBlue
                        )
                ) {

                    Text(
                        text =
                            "GET CURRENT LOCATION",

                        fontSize = 16.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

                Spacer(
                    modifier =
                        Modifier.height(25.dp)
                )

                when {

                    isLoading -> {

                        CircularProgressIndicator()
                    }

                    currentLocation != null -> {

                        Box(

                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color.White,
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(20.dp)
                        ) {

                            Column {

                                Text(
                                    text =
                                        "GPS LOCATION",

                                    fontSize = 14.sp,

                                    color =
                                        Color.Gray,

                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(10.dp)
                                )

                                Text(
                                    text =
                                        "Latitude: %.6f".format(
                                            Locale.US,
                                            currentLocation!!.latitude
                                        ),

                                    fontSize = 17.sp
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(6.dp)
                                )

                                Text(
                                    text =
                                        "Longitude: %.6f".format(
                                            Locale.US,
                                            currentLocation!!.longitude
                                        ),

                                    fontSize = 17.sp
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(6.dp)
                                )

                                Text(
                                    text =
                                        "Accuracy: %.1f meters".format(
                                            Locale.US,
                                            currentLocation!!.accuracy
                                        ),

                                    fontSize = 15.sp,

                                    color =
                                        Color.Gray
                                )
                            }
                        }
                    }

                    locationError.isNotBlank() -> {

                        Text(
                            text = locationError,

                            color =
                                Color(0xFFD32F2F),

                            textAlign =
                                TextAlign.Center
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.weight(1f)
            )

            Button(

                onClick = {

                    currentLocation?.let { location ->

                        onLocationConfirmed(location)
                    }
                },

                enabled =
                    currentLocation != null,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(55.dp),

                shape =
                    RoundedCornerShape(16.dp),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFF2E7D32)
                    )
            ) {

                Text(
                    text =
                        "CONTINUE TO DESTINATION",

                    fontSize = 16.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            TextButton(
                onClick = onBack
            ) {

                Text(
                    text = "BACK"
                )
            }
        }
    }
}


/* =========================================================
   DESTINATION DATA / GEOCODING
   ========================================================= */

data class DestinationPlaceInfo(
    val placeName: String,
    val barangay: String?,
    val municipality: String?,
    val province: String?
) {
    fun municipalityProvinceLabel(): String {
        val parts = listOfNotNull(
            municipality?.takeIf { it.isNotBlank() },
            province?.takeIf { it.isNotBlank() }
        )
        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            "Location details unavailable"
        }
    }

    fun barangayLabel(): String {
        return barangay
            ?.takeIf { it.isNotBlank() }
            ?.let {
                if (it.startsWith("Barangay ", ignoreCase = true)) {
                    it
                } else {
                    "Barangay $it"
                }
            }
            ?: "Barangay could not be identified"
    }
}

data class DestinationSearchResult(
    val name: String,
    val subtitle: String,
    val point: GeoPoint
)

@Suppress("DEPRECATION")
fun searchDestinationPlaces(
    context: android.content.Context,
    query: String,
    onSuccess: (List<DestinationSearchResult>) -> Unit,
    onFailure: (Exception) -> Unit
) {
    Thread {
        try {
            val geocoder = Geocoder(
                context,
                Locale.getDefault()
            )

            val cleanedQuery = query.trim()

            if (cleanedQuery.isBlank()) {
                Handler(Looper.getMainLooper()).post {
                    onSuccess(emptyList())
                }
                return@Thread
            }

            val queries = buildList {
                add("$cleanedQuery, Agoo, La Union")
                add(cleanedQuery)
            }.distinct()

            val results =
                mutableListOf<DestinationSearchResult>()

            for (searchQuery in queries) {

                val addresses =
                    geocoder.getFromLocationName(
                        searchQuery,
                        8
                    ) ?: emptyList()

                for (address in addresses) {

                    val latitude =
                        address.latitude

                    val longitude =
                        address.longitude

                    if (
                        !latitude.isFinite() ||
                        !longitude.isFinite()
                    ) {
                        continue
                    }

                    val name =
                        address.featureName
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: address.thoroughfare
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                            ?: address.getAddressLine(
                                0
                            )
                            ?: cleanedQuery

                    val subtitleParts =
                        listOfNotNull(
                            address.locality
                                ?.takeIf {
                                    it.isNotBlank()
                                },
                            address.subAdminArea
                                ?.takeIf {
                                    it.isNotBlank() &&
                                            it !=
                                            address.locality
                                },
                            address.adminArea
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                        ).distinct()

                    val subtitle =
                        subtitleParts
                            .joinToString(", ")
                            .ifBlank {
                                address.getAddressLine(
                                    0
                                ) ?: "Map location"
                            }

                    val duplicate =
                        results.any {
                            kotlin.math.abs(
                                it.point.latitude -
                                        latitude
                            ) < 0.00001 &&
                                    kotlin.math.abs(
                                        it.point.longitude -
                                                longitude
                                    ) < 0.00001
                        }

                    if (!duplicate) {
                        results +=
                            DestinationSearchResult(
                                name = name,
                                subtitle = subtitle,
                                point =
                                    GeoPoint(
                                        latitude,
                                        longitude
                                    )
                            )
                    }

                    if (results.size >= 6) {
                        break
                    }
                }

                if (results.size >= 6) {
                    break
                }
            }

            Handler(
                Looper.getMainLooper()
            ).post {
                onSuccess(
                    results.take(6)
                )
            }

        } catch (exception: Exception) {

            Handler(
                Looper.getMainLooper()
            ).post {
                onFailure(exception)
            }
        }
    }.start()
}

@Suppress("DEPRECATION")
fun reverseGeocodeDestination(
    context: android.content.Context,
    point: GeoPoint,
    onSuccess: (DestinationPlaceInfo?) -> Unit,
    onFailure: (Exception) -> Unit
) {
    Thread {
        try {
            // OpenStreetMap Nominatim is used first because Android's
            // Geocoder often leaves the Philippine barangay in
            // subLocality blank. This request is triggered only after
            // the passenger selects a destination, not while typing.
            val nominatimInfo =
                reverseGeocodeWithNominatim(point)

            if (nominatimInfo != null &&
                !nominatimInfo.barangay.isNullOrBlank()
            ) {
                Handler(Looper.getMainLooper()).post {
                    onSuccess(nominatimInfo)
                }
                return@Thread
            }

            // Fallback to Android's local/system geocoder.
            val geocoder =
                Geocoder(
                    context,
                    Locale.getDefault()
                )

            val addresses =
                geocoder.getFromLocation(
                    point.latitude,
                    point.longitude,
                    1
                ) ?: emptyList()

            val address =
                addresses.firstOrNull()

            val fallbackInfo =
                address?.let { returnedAddress ->
                    val barangay =
                        listOfNotNull(
                            returnedAddress.subLocality,
                            returnedAddress.subAdminArea
                        )
                            .map { it.trim() }
                            .firstOrNull {
                                it.isNotBlank() &&
                                        !it.equals(
                                            returnedAddress.locality?.trim(),
                                            ignoreCase = true
                                        ) &&
                                        !it.equals(
                                            returnedAddress.adminArea?.trim(),
                                            ignoreCase = true
                                        )
                            }

                    val municipality =
                        returnedAddress.locality
                            ?.takeIf { it.isNotBlank() }
                            ?: returnedAddress.subAdminArea
                                ?.takeIf { it.isNotBlank() }

                    val province =
                        returnedAddress.adminArea
                            ?.takeIf { it.isNotBlank() }

                    val placeName =
                        returnedAddress.featureName
                            ?.takeIf { it.isNotBlank() }
                            ?: returnedAddress.thoroughfare
                                ?.takeIf { it.isNotBlank() }
                            ?: "Selected location"

                    DestinationPlaceInfo(
                        placeName = placeName,
                        barangay = barangay,
                        municipality = municipality,
                        province = province
                    )
                }

            val mergedInfo =
                when {
                    fallbackInfo == null ->
                        nominatimInfo

                    nominatimInfo == null ->
                        fallbackInfo

                    else ->
                        DestinationPlaceInfo(
                            placeName =
                                nominatimInfo.placeName
                                    .takeIf { it.isNotBlank() }
                                    ?: fallbackInfo.placeName,
                            barangay =
                                nominatimInfo.barangay
                                    ?.takeIf { it.isNotBlank() }
                                    ?: fallbackInfo.barangay,
                            municipality =
                                nominatimInfo.municipality
                                    ?.takeIf { it.isNotBlank() }
                                    ?: fallbackInfo.municipality,
                            province =
                                nominatimInfo.province
                                    ?.takeIf { it.isNotBlank() }
                                    ?: fallbackInfo.province
                        )
                }

            Handler(Looper.getMainLooper()).post {
                onSuccess(mergedInfo)
            }

        } catch (exception: Exception) {
            Log.e(
                "GeoFareGeocoder",
                "Reverse geocoding failed",
                exception
            )

            Handler(Looper.getMainLooper()).post {
                onFailure(exception)
            }
        }
    }.start()
}

private fun reverseGeocodeWithNominatim(
    point: GeoPoint
): DestinationPlaceInfo? {
    val url = URL(
        "https://nominatim.openstreetmap.org/reverse" +
                "?format=jsonv2" +
                "&addressdetails=1" +
                "&zoom=13" +
                "&lat=${point.latitude}" +
                "&lon=${point.longitude}" +
                "&accept-language=en"
    )

    val connection =
        url.openConnection() as HttpURLConnection

    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty(
            "User-Agent",
            "GeoFare/1.0 (+https://geofare-79da3.web.app)"
        )
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        val responseCode =
            connection.responseCode

        if (responseCode !in 200..299) {
            return null
        }

        val response =
            connection.inputStream
                .bufferedReader()
                .use { it.readText() }

        val json =
            JSONObject(response)

        val address =
            json.optJSONObject("address")
                ?: return null

        fun value(vararg keys: String): String? {
            for (key in keys) {
                val value =
                    address.optString(key, "")
                        .trim()

                if (value.isNotBlank()) {
                    return value
                }
            }
            return null
        }

        // In Philippine OSM data, barangay boundaries/places may be
        // represented under suburb, village, quarter, neighbourhood,
        // or hamlet. We intentionally do not use the municipality or
        // province as the barangay because that would be fabricated.
        val municipality =
            value(
                "town",
                "municipality",
                "city"
            )

        val province =
            value(
                "state",
                "province",
                "region"
            )

        val barangayCandidate =
            value(
                "suburb",
                "village",
                "quarter",
                "neighbourhood",
                "hamlet"
            )

        val barangay =
            barangayCandidate
                ?.takeIf {
                    it.isNotBlank() &&
                            !it.equals(
                                municipality,
                                ignoreCase = true
                            ) &&
                            !it.equals(
                                province,
                                ignoreCase = true
                            ) &&
                            !it.equals(
                                "Agoo",
                                ignoreCase = true
                            )
                }

        val placeName =
            value(
                "amenity",
                "building",
                "road"
            )
                ?: json.optString("name", "")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: "Selected location"

        DestinationPlaceInfo(
            placeName = placeName,
            barangay = barangay,
            municipality = municipality,
            province = province
        )
    } catch (exception: Exception) {
        Log.e(
            "GeoFareGeocoder",
            "Nominatim reverse geocoding failed",
            exception
        )
        null
    } finally {
        connection.disconnect()
    }
}


/* =========================================================
   DESTINATION SCREEN
   ========================================================= */

@Composable
fun DestinationScreen(
    pickupLatitude: Double,
    pickupLongitude: Double,
    onBack: () -> Unit,
    onRouteCalculated:
        (
        destination: GeoPoint,
        distanceMeters: Double,
        durationSeconds: Double
    ) -> Unit
) {

    val context = LocalContext.current

    val pickupPoint =
        remember(
            pickupLatitude,
            pickupLongitude
        ) {
            GeoPoint(
                pickupLatitude,
                pickupLongitude
            )
        }

    var selectedDestination by remember {
        mutableStateOf<GeoPoint?>(null)
    }

    var destinationInfo by remember {
        mutableStateOf<DestinationPlaceInfo?>(null)
    }

    var isResolvingDestination by remember {
        mutableStateOf(false)
    }

    var destinationStatusMessage by remember {
        mutableStateOf("")
    }

    var searchQuery by remember {
        mutableStateOf("")
    }

    var searchResults by remember {
        mutableStateOf<List<DestinationSearchResult>>(
            emptyList()
        )
    }

    var isSearching by remember {
        mutableStateOf(false)
    }

    var searchError by remember {
        mutableStateOf("")
    }

    var isRouting by remember {
        mutableStateOf(false)
    }

    var routeDistanceMeters by remember {
        mutableStateOf<Double?>(null)
    }

    var routeDurationSeconds by remember {
        mutableStateOf<Double?>(null)
    }

    var routeError by remember {
        mutableStateOf("")
    }

    val mapView =
        remember {
            MapView(context).apply {

                setTileSource(
                    TileSourceFactory.MAPNIK
                )

                setMultiTouchControls(true)

                controller.setZoom(16.0)

                controller.setCenter(
                    pickupPoint
                )
            }
        }

    val pickupMarker =
        remember(mapView) {
            Marker(mapView).apply {

                position =
                    pickupPoint

                title =
                    "Pickup Location"

                snippet =
                    "Current GPS location"

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }
        }

    val locationOverlay =
        remember(mapView) {
            MyLocationNewOverlay(
                GpsMyLocationProvider(context),
                mapView
            ).apply {
                setDrawAccuracyEnabled(true)
            }
        }

    var destinationMarker by remember(mapView) {
        mutableStateOf<Marker?>(null)
    }

    fun clearRouteFromMap() {
        mapView.overlays.removeAll {
            it is Polyline
        }
    }

    fun placeDestinationMarker(
        point: GeoPoint
    ) {
        destinationMarker?.let {
            mapView.overlays.remove(it)
        }

        val marker =
            Marker(mapView).apply {

                position =
                    point

                title =
                    "Destination"

                snippet =
                    "GeoFare selected drop-off location"

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }

        destinationMarker = marker

        mapView.overlays.add(
            marker
        )

        mapView.controller.animateTo(
            point
        )

        mapView.controller.setZoom(
            maxOf(
                mapView.zoomLevelDouble,
                17.0
            )
        )

        mapView.invalidate()
    }

    fun resolveDestination(
        point: GeoPoint
    ) {
        selectedDestination =
            point

        destinationInfo =
            null

        isResolvingDestination =
            true

        destinationStatusMessage =
            "Identifying destination..."

        routeDistanceMeters =
            null

        routeDurationSeconds =
            null

        routeError =
            ""

        clearRouteFromMap()

        placeDestinationMarker(
            point
        )

        reverseGeocodeDestination(
            context = context,
            point = point,

            onSuccess = { info ->

                isResolvingDestination =
                    false

                if (info == null) {

                    destinationInfo =
                        null

                    destinationStatusMessage =
                        "Barangay could not be identified."

                    return@reverseGeocodeDestination
                }

                destinationInfo =
                    info

                destinationStatusMessage =
                    if (
                        info.barangay
                            .isNullOrBlank()
                    ) {
                        "Barangay could not be identified."
                    } else {
                        "Destination identified."
                    }
            },

            onFailure = { exception ->

                isResolvingDestination =
                    false

                destinationInfo =
                    null

                destinationStatusMessage =
                    exception.message
                        ?: "Unable to identify this location."
            }
        )
    }

    DisposableEffect(
        mapView,
        pickupMarker,
        locationOverlay
    ) {

        val mapReceiver =
            object : MapEventsReceiver {

                override fun
                        singleTapConfirmedHelper(
                    point: GeoPoint
                ): Boolean {

                    searchQuery =
                        ""

                    searchResults =
                        emptyList()

                    searchError =
                        ""

                    resolveDestination(
                        GeoPoint(
                            point.latitude,
                            point.longitude
                        )
                    )

                    return true
                }

                override fun
                        longPressHelper(
                    point: GeoPoint
                ): Boolean {
                    return false
                }
            }

        val mapEventsOverlay =
            MapEventsOverlay(
                mapReceiver
            )

        mapView.overlays.add(
            mapEventsOverlay
        )

        mapView.overlays.add(
            pickupMarker
        )

        try {

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                locationOverlay
                    .enableMyLocation()

                mapView.overlays.add(
                    locationOverlay
                )
            }

        } catch (exception: Exception) {

            Log.e(
                "GeoFareMap",
                "Unable to enable location overlay",
                exception
            )
        }

        mapView.invalidate()

        onDispose {

            try {
                locationOverlay
                    .disableMyLocation()
            } catch (_: Exception) {
            }

            mapView.overlays.remove(
                mapEventsOverlay
            )

            mapView.overlays.remove(
                pickupMarker
            )

            mapView.overlays.remove(
                locationOverlay
            )

            destinationMarker?.let {
                mapView.overlays.remove(it)
            }
        }
    }

    DisposableEffect(mapView) {

        mapView.onResume()

        onDispose {
            mapView.onPause()
        }
    }

    LaunchedEffect(searchQuery) {

        searchError = ""

        if (
            searchQuery
                .trim()
                .length < 2
        ) {

            searchResults =
                emptyList()

            isSearching =
                false

            return@LaunchedEffect
        }

        delay(650)

        val queryAtRequest =
            searchQuery.trim()

        isSearching =
            true

        searchDestinationPlaces(
            context = context,
            query = queryAtRequest,

            onSuccess = { results ->

                if (
                    searchQuery
                        .trim() ==
                    queryAtRequest
                ) {

                    searchResults =
                        results

                    isSearching =
                        false

                    if (
                        results.isEmpty()
                    ) {

                        searchError =
                            "Location not found."
                    }
                }
            },

            onFailure = { exception ->

                if (
                    searchQuery
                        .trim() ==
                    queryAtRequest
                ) {

                    isSearching =
                        false

                    searchResults =
                        emptyList()

                    searchError =
                        exception.message
                            ?: "Search is unavailable right now."
                }
            }
        )
    }

    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        AndroidView(
            modifier =
                Modifier.fillMaxSize(),
            factory = {
                mapView
            }
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(
                            bottomStart = 22.dp,
                            bottomEnd = 22.dp
                        )
                    )
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = 16.dp,
                        bottom = 14.dp
                    )
        ) {

            Column {

                Text(
                    text = "Select Destination",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0D4F8B)
                )

                Spacer(
                    modifier =
                        Modifier.height(5.dp)
                )

                Text(
                    text =
                        "Search for a place or tap directly on the map.",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                OutlinedTextField(
                    value =
                        searchQuery,

                    onValueChange = {
                        searchQuery =
                            it
                    },

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(58.dp),

                    singleLine = true,

                    placeholder = {
                        Text(
                            "Search drop-off location..."
                        )
                    },

                    leadingIcon = {
                        Text(
                            text = "⌕",
                            fontSize = 24.sp,
                            color =
                                Color(0xFF0D4F8B)
                        )
                    },

                    shape =
                        RoundedCornerShape(
                            16.dp
                        )
                )

                if (
                    isSearching ||
                    searchResults.isNotEmpty() ||
                    searchError.isNotBlank()
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Color.White,
                                    RoundedCornerShape(
                                        14.dp
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    color =
                                        Color(
                                            0xFFDDE7EF
                                        ),
                                    shape =
                                        RoundedCornerShape(
                                            14.dp
                                        )
                                )
                    ) {

                        Column {

                            if (isSearching) {

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),

                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    CircularProgressIndicator(
                                        modifier =
                                            Modifier.size(
                                                20.dp
                                            ),
                                        strokeWidth = 2.5.dp
                                    )

                                    Spacer(
                                        modifier =
                                            Modifier.width(
                                                10.dp
                                            )
                                    )

                                    Text(
                                        text =
                                            "Searching nearby places...",
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            searchResults.forEach { result ->

                                Button(
                                    onClick = {

                                        searchQuery =
                                            result.name

                                        searchResults =
                                            emptyList()

                                        searchError =
                                            ""

                                        resolveDestination(
                                            result.point
                                        )
                                    },

                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(64.dp),

                                    shape =
                                        RoundedCornerShape(
                                            0.dp
                                        ),

                                    colors =
                                        ButtonDefaults
                                            .buttonColors(
                                                containerColor =
                                                    Color.White,
                                                contentColor =
                                                    Color(
                                                        0xFF202124
                                                    )
                                            )
                                ) {

                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth(),

                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {

                                        Text(
                                            text = "📍",
                                            fontSize = 19.sp
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.width(
                                                    10.dp
                                                )
                                        )

                                        Column(
                                            modifier =
                                                Modifier.weight(
                                                    1f
                                                ),

                                            horizontalAlignment =
                                                Alignment.Start
                                        ) {

                                            Text(
                                                text =
                                                    result.name,

                                                fontSize =
                                                    14.sp,

                                                fontWeight =
                                                    FontWeight.Bold,

                                                maxLines = 1
                                            )

                                            Text(
                                                text =
                                                    result.subtitle,

                                                fontSize =
                                                    11.sp,

                                                color =
                                                    Color.Gray,

                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }

                            if (
                                !isSearching &&
                                searchResults.isEmpty() &&
                                searchError.isNotBlank()
                            ) {

                                Text(
                                    text =
                                        "$searchError\n" +
                                                "Try another name or tap the map.",

                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                14.dp
                                            ),

                                    fontSize = 12.sp,
                                    color =
                                        Color(
                                            0xFFD32F2F
                                        ),

                                    textAlign =
                                        TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text =
                "© OpenStreetMap contributors",

            fontSize = 11.sp,

            color = Color.DarkGray,

            modifier =
                Modifier
                    .align(
                        Alignment.TopEnd
                    )
                    .padding(
                        top = 176.dp,
                        end = 10.dp
                    )
                    .background(
                        Color.White.copy(
                            alpha = 0.85f
                        ),
                        RoundedCornerShape(
                            6.dp
                        )
                    )
                    .padding(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
        )

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp
                        )
                    )
                    .padding(
                        18.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            if (
                selectedDestination == null
            ) {

                Text(
                    text =
                        "Tap the map or search for a drop-off location.",

                    fontSize = 15.sp,
                    color = Color.Gray,
                    textAlign =
                        TextAlign.Center
                )

            } else {

                Text(
                    text =
                        "Destination Selected",

                    fontSize = 19.sp,
                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF0D4F8B)
                )

                Spacer(
                    modifier =
                        Modifier.height(7.dp)
                )

                if (
                    isResolvingDestination
                ) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(23.dp),
                        strokeWidth = 2.5.dp
                    )

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    Text(
                        text =
                            destinationStatusMessage
                                .ifBlank {
                                    "Identifying destination..."
                                },

                        fontSize = 13.sp,

                        color =
                            Color.Gray,

                        textAlign =
                            TextAlign.Center
                    )

                } else if (
                    destinationInfo != null
                ) {

                    Text(
                        text =
                            "📍 ${destinationInfo!!.barangayLabel()}",

                        fontSize = 17.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF173E63),

                        textAlign =
                            TextAlign.Center
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            destinationInfo!!
                                .municipalityProvinceLabel(),

                        fontSize = 14.sp,

                        color =
                            Color.DarkGray,

                        textAlign =
                            TextAlign.Center
                    )

                    if (
                        destinationInfo!!
                            .placeName
                            .isNotBlank()
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(3.dp)
                        )

                        Text(
                            text =
                                destinationInfo!!
                                    .placeName,

                            fontSize = 12.sp,

                            color =
                                Color.Gray,

                            textAlign =
                                TextAlign.Center,

                            maxLines = 2
                        )
                    }

                    if (
                        destinationInfo!!
                            .barangay
                            .isNullOrBlank()
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        Text(
                            text =
                                "Barangay could not be identified. Select another location.",

                            fontSize = 12.sp,

                            color =
                                Color(0xFFD32F2F),

                            textAlign =
                                TextAlign.Center
                        )
                    }

                } else {

                    Text(
                        text =
                            destinationStatusMessage
                                .ifBlank {
                                    "Barangay could not be identified."
                                },

                        fontSize = 13.sp,

                        color =
                            Color(0xFFD32F2F),

                        textAlign =
                            TextAlign.Center
                    )
                }
            }

            if (
                isRouting
            ) {

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                CircularProgressIndicator()

                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )

                Text(
                    text =
                        "Calculating road route...",

                    fontSize =
                        14.sp,

                    color =
                        Color.Gray
                )
            }

            if (
                routeDistanceMeters != null
            ) {

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Text(
                    text =
                        "Road Distance: %.2f km"
                            .format(
                                Locale.US,
                                routeDistanceMeters!!
                                        / 1000.0
                            ),

                    fontSize =
                        18.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF0D4F8B)
                )

                Text(
                    text =
                        "Estimated Route Time: %d min"
                            .format(
                                Locale.US,
                                (
                                        routeDurationSeconds
                                            ?: 0.0
                                        )
                                    .div(60.0)
                                    .toInt()
                            ),

                    fontSize =
                        14.sp,

                    color =
                        Color.Gray
                )
            }

            if (
                routeError.isNotBlank()
            ) {

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        routeError,

                    fontSize =
                        13.sp,

                    color =
                        Color(0xFFD32F2F),

                    textAlign =
                        TextAlign.Center
                )
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Button(
                onClick = {

                    val destination =
                        selectedDestination
                            ?: return@Button

                    isRouting =
                        true

                    routeError =
                        ""

                    fetchOsrmRoute(

                        pickup =
                            pickupPoint,

                        destination =
                            destination,

                        onSuccess = {
                                distance,
                                duration,
                                geometry ->

                            isRouting =
                                false

                            routeDistanceMeters =
                                distance

                            routeDurationSeconds =
                                duration

                            mapView
                                .overlays
                                .removeAll {
                                    it is Polyline
                                }

                            val routeLine =
                                Polyline(
                                    mapView
                                )

                            routeLine.title =
                                "GeoFare Route"

                            routeLine.setPoints(
                                geometry
                            )

                            mapView
                                .overlays
                                .add(
                                    routeLine
                                )

                            placeDestinationMarker(
                                destination
                            )

                            mapView.invalidate()

                            onRouteCalculated(
                                destination,
                                distance,
                                duration
                            )
                        },

                        onFailure = { error ->

                            isRouting =
                                false

                            routeError =
                                error
                        }
                    )
                },

                enabled =
                    selectedDestination != null &&
                            destinationInfo != null &&
                            destinationInfo!!
                                .barangay
                                ?.isNotBlank() == true &&
                            !isResolvingDestination &&
                            !isRouting,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),

                shape =
                    RoundedCornerShape(16.dp),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFF2E7D32),

                        disabledContainerColor =
                            Color(0xFFD9DEE3)
                    )
            ) {

                Text(
                    text =
                        if (
                            routeDistanceMeters != null
                        ) {
                            "ROUTE CALCULATED"
                        } else {
                            "CONFIRM & CALCULATE ROUTE"
                        },

                    fontSize =
                        16.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            TextButton(
                onClick =
                    onBack,

                enabled =
                    !isRouting
            ) {

                Text(
                    text =
                        "BACK"
                )
            }
        }
    }
}


/* =========================================================
   FARE SCREEN
   ========================================================= */

@Composable
fun FareEstimateScreen(

    distanceMeters: Double,

    durationSeconds: Double,

    fare: Double,

    onBack: () -> Unit,

    onConfirmFare: () -> Unit

) {

    val distanceKm =
        distanceMeters / 1000.0

    Box(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(0xFFF5F9FC)
                )
                .padding(24.dp)
    ) {

        Column(

            modifier =
                Modifier.fillMaxSize(),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier =
                    Modifier.height(25.dp)
            )

            Text(

                text =
                    "Fare Estimate",

                fontSize = 30.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color(0xFF0D4F8B)
            )

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Text(

                text = "Step 5",

                fontSize = 16.sp,

                color = Color.Gray
            )

            Spacer(
                modifier =
                    Modifier.height(25.dp)
            )

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFFFF3CD),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(16.dp)
            ) {

                Column {

                    Text(

                        text =
                            "DEMO FARE MATRIX",

                        fontSize = 14.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF856404)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    Text(

                        text =
                            "Base fare: ₱15.00\n" +
                                    "Includes first 1.00 km\n" +
                                    "Succeeding: ₱5.00 per started km",

                        fontSize = 14.sp,

                        color =
                            Color(0xFF856404)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    Text(

                        text =
                            "Temporary prototype values only.",

                        fontSize = 12.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF856404)
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White,
                            RoundedCornerShape(18.dp)
                        )
                        .padding(20.dp)
            ) {

                Column {

                    Text(

                        text =
                            "TRIP DETAILS",

                        fontSize = 14.sp,

                        color = Color.Gray,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(14.dp)
                    )

                    Text(
                        text = "Road Distance",
                        fontSize = 15.sp,
                        color = Color.Gray
                    )

                    Text(

                        text =
                            "%.2f km".format(
                                Locale.US,
                                distanceKm
                            ),

                        fontSize = 22.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF0D4F8B)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    Text(

                        text =
                            "Estimated Travel Time",

                        fontSize = 15.sp,

                        color = Color.Gray
                    )

                    Text(

                        text =
                            "%d minutes".format(
                                Locale.US,
                                (durationSeconds / 60.0).toInt()
                            ),

                        fontSize = 18.sp,

                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(18.dp)
                    )

                    Text(

                        text =
                            "CALCULATED FARE",

                        fontSize = 14.sp,

                        color = Color.Gray,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(

                        text =
                            "₱%.2f".format(
                                Locale.US,
                                fare
                            ),

                        fontSize = 40.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF2E7D32)
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.weight(1f)
            )

            Button(

                onClick =
                    onConfirmFare,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp),

                shape =
                    RoundedCornerShape(16.dp),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFF2E7D32)
                    )
            ) {

                Text(

                    text =
                        "CONFIRM FARE",

                    fontSize = 18.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            TextButton(
                onClick =
                    onBack
            ) {

                Text(
                    text =
                        "BACK TO DESTINATION"
                )
            }
        }
    }
}


/* =========================================================
   QR SCREEN + DRIVER CONFIRMATION LISTENER
   ========================================================= */

@Composable
fun QrTripScreen(

    tripId: String,

    plate: String,

    fare: Double,

    qrPayload: String,

    firebaseError: String,

    onDriverConfirmed: () -> Unit,

    onDriverDeclined: () -> Unit,

    onFinish: () -> Unit

) {

    val qrBitmap =
        remember(qrPayload) {

            try {

                generateQrBitmap(
                    content =
                        qrPayload,
                    size = 800
                )

            } catch (exception: Exception) {

                Log.e(
                    "GeoFareQR",
                    "QR generation failed",
                    exception
                )

                null
            }
        }


    var listener by remember(
        tripId
    ) {

        mutableStateOf<ListenerRegistration?>(
            null
        )
    }


    var listenerError by remember {

        mutableStateOf("")
    }


    /*
     * Start Firebase authentication and
     * listen for the driver's confirmation.
     */
    LaunchedEffect(
        tripId
    ) {

        ensureFirebaseAuthentication(

            onSuccess = {

                listener =
                    listenForDriverConfirmation(

                        tripId =
                            tripId,

                        onConfirmed = {

                            onDriverConfirmed()
                        },

                        onDeclined = {

                            onDriverDeclined()
                        },

                        onError = { exception ->

                            listenerError =
                                exception.message
                                    ?: "Unable to monitor trip confirmation."

                            Log.e(
                                "GeoFareFirebase",
                                "Trip confirmation listener failed",
                                exception
                            )
                        }
                    )
            },

            onFailure = { exception ->

                listenerError =
                    exception.message
                        ?: "Firebase authentication failed."

                Log.e(
                    "GeoFareFirebase",
                    "Authentication failed",
                    exception
                )
            }
        )
    }


    DisposableEffect(
        tripId
    ) {

        onDispose {

            listener?.remove()

            listener =
                null
        }
    }


    Box(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(0xFFF5F9FC)
                )
                .padding(24.dp)
    ) {

        Column(

            modifier =
                Modifier.fillMaxSize(),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Spacer(
                modifier =
                    Modifier.height(25.dp)
            )


            Text(

                text =
                    "Trip QR Code",

                fontSize =
                    30.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color(0xFF0D4F8B)
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Text(

                text =
                    "Show this QR code to the driver.",

                fontSize =
                    16.sp,

                color =
                    Color.DarkGray,

                textAlign =
                    TextAlign.Center
            )


            Spacer(
                modifier =
                    Modifier.height(4.dp)
            )


            Text(

                text =
                    "The driver scans it using Google Lens.",

                fontSize =
                    14.sp,

                color =
                    Color.Gray,

                textAlign =
                    TextAlign.Center
            )


            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )


            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                Column(

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(30.dp),
                        color =
                            Color(0xFF0D4F8B)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(10.dp)
                    )

                    Text(

                        text =
                            "WAITING FOR DRIVER",

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF0D4F8B)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(5.dp)
                    )

                    Text(

                        text =
                            "The route monitoring will start automatically\n" +
                                    "after the driver confirms the trip.",

                        fontSize =
                            13.sp,

                        color =
                            Color.Gray,

                        textAlign =
                            TextAlign.Center
                    )
                }
            }


            Spacer(
                modifier =
                    Modifier.height(15.dp)
            )


            Box(

                modifier =
                    Modifier
                        .size(300.dp)
                        .background(
                            Color.White,
                            RoundedCornerShape(18.dp)
                        )
                        .padding(15.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                if (qrBitmap != null) {

                    Image(

                        bitmap =
                            qrBitmap.asImageBitmap(),

                        contentDescription =
                            "GeoFare Trip QR Code",

                        modifier =
                            Modifier.fillMaxSize()
                    )

                } else {

                    Text(

                        text =
                            "Unable to generate QR code.",

                        color =
                            Color(0xFFD32F2F),

                        textAlign =
                            TextAlign.Center
                    )
                }
            }


            Spacer(
                modifier =
                    Modifier.height(15.dp)
            )


            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(18.dp)
            ) {

                Column {

                    Text(

                        text =
                            "TRIP INFORMATION",

                        fontSize =
                            14.sp,

                        color =
                            Color.Gray,

                        fontWeight =
                            FontWeight.Bold
                    )


                    Spacer(
                        modifier =
                            Modifier.height(10.dp)
                    )


                    Text(

                        text =
                            "Trip ID: $tripId",

                        fontSize =
                            15.sp,

                        fontWeight =
                            FontWeight.SemiBold
                    )


                    Spacer(
                        modifier =
                            Modifier.height(5.dp)
                    )


                    Text(

                        text =
                            "Plate: ${
                                plate.ifBlank {
                                    "UNKNOWN"
                                }
                            }",

                        fontSize =
                            15.sp
                    )


                    Spacer(
                        modifier =
                            Modifier.height(5.dp)
                    )


                    Text(

                        text =
                            "Fare: ₱%.2f".format(
                                Locale.US,
                                fare
                            ),

                        fontSize =
                            18.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF2E7D32)
                    )
                }
            }


            if (
                firebaseError.isNotBlank() ||
                listenerError.isNotBlank()
            ) {

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )


                Text(

                    text =
                        firebaseError.ifBlank {
                            listenerError
                        },

                    color =
                        Color(0xFFD32F2F),

                    fontSize =
                        12.sp,

                    textAlign =
                        TextAlign.Center
                )
            }


            Spacer(
                modifier =
                    Modifier.weight(1f)
            )


            TextButton(

                onClick =
                    onFinish
            ) {

                Text(
                    text =
                        "CANCEL"
                )
            }
        }
    }
}


/* =========================================================
   ROUTE MONITORING SCREEN
   Driver confirms on website.
   Android passenger app starts monitoring here.
   ========================================================= */

@SuppressLint("MissingPermission")
@Composable
fun RouteMonitoringScreen(

    tripId: String,

    pickupLocation: Location?,

    destinationLocation: GeoPoint?,

    onEndTrip: () -> Unit

) {

    val context =
        LocalContext.current


    val fusedLocationClient =
        remember {

            LocationServices
                .getFusedLocationProviderClient(
                    context
                )
        }


    /*
     * LOCATION PERMISSION
     */
    var locationPermissionGranted by remember {

        mutableStateOf(

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

                    ||

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }


    val permissionLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()

        ) { permissions ->

            locationPermissionGranted =

                permissions[
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                ] == true

                        ||

                        permissions[
                            Manifest.permission
                                .ACCESS_COARSE_LOCATION
                        ] == true
        }


    LaunchedEffect(Unit) {

        if (
            !locationPermissionGranted
        ) {

            permissionLauncher.launch(

                arrayOf(

                    Manifest.permission
                        .ACCESS_FINE_LOCATION,

                    Manifest.permission
                        .ACCESS_COARSE_LOCATION
                )
            )
        }
    }


    /*
     * LIVE STATE
     */
    var currentLocation by remember {

        mutableStateOf<Location?>(null)
    }


    var routePoints by remember {

        mutableStateOf<List<GeoPoint>>(
            emptyList()
        )
    }


    var deviationMeters by remember {

        mutableStateOf(0.0)
    }


    var tripStatus by remember {

        mutableStateOf(
            "LOADING ROUTE"
        )
    }


    var routeError by remember {

        mutableStateOf("")
    }


    var isTracking by remember {

        mutableStateOf(false)
    }


    var isEndingTrip by remember {

        mutableStateOf(false)
    }


    val latestRoutePoints =
        rememberUpdatedState(
            routePoints
        )


    /*
     * DEMO THRESHOLD
     *
     * Final value must be determined with LGU.
     */
    val deviationThresholdMeters =
        100.0


    /*
     * MAP
     */
    val mapView =
        remember {

            MapView(context).apply {

                setTileSource(
                    TileSourceFactory.MAPNIK
                )

                setMultiTouchControls(
                    true
                )

                controller.setZoom(
                    16.0
                )
            }
        }


    /*
     * TRIP POINTS
     */
    val pickupPoint =
        remember(
            pickupLocation
        ) {

            GeoPoint(

                pickupLocation?.latitude
                    ?: 0.0,

                pickupLocation?.longitude
                    ?: 0.0
            )
        }


    val destinationPoint =
        remember(
            destinationLocation
        ) {

            destinationLocation
                ?: GeoPoint(
                    0.0,
                    0.0
                )
        }


    /*
     * MARKERS
     */
    val pickupMarker =
        remember(
            mapView
        ) {

            Marker(
                mapView
            ).apply {

                title =
                    "Pickup"

                snippet =
                    "Trip pickup location"

                setAnchor(

                    Marker.ANCHOR_CENTER,

                    Marker.ANCHOR_BOTTOM
                )
            }
        }


    val destinationMarker =
        remember(
            mapView
        ) {

            Marker(
                mapView
            ).apply {

                title =
                    "Destination"

                snippet =
                    "Passenger destination"

                setAnchor(

                    Marker.ANCHOR_CENTER,

                    Marker.ANCHOR_BOTTOM
                )
            }
        }


    val vehicleMarker =
        remember(
            mapView
        ) {

            Marker(
                mapView
            ).apply {

                title =
                    "Current Passenger GPS"

                snippet =
                    "Live GeoFare location"

                setAnchor(

                    Marker.ANCHOR_CENTER,

                    Marker.ANCHOR_BOTTOM
                )
            }
        }


    /*
     * MAP LIFECYCLE + MARKERS
     */
    DisposableEffect(
        mapView,
        pickupPoint,
        destinationPoint
    ) {

        pickupMarker.position =
            pickupPoint

        destinationMarker.position =
            destinationPoint


        mapView.overlays
            .removeAll {

                it is Marker &&
                        (
                                it.title == "Pickup" ||
                                        it.title == "Destination"
                                )
            }


        mapView.overlays.add(
            pickupMarker
        )


        mapView.overlays.add(
            destinationMarker
        )


        mapView.controller.setCenter(
            pickupPoint
        )


        mapView.invalidate()


        onDispose {
            // Map lifecycle is handled below.
        }
    }


    DisposableEffect(
        mapView
    ) {

        mapView.onResume()

        onDispose {

            mapView.onPause()
        }
    }


    /*
     * LOAD PLANNED ROUTE.
     */
    LaunchedEffect(
        pickupPoint,
        destinationPoint
    ) {

        if (
            pickupLocation == null ||
            destinationLocation == null
        ) {

            routeError =
                "Pickup or destination information is missing."

            tripStatus =
                "ROUTE ERROR"

            return@LaunchedEffect
        }


        tripStatus =
            "LOADING ROUTE"


        fetchOsrmRoute(

            pickup =
                pickupPoint,

            destination =
                destinationPoint,

            onSuccess = {
                    distance,
                    duration,
                    geometry ->

                routePoints =
                    geometry


                routeError =
                    ""


                val routeLine =
                    Polyline(
                        mapView
                    )


                routeLine.title =
                    "GeoFare Planned Route"


                routeLine.setPoints(
                    geometry
                )


                mapView.overlays
                    .removeAll {

                        it is Polyline
                    }


                mapView.overlays.add(
                    0,
                    routeLine
                )


                mapView.invalidate()


                mapView.zoomToBoundingBox(
                    routeLine.bounds,
                    true,
                    80
                )


                /*
                 * After route is available, mark trip
                 * as active and waiting for GPS.
                 */
                updateAndroidTripStatus(
                    tripId =
                        tripId,

                    status =
                        "ACTIVE",

                    routeStatus =
                        "WAITING_FOR_GPS"
                )


                tripStatus =
                    "WAITING FOR GPS"
            },

            onFailure = {
                    errorMessage ->

                routeError =
                    errorMessage

                tripStatus =
                    "ROUTE ERROR"
            }
        )
    }


    /*
     * CURRENT GPS MARKER
     */
    var markerAdded by remember {

        mutableStateOf(false)
    }


    /*
     * LOCATION CALLBACK
     */
    val locationCallback =
        remember(
            mapView
        ) {

            object :
                LocationCallback() {

                override fun onLocationResult(
                    result:
                    LocationResult
                ) {

                    val location =
                        result.lastLocation
                            ?: return


                    currentLocation =
                        location


                    val vehiclePoint =
                        GeoPoint(

                            location.latitude,

                            location.longitude
                        )


                    vehicleMarker.position =
                        vehiclePoint


                    if (!markerAdded) {

                        mapView.overlays.add(
                            vehicleMarker
                        )

                        markerAdded =
                            true
                    }


                    val points =
                        latestRoutePoints.value


                    val distanceToRoute =
                        calculateDistanceToRoutePoints(

                            location,

                            points
                        )


                    if (
                        distanceToRoute != null
                    ) {

                        deviationMeters =
                            distanceToRoute


                        tripStatus =

                            if (
                                distanceToRoute >
                                deviationThresholdMeters
                            ) {

                                "ROUTE DEVIATION"

                            } else {

                                "ON ROUTE"
                            }


                        updateAndroidTripStatus(

                            tripId =
                                tripId,

                            status =
                                "ACTIVE",

                            routeStatus =
                                if (
                                    distanceToRoute >
                                    deviationThresholdMeters
                                ) {

                                    "ROUTE_DEVIATION"

                                } else {

                                    "ON_ROUTE"
                                },

                            latitude =
                                location.latitude,

                            longitude =
                                location.longitude,

                            accuracy =
                                location.accuracy,

                            distanceFromRoute =
                                distanceToRoute
                        )

                    } else {

                        tripStatus =
                            "WAITING FOR ROUTE"
                    }


                    mapView.controller
                        .animateTo(
                            vehiclePoint
                        )


                    mapView.invalidate()
                }
            }
        }


    /*
     * START LIVE GPS.
     */
    DisposableEffect(
        locationPermissionGranted,
        tripId
    ) {

        if (
            !locationPermissionGranted
        ) {

            return@DisposableEffect onDispose {}
        }


        val request =
            LocationRequest.Builder(

                Priority
                    .PRIORITY_HIGH_ACCURACY,

                3000L
            )
                .setMinUpdateIntervalMillis(
                    1500L
                )
                .build()


        try {

            fusedLocationClient
                .requestLocationUpdates(

                    request,

                    locationCallback,

                    Looper.getMainLooper()
                )


            isTracking =
                true

        } catch (
            exception:
            SecurityException
        ) {

            isTracking =
                false

            tripStatus =
                "GPS PERMISSION ERROR"

            routeError =
                exception.message
                    ?: "GPS permission is required."
        }


        onDispose {

            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )

            isTracking =
                false
        }
    }


    /*
     * END TRIP.
     *
     * Stay on the monitoring screen until Firestore
     * confirms the completed trip record was saved.
     */
    fun finishTrip() {

        if (isEndingTrip) {

            return
        }

        isEndingTrip = true

        tripStatus =
            "SAVING COMPLETED TRIP"

        routeError = ""

        updateAndroidTripStatus(

            tripId =
                tripId,

            status =
                "COMPLETED",

            routeStatus =
                "COMPLETED",

            latitude =
                currentLocation?.latitude,

            longitude =
                currentLocation?.longitude,

            accuracy =
                currentLocation?.accuracy,

            distanceFromRoute =
                deviationMeters,

            onSuccess = {

                isEndingTrip = false

                isTracking = false

                tripStatus =
                    "TRIP COMPLETED"

                onEndTrip()
            },

            onFailure = { exception ->

                isEndingTrip = false

                tripStatus =
                    "COMPLETION FAILED"

                routeError =
                    exception.message
                        ?: "Unable to save the completed trip. Please try again."
            }
        )
    }


    /*
     * UI
     */
    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        AndroidView(

            modifier =
                Modifier.fillMaxSize(),

            factory = {
                mapView
            }
        )


        Box(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(
                            bottomStart = 20.dp,
                            bottomEnd = 20.dp
                        )
                    )
                    .padding(18.dp)
        ) {

            Column {

                Text(

                    text =
                        "Live Route Monitoring",

                    fontSize =
                        24.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF0D4F8B)
                )


                Spacer(
                    modifier =
                        Modifier.height(5.dp)
                )


                Text(

                    text =
                        "Trip ID: $tripId",

                    fontSize =
                        14.sp,

                    color =
                        Color.Gray
                )
            }
        }


        Text(

            text =
                "© OpenStreetMap contributors",

            fontSize =
                11.sp,

            color =
                Color.DarkGray,

            modifier =
                Modifier
                    .align(
                        Alignment.TopEnd
                    )
                    .padding(
                        top = 78.dp,
                        end = 10.dp
                    )
                    .background(
                        Color.White.copy(
                            alpha = 0.85f
                        ),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(
                        horizontal = 5.dp,
                        vertical = 3.dp
                    )
        )


        Column(

            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .fillMaxWidth()
                    .background(
                        Color.White,
                        RoundedCornerShape(
                            topStart = 24.dp,
                            topEnd = 24.dp
                        )
                    )
                    .padding(20.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(

                text =
                    tripStatus,

                fontSize =
                    23.sp,

                fontWeight =
                    FontWeight.Bold,

                color =

                    when {
                        tripStatus ==
                                "ROUTE DEVIATION" ->
                            Color(0xFFD32F2F)

                        tripStatus ==
                                "ON ROUTE" ->
                            Color(0xFF2E7D32)

                        tripStatus ==
                                "TRIP COMPLETED" ->
                            Color(0xFF455A64)

                        else ->
                            Color(0xFF0D4F8B)
                    }
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Text(

                text =
                    if (isTracking) {

                        "● LIVE GPS ACTIVE"

                    } else {

                        "GPS NOT ACTIVE"
                    },

                fontSize =
                    13.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    if (isTracking) {

                        Color(0xFF2E7D32)

                    } else {

                        Color.Gray
                    }
            )


            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )


            if (
                currentLocation != null
            ) {

                Text(

                    text =
                        "CURRENT GPS\n" +
                                "Lat: %.6f\n" +
                                "Lng: %.6f"
                                    .format(

                                        Locale.US,

                                        currentLocation!!
                                            .latitude,

                                        currentLocation!!
                                            .longitude
                                    ),

                    fontSize =
                        14.sp,

                    textAlign =
                        TextAlign.Center
                )


                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )


                Text(

                    text =
                        "GPS Accuracy: %.1f meters"
                            .format(

                                Locale.US,

                                currentLocation!!
                                    .accuracy
                            ),

                    fontSize =
                        13.sp,

                    color =
                        Color.Gray
                )

            } else {

                CircularProgressIndicator()

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(

                    text =
                        if (
                            locationPermissionGranted
                        ) {

                            "Waiting for GPS location..."

                        } else {

                            "Waiting for location permission..."
                        },

                    fontSize =
                        14.sp,

                    color =
                        Color.Gray,

                    textAlign =
                        TextAlign.Center
                )
            }


            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )


            Text(

                text =
                    "Distance from planned route: %.1f m"
                        .format(

                            Locale.US,

                            deviationMeters
                        ),

                fontSize =
                    15.sp,

                fontWeight =
                    FontWeight.SemiBold
            )


            Spacer(
                modifier =
                    Modifier.height(3.dp)
            )


            Text(

                text =
                    "Demo deviation threshold: %.0f m"
                        .format(

                            Locale.US,

                            deviationThresholdMeters
                        ),

                fontSize =
                    12.sp,

                color =
                    Color.Gray
            )


            if (
                routeError.isNotBlank()
            ) {

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Text(

                    text =
                        routeError,

                    color =
                        Color(0xFFD32F2F),

                    fontSize =
                        13.sp,

                    textAlign =
                        TextAlign.Center
                )
            }


            Spacer(
                modifier =
                    Modifier.height(15.dp)
            )


            Button(

                onClick =
                    ::finishTrip,

                enabled =
                    !isEndingTrip,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),

                shape =
                    RoundedCornerShape(
                        16.dp
                    ),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFFD32F2F)
                    )
            ) {

                if (isEndingTrip) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(22.dp),
                        color = Color.White
                    )

                } else {

                    Text(

                        text =
                            "END TRIP",

                        fontSize =
                            17.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }
    }
}


/* =========================================================
   DISTANCE TO ROUTE POINTS
   ========================================================= */

fun calculateDistanceToRoutePoints(

    location: Location,

    routePoints:
    List<GeoPoint>

): Double? {

    if (
        routePoints.isEmpty()
    ) {

        return null
    }


    var minimumDistance =
        Double.MAX_VALUE


    for (
    point in routePoints
    ) {

        val results =
            FloatArray(1)


        Location.distanceBetween(

            location.latitude,

            location.longitude,

            point.latitude,

            point.longitude,

            results
        )


        val distance =
            results[0]
                .toDouble()


        if (
            distance <
            minimumDistance
        ) {

            minimumDistance =
                distance
        }
    }


    return minimumDistance
}


/* =========================================================
   UPDATE ANDROID TRIP STATUS
   ========================================================= */

fun updateAndroidTripStatus(

    tripId: String,

    status: String,

    routeStatus: String,

    latitude: Double? = null,

    longitude: Double? = null,

    accuracy: Float? = null,

    distanceFromRoute: Double? = null,

    onSuccess: (() -> Unit)? = null,

    onFailure: ((Exception) -> Unit)? = null

) {

    ensureFirebaseAuthentication(

        onSuccess = {

            val updates =
                hashMapOf<String, Any>(

                    "status" to status,

                    "routeStatus" to routeStatus
                )

            if (latitude != null && longitude != null) {

                updates["liveLocation"] =
                    hashMapOf<String, Any>(
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "accuracy" to (accuracy ?: 0f).toDouble()
                    )
            }

            if (distanceFromRoute != null) {

                updates["distanceFromRouteMeters"] =
                    distanceFromRoute
            }

            if (status == "ACTIVE") {

                updates["startedAt"] =
                    com.google.firebase.firestore.FieldValue.serverTimestamp()
            }

            if (status == "COMPLETED") {

                val completedAt =
                    com.google.firebase.firestore.FieldValue.serverTimestamp()

                updates["completedAt"] =
                    completedAt

                updates["monitoringEndedAt"] =
                    completedAt
            }

            geoFareFirestore
                .collection("trips")
                .document(tripId)
                .update(updates)
                .addOnSuccessListener {

                    onSuccess?.invoke()
                }
                .addOnFailureListener { updateException ->

                    geoFareFirestore
                        .collection("trips")
                        .document(tripId)
                        .set(
                            updates,
                            com.google.firebase.firestore.SetOptions.merge()
                        )
                        .addOnSuccessListener {

                            onSuccess?.invoke()
                        }
                        .addOnFailureListener { fallbackException ->

                            Log.e(
                                "GeoFareFirebase",
                                "Trip status update failed: $updateException",
                                fallbackException
                            )

                            onFailure?.invoke(
                                fallbackException
                            )
                        }
                }

        },

        onFailure = { exception ->

            Log.e(
                "GeoFareFirebase",
                "Firebase authentication failed while updating trip.",
                exception
            )

            onFailure?.invoke(exception)
        }
    )
}


/* =========================================================
   OSRM ROUTING
   ========================================================= */

fun fetchOsrmRoute(

    pickup: GeoPoint,

    destination: GeoPoint,

    onSuccess:
        (
        distanceMeters: Double,
        durationSeconds: Double,
        geometry: List<GeoPoint>
    ) -> Unit,

    onFailure:
        (String) -> Unit

) {

    Thread {

        var connection:
                HttpURLConnection? = null

        try {

            val urlString =
                "https://router.project-osrm.org/" +
                        "route/v1/driving/" +

                        "${pickup.longitude}," +
                        "${pickup.latitude};" +

                        "${destination.longitude}," +
                        "${destination.latitude}" +

                        "?overview=full&geometries=geojson"

            val connectionUrl =
                URL(urlString)

            connection =
                connectionUrl.openConnection()
                        as HttpURLConnection

            connection.requestMethod = "GET"

            connection.connectTimeout = 10000

            connection.readTimeout = 15000

            connection.setRequestProperty(
                "User-Agent",
                "GeoFare/1.0 Android"
            )

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {

                throw Exception(
                    "Routing server returned HTTP $responseCode"
                )
            }

            val responseText =
                connection.inputStream
                    .bufferedReader()
                    .use { reader ->
                        reader.readText()
                    }

            val json =
                JSONObject(responseText)

            val code =
                json.optString("code")

            if (code != "Ok") {

                throw Exception(
                    json.optString(
                        "message",
                        "No route was found."
                    )
                )
            }

            val routes =
                json.getJSONArray("routes")

            if (routes.length() == 0) {

                throw Exception(
                    "No route was found."
                )
            }

            val route =
                routes.getJSONObject(0)

            val distance =
                route.getDouble("distance")

            val duration =
                route.getDouble("duration")

            val geometry =
                route.getJSONObject("geometry")

            val coordinates =
                geometry.getJSONArray("coordinates")

            val routePoints =
                mutableListOf<GeoPoint>()

            for (
            index in 0 until coordinates.length()
            ) {

                val coordinate =
                    coordinates.getJSONArray(index)

                val longitude =
                    coordinate.getDouble(0)

                val latitude =
                    coordinate.getDouble(1)

                routePoints.add(
                    GeoPoint(
                        latitude,
                        longitude
                    )
                )
            }

            Handler(
                Looper.getMainLooper()
            ).post {

                onSuccess(
                    distance,
                    duration,
                    routePoints
                )
            }

        } catch (exception: Exception) {

            Handler(
                Looper.getMainLooper()
            ).post {

                onFailure(
                    exception.message
                        ?: "Unable to calculate route."
                )
            }

        } finally {

            connection?.disconnect()
        }

    }.start()
}