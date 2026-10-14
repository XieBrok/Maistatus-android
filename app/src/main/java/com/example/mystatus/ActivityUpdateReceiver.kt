package com.example.mystatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import org.json.JSONObject

class ActivityUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!StatusStore.collectionEnabled(context)) return
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val mostLikely = result.mostProbableActivity
        val value = "${mostLikely.type}:${mostLikely.confidence}"
        if (StatusStore.activity(context) == value) return
        StatusStore.setActivity(context, value)
        EventQueue.enqueue(context, "activity", JSONObject()
            .put("activity_type", mostLikely.type)
            .put("confidence", mostLikely.confidence),
            result.time)
    }
}
