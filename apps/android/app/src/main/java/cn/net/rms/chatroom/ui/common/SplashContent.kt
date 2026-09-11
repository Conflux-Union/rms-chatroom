package cn.net.rms.chatroom.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.theme.SurfaceDark
import cn.net.rms.chatroom.ui.theme.TiColor

/**
 * In-app splash shown while the auth session is being restored.
 * Uses the same background color and logo size (288dp icon area) as the
 * system splash screen so the transition between the two is seamless.
 */
@Composable
fun SplashContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(288.dp)
        )
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 192.dp)
                .size(36.dp),
            color = TiColor
        )
    }
}
