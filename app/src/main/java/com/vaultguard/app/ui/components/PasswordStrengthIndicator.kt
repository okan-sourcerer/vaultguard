package com.vaultguard.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vaultguard.app.util.PasswordStrength
import com.vaultguard.app.util.StrengthLevel

@Composable
fun PasswordStrengthIndicator(
    strength: PasswordStrength,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(
        targetValue = strength.score / 100f,
        label = "strength_progress"
    )
    val color by animateColorAsState(
        targetValue = when (strength.level) {
            StrengthLevel.WEAK -> Color(0xFFE53935)
            StrengthLevel.FAIR -> Color(0xFFFB8C00)
            StrengthLevel.STRONG -> Color(0xFF43A047)
            StrengthLevel.VERY_STRONG -> Color(0xFF1B5E20)
        },
        label = "strength_color"
    )
    val label = when (strength.level) {
        StrengthLevel.WEAK -> "Weak"
        StrengthLevel.FAIR -> "Fair"
        StrengthLevel.STRONG -> "Strong"
        StrengthLevel.VERY_STRONG -> "Very Strong"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
