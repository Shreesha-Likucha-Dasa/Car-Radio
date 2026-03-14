package com.shreesha.carradio

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Data class representing a radio station from the RadioBrowser API.
 * Contains all necessary fields for display and playback.
 */
data class Station(
    val stationuuid: String,  // Unique identifier for the station
    val name: String,         // Station name
    val url_resolved: String, // Direct stream URL
    val country: String,      // Country of origin
    val favicon: String,      // URL to station logo/favicon
    val tags: String          // Genres/tags (comma-separated)
)

/**
 * Retrofit interface for interacting with the RadioBrowser API.
 * Provides methods to fetch stations by country, global top, and search by various criteria.
 * All methods are suspend functions for coroutine support.
 */
interface RadioApi {
    /**
     * Fetches top 20 stations for a specific country, ordered by click count.
     * Used for local stations based on device locale.
     * @param countryCode ISO country code (e.g., "US", "IN").
     * @return List of Station objects.
     */
    @GET("json/stations/search?limit=20&order=clickcount&reverse=true")
    suspend fun getStationsByCountryCode(@Query("countrycode") countryCode: String): List<Station>

    /**
     * Fetches global top 20 stations by click count.
     * Fallback when country detection fails.
     * @return List of Station objects.
     */
    @GET("json/stations/topclick/20")
    suspend fun getGlobalTopStations(): List<Station>

    /**
     * Searches stations by name, ordered by popularity.
     * @param query Search term for station name.
     * @return List of matching Station objects.
     */
    @GET("json/stations/search?limit=20&order=clickcount&reverse=true")
    suspend fun searchByName(@Query("name") query: String): List<Station>

    /**
     * Searches stations by country, ordered by popularity.
     * @param query Search term for country.
     * @return List of matching Station objects.
     */
    @GET("json/stations/search?limit=20&order=clickcount&reverse=true")
    suspend fun searchByCountry(@Query("country") query: String): List<Station>

    /**
     * Searches stations by tag/genre, ordered by popularity.
     * @param query Search term for tags.
     * @return List of matching Station objects.
     */
    @GET("json/stations/search?limit=20&order=clickcount&reverse=true")
    suspend fun searchByTag(@Query("tag") query: String): List<Station>

    /**
     * Companion object to create Retrofit instance.
     * Uses Gson for JSON conversion and the RadioBrowser API base URL.
     */
    companion object {
        private const val BASE_URL = "https://de1.api.radio-browser.info/"

        /**
         * Creates and returns a configured RadioApi instance.
         * @return RadioApi interface implementation.
         */
        fun create(): RadioApi {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioApi::class.java)
        }
    }
}