package com.eliav.wacompletemute

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** מסך סטטוס בלבד: גרסה, חותמת בנייה וגישה להתראות. אין לוגים וקבצים חיצוניים. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "Eliav WA Modul v${BuildConfig.VERSION_NAME}"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "build ${BuildConfig.BUILD_STAMP}"
            textSize = 13f
            setPadding(0, 4, 0, 16)
        })

        val notifView = TextView(this).apply {
            setPadding(12, 10, 12, 10)
            textSize = 14f
        }
        if (isNotificationAccessGranted()) {
            notifView.text = "✓ גישה להתראות מאושרת"
            notifView.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            notifView.text = "✗ גישה להתראות חסרה — לחץ כאן"
            notifView.setBackgroundColor(0xFFFFCDD2.toInt())
            notifView.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(notifView)

        root.addView(TextView(this).apply {
            text = "\nשימוש: בתוך קבוצה ב-WhatsApp, שלוש נקודות (⋮) ← \"השתק לגמרי\"."
            textSize = 13f
        })

        return root
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }
}
