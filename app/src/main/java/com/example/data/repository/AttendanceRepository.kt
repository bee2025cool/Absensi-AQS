package com.example.data.repository

import android.util.Log
import com.example.data.local.AttendanceDao
import com.example.data.model.AttendanceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class AttendanceRepository(private val attendanceDao: AttendanceDao) {

    private val client = OkHttpClient()
    private val sheetUrl = "https://docs.google.com/spreadsheets/d/1zC7f8o6CYhZiheWs_K9acUA4FJUivmqFmxraiiDxHWQ/export?format=csv"

    // Locally cached map of users fetched from sheet, fallback if offline or loading fails
    private var usersCache = mutableMapOf<String, String>()

    val allLogs: Flow<List<AttendanceLog>> = attendanceDao.getAllLogs()

    suspend fun insertLog(log: AttendanceLog) {
        withContext(Dispatchers.IO) {
            attendanceDao.insertLog(log)
        }
    }

    suspend fun clearLogs() {
        withContext(Dispatchers.IO) {
            attendanceDao.clearAllLogs()
        }
    }

    /**
     * Fetches the user list sheet, parses it, and updates the local cache.
     * Returns true if successful, false otherwise.
     */
    suspend fun fetchUsersFromSheet(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sheetUrl)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to download sheet: HTTP ${response.code}"))
                }

                val bodyString = response.body?.string() ?: ""
                if (bodyString.isBlank()) {
                    return@withContext Result.failure(Exception("Empty spreadsheet response"))
                }

                val parsedUsers = parseCsvToUserMap(bodyString)
                if (parsedUsers.isEmpty()) {
                    return@withContext Result.failure(Exception("No users parsed from sheet"))
                }

                synchronized(this@AttendanceRepository) {
                    usersCache.clear()
                    usersCache.putAll(parsedUsers)
                }

                Log.d("AttendanceRepository", "Successfully fetched ${parsedUsers.size} users from Google Sheet")
                return@withContext Result.success(parsedUsers)
            }
        } catch (e: Exception) {
            Log.e("AttendanceRepository", "Error fetching Google Sheet: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Local authentication that checks against current cache first.
     * If cache is empty, it attempts to fetch if internet is active.
     */
    suspend fun authenticateUser(usernameInput: String, passwordInput: String): AuthenticationResult {
        val usernameTrimmed = usernameInput.trim()
        val passwordTrimmed = passwordInput.trim()

        // Sync list from sheet if empty
        if (usersCache.isEmpty()) {
            val result = fetchUsersFromSheet()
            if (result.isFailure) {
                // If offline and cache is empty, let's use a backup local fallback user list so the app is always functional!
                val dummyUsers = mapOf(
                    "USER01" to "USER01",
                    "USER02" to "USER02",
                    "USER03" to "USER03"
                )
                usersCache.putAll(dummyUsers)
            }
        }

        val storedPassword = usersCache[usernameTrimmed]
        return if (storedPassword != null && storedPassword == passwordTrimmed) {
            AuthenticationResult.Success(usernameTrimmed)
        } else {
            AuthenticationResult.Failure("Invalid username or password. Please try again.")
        }
    }

    private fun parseCsvToUserMap(csvData: String): Map<String, String> {
        val userMap = mutableMapOf<String, String>()
        val lines = csvData.split(Regex("\\r?\\n"))

        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split(",")
            if (parts.size >= 2) {
                val user = parts[0].trim()
                val pass = parts[1].trim()
                
                // Skip header line "USER,PASSWORD"
                if (user.equals("USER", ignoreCase = true) && pass.equals("PASSWORD", ignoreCase = true)) {
                    continue
                }
                userMap[user] = pass
            }
        }
        return userMap
    }
}

sealed interface AuthenticationResult {
    data class Success(val username: String) : AuthenticationResult
    data class Failure(val errorMessage: String) : AuthenticationResult
}
