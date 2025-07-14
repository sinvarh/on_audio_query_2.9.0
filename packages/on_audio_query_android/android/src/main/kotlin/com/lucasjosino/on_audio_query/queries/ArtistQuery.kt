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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OnArtistsQuery */
class ArtistQuery : ViewModel() {

    companion object {
        private const val TAG = "OnArtistsQuery"
    }

    //Main parameters
    private val helper = QueryHelper()
    private var isReplySent = false

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
        this.isReplySent = false

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
        viewModelScope.launch {
            try {
                val queryResult = loadArtists()
                sendResult(result, queryResult, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying artists: ${e.message}")
                sendResult(result, null, e)
            }
        }
    }

    private fun sendResult(result: MethodChannel.Result, data: Any?, error: Exception?) {
        synchronized(this) {
            if (isReplySent) {
                Log.w(TAG, "Reply already sent, ignoring duplicate result")
                return
            }
            isReplySent = true
            
            try {
                if (error != null) {
                    result.error("QUERY_ERROR", "Error querying artists: ${error.message}", null)
                } else {
                    result.success(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending result: ${e.message}")
            }
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
