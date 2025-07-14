package com.lucasjosino.on_audio_query.queries

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucasjosino.on_audio_query.PluginProvider
import com.lucasjosino.on_audio_query.queries.helper.QueryHelper
import com.lucasjosino.on_audio_query.types.checkAlbumsUriType
import com.lucasjosino.on_audio_query.types.sorttypes.checkAlbumSortType
import io.flutter.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference

/** OnAlbumsQuery */
class AlbumQuery : ViewModel() {

    companion object {
        private const val TAG = "OnAlbumsQuery"
    }

    // Main parameters.
    private val helper = QueryHelper()
    private var currentJob: Job? = null
    private val currentResult = AtomicReference<MethodChannel.Result?>(null)

    private lateinit var uri: Uri
    private lateinit var sortType: String
    private lateinit var resolver: ContentResolver

    /**
     * Method to "query" all albums.
     */
    fun queryAlbums() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver

        // Cancel any existing job and set new result
        currentJob?.cancel()
        currentResult.set(result)

        // Sort: Type and Order.
        sortType = checkAlbumSortType(
            call.argument<Int>("sortType"),
            call.argument<Int>("orderType")!!,
            call.argument<Boolean>("ignoreCase")!!
        )

        // Check uri:
        //   * 0 -> External
        //   * 1 -> Internal
        uri = checkAlbumsUriType(call.argument<Int>("uri")!!)

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

                val queryResult = loadAlbums()
                
                if (!isActive) {
                    Log.d(TAG, "Coroutine was cancelled before sending result")
                    return@launch
                }

                sendResultSafely(queryResult, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying albums: ${e.message}")
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
                    result.error("QUERY_ERROR", "Error querying albums: ${error.message}", null)
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
    private suspend fun loadAlbums(): ArrayList<MutableMap<String, Any?>> =
        withContext(Dispatchers.IO) {
            // Setup the cursor with 'uri', 'projection'(null == all items) and 'sortType'.
            val cursor = resolver.query(uri, null, null, null, sortType)

            val albumList: ArrayList<MutableMap<String, Any?>> = ArrayList()

            Log.d(TAG, "Cursor count: ${cursor?.count}")

            // For each item(album) inside this "cursor", take one and "format"
            // into a 'Map<String, dynamic>'.
            while (cursor != null && cursor.moveToNext()) {
                val tempData: MutableMap<String, Any?> = HashMap()

                for (albumMedia in cursor.columnNames) {
                    tempData[albumMedia] = helper.loadAlbumItem(albumMedia, cursor)
                }

                // Android 10 and above 'album_art' will return null. Use 'queryArtwork' instead.
                val art = tempData["album_art"].toString()
                if (art.isEmpty()) tempData.remove("album_art")

                albumList.add(tempData)
            }

            // Close cursor to avoid memory leaks.
            cursor?.close()
            return@withContext albumList
        }
}
