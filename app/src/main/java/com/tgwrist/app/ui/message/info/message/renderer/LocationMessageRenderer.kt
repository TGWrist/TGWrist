package com.tgwrist.app.ui.message.info.message.renderer

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import com.tgwrist.app.utils.openInBrowser
import com.tgwrist.app.utils.setClipboardText
import org.drinkless.tdlib.TdApi
import java.util.Locale

/**
 * 可长按复制的信息卡片，统一了经纬度/直播时长/朝向等条目的展现与交互。
 */
@Composable
private fun TransformingLazyColumnItemScope.LocationInfoCard(
    title: String,
    value: String,
    transformationSpec: TransformationSpec,
    onCopy: (String) -> Unit,
) {
    TitleCard(
        title = { Text(title) },
        onClick = { },
        onLongClick = { onCopy(value) },
        transformation = SurfaceTransformation(transformationSpec),
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec)
    ) {
        Text(value)
    }
}

@Composable
fun LocationMessageRenderer(
    content: TdApi.MessageLocation,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.Copied_clipboard)
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    val location = content.location
    val latitude = remember(location.latitude) {
        String.format(Locale.US, "%.6f", location.latitude)
    }
    val longitude = remember(location.longitude) {
        String.format(Locale.US, "%.6f", location.longitude)
    }
    val coordinates = remember(latitude, longitude) { "$latitude, $longitude" }
    val isLiveLocation = content.livePeriod != 0
    val hasAccuracy = location.horizontalAccuracy > 0

    // 直播相关文案预取，避免在 lambda 里重复 stringResource
    val livePeriodText = if (isLiveLocation) {
        if (content.livePeriod == Int.MAX_VALUE) {
            stringResource(R.string.location_forever)
        } else {
            stringResource(R.string.location_seconds_value, content.livePeriod)
        }
    } else ""
    val expiresInText =
        if (isLiveLocation) stringResource(R.string.location_seconds_value, content.expiresIn) else ""
    val headingText =
        if (isLiveLocation) stringResource(R.string.location_degrees_value, content.heading) else ""
    val proximityText = if (isLiveLocation) {
        stringResource(R.string.location_meters_value, content.proximityAlertRadius)
    } else ""
    val accuracyText =
        if (hasAccuracy) stringResource(R.string.location_meters_value, location.horizontalAccuracy.toInt()) else ""

    fun openLocation() {
        val query = "${location.latitude},${location.longitude}"
        val geoUri = "geo:$query?q=$query".toUri()
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
        openInBrowser(
            context,
            "https://www.google.com/maps/search/?api=1&query=$query"
        )
    }

    fun copyText(text: String) {
        context.setClipboardText(text)
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ========== 位置图标 ==========
            item(key = "location_icon") {
                ListHeader(
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() * 0.2f, bottom = 4.dp, end = contentPadding.calculateEndPadding(
                        LayoutDirection.Ltr), start = contentPadding.calculateStartPadding(LayoutDirection.Rtl)),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = "Location",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item(key = "open_location_button") {
                FilledTonalButton(
                    onClick = { openLocation() },
                    label = {
                        Text(
                            text = stringResource(R.string.location_open),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null
                        )
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            item(key = "latitude") {
                LocationInfoCard(
                    title = stringResource(R.string.location_latitude),
                    value = latitude,
                    transformationSpec = transformationSpec,
                    onCopy = ::copyText
                )
            }

            item(key = "longitude") {
                LocationInfoCard(
                    title = stringResource(R.string.location_longitude),
                    value = longitude,
                    transformationSpec = transformationSpec,
                    onCopy = ::copyText
                )
            }

            if (hasAccuracy) {
                item(key = "horizontal_accuracy") {
                    LocationInfoCard(
                        title = stringResource(R.string.location_horizontal_accuracy),
                        value = accuracyText,
                        transformationSpec = transformationSpec,
                        onCopy = ::copyText
                    )
                }
            }

            if (isLiveLocation) {
                item(key = "live_period") {
                    LocationInfoCard(
                        title = stringResource(R.string.location_live_period),
                        value = livePeriodText,
                        transformationSpec = transformationSpec,
                        onCopy = ::copyText
                    )
                }

                item(key = "expires_in") {
                    LocationInfoCard(
                        title = stringResource(R.string.location_expires_in),
                        value = expiresInText,
                        transformationSpec = transformationSpec,
                        onCopy = ::copyText
                    )
                }

                if (content.heading in 1..360) {
                    item(key = "heading") {
                        LocationInfoCard(
                            title = stringResource(R.string.location_heading),
                            value = headingText,
                            transformationSpec = transformationSpec,
                            onCopy = ::copyText
                        )
                    }
                }

                if (content.proximityAlertRadius > 0) {
                    item(key = "proximity_alert_radius") {
                        LocationInfoCard(
                            title = stringResource(R.string.location_proximity_alert_radius),
                            value = proximityText,
                            transformationSpec = transformationSpec,
                            onCopy = ::copyText
                        )
                    }
                }
            }

            item(key = "reply") {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            item(key = "forward") {
                ForwardMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            if (messageRenderContext.chat != null) {
                item(key = "delete") {
                    DeleteMessageButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        chat = messageRenderContext.chat,
                        messageId = messageRenderContext.messageId,
                        properties = messageRenderContext.properties,
                        useDialog = messageRenderContext.useDialog
                    )
                }
            }

            item(key = "bottom_spacing") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
