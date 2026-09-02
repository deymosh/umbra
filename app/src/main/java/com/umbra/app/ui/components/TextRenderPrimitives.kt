package com.umbra.app.ui.components

import com.umbra.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun RenderRichText(
    text: AnnotatedString,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent>,
    modifier: Modifier = Modifier,
    style: TextStyle
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        inlineContent = inlineContent
    )
}


@Composable
internal fun JsonContentBlock(
    json: String,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Text(
                text = json,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            CopyIconButton(
                onCopy = onCopy,
                modifier = Modifier.align(Alignment.TopEnd),
                contentDescription = stringResource(R.string.copy_formatted_json)
            )
        }
    }
}
