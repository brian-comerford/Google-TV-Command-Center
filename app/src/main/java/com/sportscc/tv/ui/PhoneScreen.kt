@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.sportscc.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sportscc.tv.SyncStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun PhoneScreen(siteUrl: String, code: String, status: SyncStatus, onNewCode: () -> Unit, onDone: () -> Unit) = ScreenFrame {
    val link = "$siteUrl?tv=$code"
    val qr = remember(link) { qrBitmap(link) }
    Row {
        Column(Modifier.weight(1f)) {
            ScreenTitle("Edit your list on your phone", "Typing is easier there. Changes show up here within about 20 seconds.")
            Text("1.  Scan the QR code with your phone's camera, or open", fontSize = 16.sp, color = Palette.Text)
            Text(siteUrl.removePrefix("https://"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Accent, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
            Spacer(Modifier.height(12.dp))
            Text("2.  Enter this TV's code (only needed once):", fontSize = 16.sp, color = Palette.Text)
            Text(
                code, fontSize = 46.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                color = Palette.Text, letterSpacing = 4.sp, modifier = Modifier.padding(start = 26.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("3.  Add teams, players and props, then tap Send to TV.", fontSize = 16.sp, color = Palette.Text)
            Spacer(Modifier.height(18.dp))
            val last = status.lastFromPhone
            Text(
                when {
                    status.error != null -> "Can't reach the sync service: ${status.error}"
                    last != null -> "Last update from your phone: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}"
                    else -> "Waiting for your phone…"
                },
                fontSize = 14.sp, color = if (status.error != null) Palette.Warn else Palette.Muted,
            )
            Spacer(Modifier.height(18.dp))
            Row {
                FocusButton("Done", onDone, Modifier.focusRequester(rememberInitialFocus()), accent = Palette.Win)
                Spacer(Modifier.width(10.dp))
                FocusButton("New code (unlink phones)", onNewCode)
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = qr,
                contentDescription = "QR code for the phone website",
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("Scan to open with this code filled in", fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

/** One pixel per QR module; drawn with FilterQuality.None so it scales up crisp. */
private fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) {
        bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp.asImageBitmap()
}
