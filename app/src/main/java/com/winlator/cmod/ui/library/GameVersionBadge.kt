package com.winlator.cmod.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R

@Composable
internal fun GameVersionBadge(version: String, modifier: Modifier = Modifier) {
    if (version.isBlank()) return
    val description = stringResource(R.string.game_version_description, version)
    Surface(
        modifier = modifier.widthIn(max = 150.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(6.dp),
        color = Color(0xDD111827),
        border = BorderStroke(1.dp, Color.White.copy(.30f))
    ) {
        Text(
            version,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
