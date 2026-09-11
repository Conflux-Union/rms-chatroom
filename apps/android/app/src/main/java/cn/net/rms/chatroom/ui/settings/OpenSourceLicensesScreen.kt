package cn.net.rms.chatroom.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.ui.theme.SurfaceDark
import cn.net.rms.chatroom.ui.theme.SurfaceLighter
import cn.net.rms.chatroom.ui.theme.TextMuted
import cn.net.rms.chatroom.ui.theme.TextPrimary
import cn.net.rms.chatroom.ui.theme.TiColor

data class LicenseInfo(
    val name: String,
    val description: String,
    val license: String
)

private val openSourceLibraries = listOf(
    LicenseInfo(
        name = "Kotlin",
        description = "The Kotlin Programming Language",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Jetpack Compose",
        description = "Android's modern toolkit for building native UI",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "LiveKit Android SDK",
        description = "Real-time audio/video communication SDK",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Hilt",
        description = "Dependency injection library for Android",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Retrofit",
        description = "Type-safe HTTP client for Android",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "OkHttp",
        description = "HTTP client for Android and Java",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Gson",
        description = "A Java serialization/deserialization library",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Room",
        description = "SQLite object mapping library",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "DataStore",
        description = "Data storage solution for Android",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Coil",
        description = "Image loading library for Android",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Colorful Sliders",
        description = "Customizable slider components",
        license = "Apache License 2.0"
    ),
    LicenseInfo(
        name = "Material Icons Extended",
        description = "Extended Material Design icons",
        license = "Apache License 2.0"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开放源代码许可") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = SurfaceDark
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "本应用使用了以下开源软件:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(openSourceLibraries) { library ->
                LicenseCard(library = library)
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LicenseCard(library: LicenseInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceLighter)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = library.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = TiColor.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = library.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = TiColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
