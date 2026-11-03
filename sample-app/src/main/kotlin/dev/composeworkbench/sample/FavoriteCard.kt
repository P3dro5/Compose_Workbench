package dev.composeworkbench.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun FavoriteCard(onFavorite: () -> Unit, onShare: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = "Share",
            modifier = Modifier
                .testTag("share")
                .clickable { onShare() }
                .size(32.dp)
        )
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "Favorite",
            modifier = Modifier
                .testTag("favorite")
                .clickable { onFavorite() }
                .size(24.dp)
        )
    }
}
