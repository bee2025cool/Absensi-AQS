package com.example.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AttendanceLog
import com.example.data.repository.AttendanceRepository
import com.example.data.repository.AuthenticationResult
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AttendanceViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AttendanceRepository(database.attendanceDao())

    // UI state for Login
    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Initial)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    // Current logged-in user
    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser: StateFlow<String?> = _currentUser.asStateFlow()

    // Location State
    private val _locationState = MutableStateFlow<LocationData>(
        LocationData(
            latitude = -6.2088, // Default Jakarta Monas
            longitude = 106.8456,
            address = "Monas, Jakarta Pusat, Indonesia (Pencarian Lokasi...)",
            isSimulated = true
        )
    )
    val locationState: StateFlow<LocationData> = _locationState.asStateFlow()

    // Clock
    private val _currentTime = MutableStateFlow("")
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    // Clock check-in stamp stored when clicking "Masuk Kerja" to show on screen
    private val _checkInTimeMessage = MutableStateFlow<String?>(null)
    val checkInTimeMessage: StateFlow<String?> = _checkInTimeMessage.asStateFlow()

    // List of past log entries from db
    val logsList: StateFlow<List<AttendanceLog>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private var clockJob: Job? = null

    init {
        startClock()
        // Fetch sheet users immediately in background so it's ready when user opens login screen
        viewModelScope.launch {
            repository.fetchUsersFromSheet()
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Jakarta")
                }
                val sdfFull = SimpleDateFormat("HH:mm:ss · dd MMMM yyyy", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Jakarta")
                }
                _currentTime.value = sdfFull.format(Date())
                delay(1000)
            }
        }
    }

    fun login(usernameInput: String, passwordInput: String) {
        if (usernameInput.isBlank() || passwordInput.isBlank()) {
            _loginState.value = LoginUiState.Error("Username dan password tidak boleh kosong.")
            return
        }

        viewModelScope.launch {
            _loginState.value = LoginUiState.Loading
            delay(600) // Aesthetic delay for animation & feedback
            
            when (val result = repository.authenticateUser(usernameInput, passwordInput)) {
                is AuthenticationResult.Success -> {
                    _currentUser.value = result.username
                    _loginState.value = LoginUiState.Success(result.username)
                }
                is AuthenticationResult.Failure -> {
                    _loginState.value = LoginUiState.Error(result.errorMessage)
                }
            }
        }
    }

    fun logout() {
        _currentUser.value = null
        _loginState.value = LoginUiState.Initial
        _checkInTimeMessage.value = null
    }

    fun resetLoginError() {
        if (_loginState.value is LoginUiState.Error) {
            _loginState.value = LoginUiState.Initial
        }
    }

    /**
     * Attempts to read coordinates from FusedLocationProviderClient
     */
    @SuppressLint("MissingPermission")
    fun updateLocation() {
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    resolveAddress(location.latitude, location.longitude, isSimulated = false)
                } else {
                    // Fallback to last known location
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            resolveAddress(lastLoc.latitude, lastLoc.longitude, isSimulated = false)
                        } else {
                            // If both return null (e.g. cloud emulator), keep current simulated Jakarta location
                            resolveAddress(-6.2088, 106.8456, isSimulated = true)
                        }
                    }
                }
            }.addOnFailureListener { e ->
                Log.e("AttendanceViewModel", "Failed to retrieve location: ${e.message}")
                // Fallback to simulated location
                resolveAddress(-6.2088, 106.8456, isSimulated = true)
            }
        } catch (e: SecurityException) {
            Log.e("AttendanceViewModel", "Location permission missing: ${e.message}")
            resolveAddress(-6.2088, 106.8456, isSimulated = true)
        }
    }

    private fun resolveAddress(latitude: Double, longitude: Double, isSimulated: Boolean) {
        viewModelScope.launch {
            var addressText = "Alamat tidak ditemukan"
            try {
                val geocoder = Geocoder(getApplication(), Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val parts = mutableListOf<String>()
                    for (i in 0..address.maxAddressLineIndex) {
                        parts.add(address.getAddressLine(i))
                    }
                    addressText = parts.joinToString(", ")
                }
            } catch (e: Exception) {
                Log.e("AttendanceViewModel", "Geocoder failed: ${e.message}")
                addressText = if (isSimulated) {
                    "Jl. Merdeka Barat, Gambir, Jakarta Pusat, DKI Jakarta (Simulasi GPS)"
                } else {
                    "Koordinat: ($latitude, $longitude)"
                }
            }

            _locationState.value = LocationData(
                latitude = latitude,
                longitude = longitude,
                address = addressText,
                isSimulated = isSimulated
            )
        }
    }

    /**
     * Submits attendance, records to DB history, and saves time message display.
     * Computes dynamically if this is "Absensi Masuk" or "Absensi Pulang" using the logging history.
     */
    fun performCheckIn(onSuccess: (String) -> Unit) {
        val user = _currentUser.value ?: "USER"
        val currentTimeMillis = System.currentTimeMillis()
        
        // Format readable time for message
        val sdf = SimpleDateFormat("HH:mm:ss 'WIB'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        val timeStr = sdf.format(Date(currentTimeMillis))
        _checkInTimeMessage.value = timeStr

        val currentLoc = _locationState.value

        // Filter logs for this specific user to find last registered status
        val matches = logsList.value.filter { it.username.equals(user, ignoreCase = true) }
        val lastLog = matches.maxByOrNull { it.timestamp }
        val nextStatus = if (lastLog != null && lastLog.status == "Absensi Masuk") {
            "Absensi Pulang"
        } else {
            "Absensi Masuk"
        }

        viewModelScope.launch {
            val log = AttendanceLog(
                username = user,
                timestamp = currentTimeMillis,
                latitude = currentLoc.latitude,
                longitude = currentLoc.longitude,
                status = nextStatus
            )
            repository.insertLog(log)
            onSuccess(nextStatus)
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }
}

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val isSimulated: Boolean
)

sealed interface LoginUiState {
    object Initial : LoginUiState
    object Loading : LoginUiState
    data class Success(val username: String) : LoginUiState
    data class Error(val message: String) : LoginUiState
}
