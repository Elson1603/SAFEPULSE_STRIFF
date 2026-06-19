package com.safepulse.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService.ComplicationRequestListener
import com.safepulse.wear.WearSafePulseApp
import com.safepulse.wear.data.WearRiskLevel
import com.safepulse.wear.presentation.WearMainActivity

class SafePulseComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        listener.onComplicationData(createData(request.complicationType))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return if (type == ComplicationType.SHORT_TEXT) {
            shortTextData("SOS", "Ready", "SafePulse emergency status")
        } else {
            NoDataComplicationData()
        }
    }

    private fun createData(type: ComplicationType): ComplicationData {
        if (type != ComplicationType.SHORT_TEXT) return NoDataComplicationData()

        val manager = (application as WearSafePulseApp).communicationManager
        val state = manager.safetyState.value
        val phoneConnected = manager.phoneConnected.value

        val text = when {
            state.liveTrackingActive -> "SOS"
            state.riskLevel == WearRiskLevel.HIGH -> "HIGH"
            state.riskLevel == WearRiskLevel.MEDIUM -> "MED"
            else -> "LOW"
        }
        val title = when {
            state.liveTrackingActive -> "Live"
            !phoneConnected -> "No phone"
            state.trustedJourneyActive -> "Journey"
            else -> "Ready"
        }

        return shortTextData(
            text = text,
            title = title,
            description = "SafePulse $title status, risk ${state.riskLevel.name.lowercase()}",
            tapAction = openAppPendingIntent()
        )
    }

    private fun shortTextData(
        text: String,
        title: String,
        description: String,
        tapAction: PendingIntent? = null
    ): ShortTextComplicationData {
        return ShortTextComplicationData.Builder(
            plainText(text),
            plainText(description)
        )
            .setTitle(plainText(title))
            .setTapAction(tapAction)
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, WearMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            42,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun plainText(text: String): ComplicationText {
        return PlainComplicationText.Builder(text).build()
    }
}
