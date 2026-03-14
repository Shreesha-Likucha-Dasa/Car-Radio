package com.shreesha.carradio

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.core.content.edit
import androidx.core.net.toUri

// --- FAVORITES MANAGER ---
/**
 * Manages the user's favorite radio stations.
 * Stores favorites persistently in SharedPreferences as JSON.
 * Provides methods to get, add, remove, and check favorite status.
 */
class FavoritesManager(context: Context) {
    private val prefs = context.getSharedPreferences("car_radio_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Retrieves the list of favorite stations from SharedPreferences.
     * @return List of Station objects that are marked as favorites.
     */
    fun getFavorites(): List<Station> {
        val json = prefs.getString("favorites", null) ?: return emptyList()
        val type = object : TypeToken<List<Station>>() {}.type
        return gson.fromJson(json, type)
    }

    /**
     * Toggles the favorite status of a station.
     * If the station is already a favorite, removes it; otherwise, adds it.
     * @param station The station to toggle.
     * @return True if the station is now a favorite, false if removed.
     */
    fun toggleFavorite(station: Station): Boolean {
        val favs = getFavorites().toMutableList()
        val exists = favs.find { it.stationuuid == station.stationuuid }

        val isNowFav = if (exists != null) {
            favs.remove(exists)
            false
        } else {
            favs.add(station)
            true
        }

        prefs.edit { putString("favorites", gson.toJson(favs)) }
        return isNowFav
    }

    /**
     * Checks if a station is in the favorites list.
     * @param uuid The unique ID of the station.
     * @return True if the station is a favorite.
     */
    fun isFavorite(uuid: String?): Boolean {
        if (uuid == null) return false
        return getFavorites().any { it.stationuuid == uuid }
    }
}

// --- MAIN MEDIA SERVICE ---
/**
 * The core service for the Car Radio app, extending MediaLibraryService from Media3.
 * This service integrates with Android Auto to provide a media browser interface.
 * It handles playback of internet radio stations, browsing local/favorite/search results,
 * and custom controls like the heart button for favorites.
 *
 * Flow:
 * 1. On app start, fetches local stations based on device location.
 * 2. User browses root -> sees "Local Stations" and "Favorites" folders.
 * 3. Browsing a folder loads the list of stations as MediaItems.
 * 4. Playing a station sets it in ExoPlayer and updates context for next/prev.
 * 5. Custom heart button toggles favorites and refreshes UI.
 * 6. Search queries the radio API and caches results for browsing.
 */
@UnstableApi
class CarRadioService : MediaLibraryService() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var player: ForwardingPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private val api = RadioApi.create()
    private lateinit var favoritesManager: FavoritesManager

    // Context-Aware Lists to power the Next/Previous buttons
    private var localStationsCache = listOf<Station>() // Cached stations for user's country
    private var searchStationsCache = mutableMapOf<String, List<Station>>() // Cached search results
    private var currentContextList = listOf<Station>() // Current list for next/prev navigation
    private var rawStationsCache = mutableSetOf<Station>() // Global memory for all stations

    private var localLoadFuture: SettableFuture<LibraryResult<ImmutableList<MediaItem>>>? = null
    // Stores Android Auto's secret subscription parameters
    private val subscriptionMap = mutableMapOf<String, LibraryParams?>()

    private val COMMAND_TOGGLE_FAV = "ACTION_TOGGLE_FAVORITE"

    /**
     * Initializes the service when the app starts.
     * Sets up ExoPlayer for audio playback, wraps it for custom next/prev behavior,
     * creates the MediaLibrarySession for Android Auto integration,
     * and starts fetching local stations.
     */
    override fun onCreate() {
        super.onCreate()
        favoritesManager = FavoritesManager(this)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Wrap ExoPlayer to force Next/Previous buttons to stay visible and intercept clicks
        player = object : ForwardingPlayer(exoPlayer) {
            /**
             * Ensures next/prev commands are always available.
             */
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }

            /**
             * Checks if next/prev are available (always true here).
             */
            override fun isCommandAvailable(command: Int): Boolean {
                if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS) return true
                return super.isCommandAvailable(command)
            }

            /**
             * Intercepts next button click to play next in context list.
             */
            override fun seekToNext() { playNextOrPrevious(isNext = true) }
            /**
             * Intercepts previous button click to play previous in context list.
             */
            override fun seekToPrevious() { playNextOrPrevious(isNext = false) }
            /**
             * Alternative next method.
             */
            override fun seekToNextMediaItem() { playNextOrPrevious(isNext = true) }
            /**
             * Alternative previous method.
             */
            override fun seekToPreviousMediaItem() { playNextOrPrevious(isNext = false) }
        }

        // Listen for playback events to update UI
        player.addListener(object : Player.Listener {
            /**
             * Called when the current media item changes (e.g., new station plays).
             * Updates the heart button icon based on favorite status.
             */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
            }

            /**
             * Handles playback errors, logging them for debugging.
             */
            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                println("CarRadio Error: Stream failed to play - ${error.message}")
            }
        })

        // Create the MediaLibrarySession with custom callback for browsing
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CustomCallback()).build()

        // Prepare for async loading of local stations
        localLoadFuture = SettableFuture.create()
        fetchDefaultLocationStations()
    }

    // --- NEXT / PREVIOUS LOGIC ---
    /**
     * Handles next/previous button presses by playing the next or previous station
     * in the current context list (e.g., local stations, favorites, or search results).
     * If no context list, falls back to local stations.
     * @param isNext True for next, false for previous.
     */
    private fun playNextOrPrevious(isNext: Boolean) {
        // Fallback: if no context list, use local stations
        if (currentContextList.isEmpty()) {
            currentContextList = localStationsCache
        }
        if (currentContextList.isEmpty()) return

        val currentId = player.currentMediaItem?.mediaId ?: return

        val currentIndex = currentContextList.indexOfFirst { it.stationuuid == currentId }
        if (currentIndex == -1) {
            // If current station not in context, try to find a list that contains it
            val allLists = listOf(localStationsCache, favoritesManager.getFavorites()) + searchStationsCache.values
            for (list in allLists) {
                val idx = list.indexOfFirst { it.stationuuid == currentId }
                if (idx != -1) {
                    currentContextList = list
                    playFromIndex(list, idx, isNext)
                    return
                }
            }
            return // Can't find
        }

        playFromIndex(currentContextList, currentIndex, isNext)
    }

    /**
     * Plays the next or previous station from the given list starting from currentIndex.
     * Wraps around the list ends.
     * @param list The list of stations.
     * @param currentIndex The index of the currently playing station.
     * @param isNext True to play next, false for previous.
     */
    private fun playFromIndex(list: List<Station>, currentIndex: Int, isNext: Boolean) {
        val newIndex = if (isNext) {
            if (currentIndex + 1 < list.size) currentIndex + 1 else 0
        } else {
            if (currentIndex - 1 >= 0) currentIndex - 1 else list.size - 1
        }

        val nextStation = list[newIndex]
        val nextMediaItem = stationsToMediaItems(listOf(nextStation)).firstOrNull()

        if (nextMediaItem != null) {
            exoPlayer.setMediaItem(nextMediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            updateCustomLayout() // Refresh the heart icon instantly
        }
    }

    /**
     * Updates the custom layout in Android Auto to show the heart button
     * with the correct icon and title based on whether the current station is favorited.
     * Sends the updated layout to all connected controllers (e.g., Android Auto).
     */
    private fun updateCustomLayout() {
        val currentItem = player.currentMediaItem
        val isFav = favoritesManager.isFavorite(currentItem?.mediaId)

        val iconRes = if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val title = if (isFav) "Remove Favorite" else "Add Favorite"

        val favButton = CommandButton.Builder()
            .setDisplayName(title)
            .setIconResId(iconRes)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAV, Bundle.EMPTY))
            .build()

        // Apply the custom layout to each connected controller
        mediaLibrarySession.connectedControllers.forEach { controller ->
            mediaLibrarySession.setCustomLayout(controller, ImmutableList.of(favButton))
        }
    }

    /**
     * Filters out stations with unsupported stream formats (e.g., .m3u, .pls)
     * to ensure only playable streams are included.
     * @param stations Raw list from API.
     * @return Filtered list of playable stations.
     */
    private fun cleanStations(stations: List<Station>): List<Station> {
//        Enable filters if needed in future
//        return stations.filter { station ->
//            val url = station.url_resolved.lowercase()
//            !(url.contains(".m3u") && !url.contains(".m3u8")) && !url.contains(".pls")
//        }
        return stations
    }

    /**
     * Fetches stations for the user's country (or global if country unknown)
     * using the RadioBrowser API. Caches them and notifies the browser.
     * Runs asynchronously to avoid blocking the main thread.
     */
    private fun fetchDefaultLocationStations() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val countryCode = Locale.getDefault().country
                val rawStations = if (countryCode.isNotEmpty()) {
                    api.getStationsByCountryCode(countryCode)
                } else {
                    api.getGlobalTopStations()
                }

                localStationsCache = cleanStations(rawStations)
                currentContextList = localStationsCache // Set active context to local

                val items = stationsToMediaItems(localStationsCache)
                localLoadFuture?.set(LibraryResult.ofItemList(items, null))
                mediaLibrarySession.notifyChildrenChanged("folder_local", items.size, null)

            } catch (e: Exception) {
                e.printStackTrace()
                localLoadFuture?.set(LibraryResult.ofItemList(ImmutableList.of(), null))
            }
        }
    }

    /**
     * Converts a list of Station objects into MediaItem objects for Media3.
     * Each MediaItem includes metadata like title, artist, and artwork URI.
     * @param stations List of stations to convert.
     * @return List of MediaItem ready for playback.
     */
    private fun stationsToMediaItems(stations: List<Station>): List<MediaItem> {
        val fallbackUri = "android.resource://$packageName/${R.mipmap.ic_launcher}".toUri()

        return stations.map { station ->
            rawStationsCache.add(station)

            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(station.name.trim())
                .setArtist(station.country.ifEmpty { "Internet Radio" })
                .setIsBrowsable(false)
                .setIsPlayable(true)

            var finalArtworkUri = fallbackUri
            if (station.favicon.isNotEmpty() && station.favicon.startsWith("http")) {
                try { finalArtworkUri = station.favicon.toUri() } catch (_: Exception) {}
            }

            metadataBuilder.setArtworkUri(finalArtworkUri)

            MediaItem.Builder()
                .setMediaId(station.stationuuid)
                .setUri(station.url_resolved)
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }
    }

    /**
     * Performs a smart search across name, country, and tag using the RadioBrowser API.
     * Runs searches in parallel for efficiency, combines results, removes duplicates,
     * and limits to 20 stations.
     * @param query The search term.
     * @return List of matching stations.
     */
    private suspend fun performSmartSearch(query: String): List<Station> = coroutineScope {
        val nameDef = async { api.searchByName(query) }
        val countryDef = async { api.searchByCountry(query) }
        val tagDef = async { api.searchByTag(query) }

        val results = mutableListOf<Station>()
        try { results.addAll(nameDef.await()) } catch (_: Exception) {}
        try { results.addAll(countryDef.await()) } catch (_: Exception) {}
        try { results.addAll(tagDef.await()) } catch (_: Exception) {}

        cleanStations(results.distinctBy { it.stationuuid }.take(20))
    }

    /**
     * Returns the MediaLibrarySession for the given controller.
     * Required by MediaLibraryService.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        player.release()
        exoPlayer.release()
        mediaLibrarySession.release()
        super.onDestroy()
    }

    /**
     * Custom callback for MediaLibrarySession to handle browsing, searching, and commands.
     * This is where the media browser tree is defined and interactions are processed.
     */
    private inner class CustomCallback : MediaLibrarySession.Callback {

        /**
         * Called when a controller connects (e.g., Android Auto).
         * Adds the custom favorite toggle command to available commands.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)

            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_FAV, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                connectionResult.availablePlayerCommands
            )
        }

        /**
         * Called after connection, initializes the custom layout with the heart button.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            updateCustomLayout()
        }

        /**
         * Handles custom commands, specifically the favorite toggle.
         * Finds the current station, toggles its favorite status, updates UI,
         * and notifies the browser to refresh the favorites list.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_TOGGLE_FAV) {
                val currentItem = player.currentMediaItem
                if (currentItem != null) {
                    val uuid = currentItem.mediaId
                    val stationToFav = rawStationsCache.find { it.stationuuid == uuid }
                        ?: favoritesManager.getFavorites().find { it.stationuuid == uuid }

                    if (stationToFav != null) {
                        favoritesManager.toggleFavorite(stationToFav)
                        updateCustomLayout()
                        // THE FIX: Notify using the exact params Android Auto used!
                        val size = favoritesManager.getFavorites().size
                        Log.i("CarRadio-debug", "notifyChildrenChanged [fav] size $size")
                        mediaLibrarySession.notifyChildrenChanged("folder_favorites", size, subscriptionMap["folder_favorites"])
                        // Fallback: Notify with null just in case it subscribed generically
                        mediaLibrarySession.notifyChildrenChanged("folder_local", size, subscriptionMap["folder_local"])
                        // Also notify root to update folder title
                        mediaLibrarySession.notifyChildrenChanged("root", 2, subscriptionMap["root"])
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // Intercept Android Auto when it looks at a folder and save its settings
        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.i("CarRadio-debug", "onSubscribe $parentId")
            if (parentId == "folder_favorites") {
                subscriptionMap[parentId] = params // Save the secret settings!
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        /**
         * Defines the root of the media library tree.
         * Returns a browsable root item with search hints for Android Auto.
         */
        @OptIn(UnstableApi::class)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                .build()

            val searchHints = Bundle().apply {
                putString("android.media.browse.SEARCH_HINT", "Search Country, Station Name, or Genre")
                putString("android.media.browse.SEARCH_HINT_SECONDARY", "e.g. India, Jazz, 98.3 FM")
            }

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, LibraryParams.Builder().setExtras(searchHints).build()))
        }

        /**
         * Provides the children of a browsable MediaItem (e.g., folders).
         * Handles root (shows folders), local stations, favorites, and search results.
         * Sets currentContextList for next/prev navigation.
         */
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.i("CarRadio-debug", "onGetChildren $parentId")
            if (parentId == "root") {
                val localFolder = MediaItem.Builder()
                    .setMediaId("folder_local")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Local Stations").setIsBrowsable(true).setIsPlayable(false).build())
                    .build()
                val favFolder = MediaItem.Builder()
                    .setMediaId("folder_favorites")
//                    enable favorite count when you fix immediet refresh issue
//                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Favorites (${favoritesManager.getFavorites().size})").setIsBrowsable(true).setIsPlayable(false).build())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Favorites").setIsBrowsable(true).setIsPlayable(false).build())
                    .build()

                return Futures.immediateFuture(LibraryResult.ofItemList(listOf(localFolder, favFolder), params))
            }

            if (parentId == "folder_local") {
                currentContextList = localStationsCache // Let Next/Prev know we are here

                if (localStationsCache.isNotEmpty()) {
                    val items = stationsToMediaItems(localStationsCache)
                    return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
                }
                if (localLoadFuture == null || localLoadFuture!!.isDone) {
                    localLoadFuture = SettableFuture.create()
                    fetchDefaultLocationStations()
                }
                return localLoadFuture!!
            }

            Log.i("CarRadio-debug", "folder_fav size checking...")

            if (parentId == "folder_favorites") {
                currentContextList = favoritesManager.getFavorites() // Let Next/Prev know we are here
                val favItems = stationsToMediaItems(currentContextList)
//                val favFolder = MediaItem.Builder()
//                    .setMediaId("folder_favorites")
//                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Favorites (${currentContextList.size})").setIsBrowsable(true).setIsPlayable(false).build())
//                    .build()
                return Futures.immediateFuture(LibraryResult.ofItemList(favItems, params))
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        /**
         * Handles search requests by performing smart search and caching results.
         * Notifies the browser of available results.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            CoroutineScope(Dispatchers.IO).launch {
                val stations = performSmartSearch(query)
                searchStationsCache[query] = stations
                session.notifySearchResultChanged(browser, query, stations.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        /**
         * Returns the search results for a query, setting context for next/prev.
         */
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

            val stations = searchStationsCache[query] ?: emptyList()
            currentContextList = stations // Set context for next/prev

            val items = stationsToMediaItems(stations)
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        /**
         * Resolves requested MediaItems by finding the corresponding Station and converting to full MediaItem.
         * Used when Android Auto requests playback of a specific item.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {

            val resolvedItems = mediaItems.mapNotNull { requestedItem ->
                val station = rawStationsCache.find { it.stationuuid == requestedItem.mediaId }
                    ?: favoritesManager.getFavorites().find { it.stationuuid == requestedItem.mediaId }

                if (station != null) {
                    stationsToMediaItems(listOf(station)).firstOrNull()
                } else {
                    null
                }
            }.toMutableList()

            return Futures.immediateFuture(resolvedItems)
        }
    }
}