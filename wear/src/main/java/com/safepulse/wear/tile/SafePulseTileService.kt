package com.safepulse.wear.tile

import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.protolayout.ResourceBuilders as ProtoResourceBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.safepulse.wear.WearSafePulseApp
import com.safepulse.wear.data.WearRiskLevel

class SafePulseTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val state = (application as WearSafePulseApp).communicationManager.safetyState.value
        val phoneConnected = (application as WearSafePulseApp).communicationManager.phoneConnected.value

        val riskColor = when (state.riskLevel) {
            WearRiskLevel.LOW -> 0xFF4CAF50.toInt()
            WearRiskLevel.MEDIUM -> 0xFFFF9800.toInt()
            WearRiskLevel.HIGH -> 0xFFF44336.toInt()
        }

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(192f))
            .setHeight(dp(192f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF101418.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(dp(176f))
                    .setHeight(dp(176f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(tileText("SafePulse", 16f, 0xFFFFFFFF.toInt()))
                    .addContent(tileText(if (phoneConnected) "Phone connected" else "Phone offline", 11f, if (phoneConnected) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()))
                    .addContent(tileText("Risk ${state.riskLevel.name}", 14f, riskColor))
                    .addContent(tileText(if (state.liveTrackingActive) "SOS ${state.liveTrackingSessionId}" else "SOS ready", 13f, if (state.liveTrackingActive) 0xFFF44336.toInt() else 0xFFFFFFFF.toInt()))
                    .addContent(tileText("HR ${if (state.heartRate > 0) state.heartRate else "--"}", 12f, 0xFFE53935.toInt()))
                    .addContent(tileText(if (state.trustedJourneyActive) "Journey active" else "No journey", 10f, 0xFFB0BEC5.toInt()))
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(60_000)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ProtoResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ProtoResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        )
    }

    private fun tileText(text: String, size: Float, color: Int): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .build()
            )
            .build()
    }
}
