package com.lucasjosino.on_audio_query.queries

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucasjosino.on_audio_query.PluginProvider
import com.lucasjosino.on_audio_query.controllers.PermissionController
import com.lucasjosino.on_audio_query.queries.helper.QueryHelper
import com.lucasjosino.on_audio_query.types.checkArtistsUriType
import com.lucasjosino.on_audio_query.types.sorttypes.checkArtistSortType
import com.lucasjosino.on_audio_query.utils.artistProjection
import io.flutter.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference

/** OnArtistsQuery */
class ArtistQuery : ViewModel() {

    companion object {
        private const val TAG = "OnArtistsQuery"
    }

    //Main parameters
    private val helper = QueryHelper()
    private var currentJob: Job? = null
    private val currentResult = AtomicReference<MethodChannel.Result?>(null)

    // None of this methods can be null.
    private lateinit var uri: Uri
    private lateinit var resolver: ContentResolver
    private lateinit var sortType: String

    /**
     * Method to "query" all artists.
     */
    fun queryArtists() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver

        // Cancel any existing job and set new result
        currentJob?.cancel()
        currentResult.set(result)

        // Sort: Type and Order
        sortType = checkArtistSortType(
            call.argument<Int>("sortType"),
            call.argument<Int>("orderType")!!,
            call.argument<Boolean>("ignoreCase")!!
        )

        // Check uri:
        //   * 0 -> External.
        //   * 1 -> Internal.
        uri = checkArtistsUriType(call.argument<Int>("uri")!!)

        Log.d(TAG, "Query config: ")
        Log.d(TAG, "\tsortType: $sortType")
        Log.d(TAG, "\turi: $uri")

        // Query everything in background for a better performance.
        currentJob = viewModelScope.launch {
            try {
                if (!isActive) {
                    Log.d(TAG, "Coroutine was cancelled before starting query")
                    return@launch
                }

                val queryResult = loadArtists()
                
                if (!isActive) {
                    Log.d(TAG, "Coroutine was cancelled before sending result")
                    return@launch
                }

                sendResultSafely(queryResult, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying artists: ${e.message}")
                if (isActive) {
                    sendResultSafely(null, e)
                }
            }
        }
    }

    private fun sendResultSafely(data: Any?, error: Exception?) {
        try {
            val result = currentResult.getAndSet(null)
            if (result != null) {
                if (error != null) {
                    result.error("QUERY_ERROR", "Error querying artists: ${error.message}", null)
                } else {
                    result.success(data)
                }
            } else {
                Log.w(TAG, "Result was null or already sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending result: ${e.message}")
        }
    }

    // Loading in Background
    private suspend fun loadArtists(): ArrayList<MutableMap<String, Any?>> =
        withContext(Dispatchers.IO) {
            // Setup the cursor with 'uri', 'projection' and 'sortType'.
            val cursor = resolver.query(uri, artistProjection, null, null, sortType)

            val artistList: ArrayList<MutableMap<String, Any?>> = ArrayList()

            Log.d(TAG, "Cursor count: ${cursor?.count}")

            // For each item(artist) inside this "cursor", take one and "format"
            // into a 'Map<String, dynamic>'.
            while (cursor != null && cursor.moveToNext()) {
                val tempData: MutableMap<String, Any?> = HashMap()

                for (artistMedia in cursor.columnNames) {
                    tempData[artistMedia] = helper.loadArtistItem(artistMedia, cursor)
                }

                artistList.add(tempData)
            }

            // Close cursor to avoid memory leaks.
            cursor?.close()
            return@withContext artistList
        }
}
