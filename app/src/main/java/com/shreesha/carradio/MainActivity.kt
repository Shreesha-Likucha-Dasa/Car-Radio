package com.shreesha.carradio

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity is a simple placeholder activity for the Car Radio app.
 * Since this is primarily an Android Auto media app, the main functionality
 * is handled by CarRadioService, which runs in the background.
 *
 * This activity displays user instructions on how to use the app with Android Auto.
 * It serves as a fallback if the user opens the app directly on the phone.
 *
 * Flow:
 * - App launches → onCreate called → Displays instructions → User connects to Android Auto.
 */
class MainActivity : AppCompatActivity() {
    /**
     * Called when the activity is created.
     * Sets up a TextView with usage instructions and sets it as the content view.
     * No complex logic here; the real work is in CarRadioService.
     * @param savedInstanceState Bundle for restoring state (not used here).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Instructional message for users
        val message = """
            
🚗 Car Radio — Android Auto App

This app works on your car screen via Android Auto.

To use:

1. Connect your phone to your car
2. Open Android Auto
3. Select "Car Radio" from media apps
4. Browse and play stations safely while driving

Tip:
Use voice commands to search stations.

Enjoy safe listening!
        """.trimIndent()

        // Create and configure TextView
        val tv = TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(40, 80, 40, 40)
        }

        // Set the TextView as the activity's content
        setContentView(tv)
    }
}