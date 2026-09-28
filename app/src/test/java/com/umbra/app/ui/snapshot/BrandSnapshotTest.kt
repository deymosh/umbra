package com.umbra.app.ui.snapshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.components.UmbraIcons
import com.umbra.app.ui.components.media.UserAvatar
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class BrandSnapshotTest {
    @Test
    fun brandSheet() {
        snapshot("Brand_brandSheet") {
            SnapshotHost {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(28.dp))) {
                            Image(painterResource(R.drawable.ic_umbra_background), null, Modifier.size(120.dp))
                            Image(painterResource(R.drawable.ic_umbra_foreground_totality), null, Modifier.size(120.dp))
                        }
                        Box(Modifier.size(120.dp).clip(CircleShape)) {
                            Image(painterResource(R.drawable.ic_umbra_background), null, Modifier.size(120.dp))
                            Image(painterResource(R.drawable.ic_umbra_monochrome), null, Modifier.size(120.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EclipseMark(size = 96.dp, ignition = 0.15f)
                        EclipseMark(size = 96.dp, ignition = 0.6f)
                        EclipseMark(size = 96.dp)
                    }
                    Text("umbra", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(SnapshotFixtures.ALICE, SnapshotFixtures.BOB, SnapshotFixtures.CAROL, "d".repeat(64), "0f".repeat(32), "9a".repeat(32)).forEach {
                            UserAvatar(userProfile = null, pubkey = it, size = 48.dp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(UmbraIcons.Onion, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
                        Icon(UmbraIcons.Eclipse, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Icon(UmbraIcons.Onion, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                        Icon(UmbraIcons.Onion, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        Icon(UmbraIcons.Onion, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(72.dp))
                    }
                }
            }
        }
    }
}
