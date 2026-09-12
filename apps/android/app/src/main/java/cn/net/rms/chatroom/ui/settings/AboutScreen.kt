package cn.net.rms.chatroom.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.theme.PaperDarkSubtle
import cn.net.rms.chatroom.ui.theme.InkDarkFaint
import cn.net.rms.chatroom.ui.theme.InkDark

private const val GITHUB_REPO_URL = "https://github.com/Conflux-Union/rms-chatroom"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLicenses: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于应用") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PaperDarkSubtle)
            )
        },
        containerColor = PaperDarkSubtle
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Version
            AboutItem(
                icon = Icons.Default.Info,
                title = "版本",
                subtitle = BuildConfig.VERSION_NAME
            )

            // Copyright
            AboutItem(
                icon = Icons.Default.Copyright,
                title = "版权信息",
                subtitle = "RMS Server 版权所有"
            )

            // GitHub repository
            AboutItem(
                icon = ImageVector.vectorResource(R.drawable.ic_github),
                title = "GitHub 仓库",
                subtitle = "Conflux-Union/rms-chatroom",
                onClick = {
                    runCatching {
                        CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                            .launchUrl(context, Uri.parse(GITHUB_REPO_URL))
                    }.onFailure {
                        Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Open source licenses
            AboutItem(
                icon = Icons.Default.Code,
                title = "开放源代码许可",
                subtitle = "查看第三方开源库许可",
                onClick = onNavigateToLicenses
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AboutItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = InkDarkFaint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = InkDark
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDarkFaint
                )
            }
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = InkDarkFaint
            )
        }
    }
}
