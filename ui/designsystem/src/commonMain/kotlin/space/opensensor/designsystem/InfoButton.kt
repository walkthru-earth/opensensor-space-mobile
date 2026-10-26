package space.opensensor.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.info_close
import space.opensensor.designsystem.resources.info_more

/**
 * One explanation behind a small "i". The default view of a card shows a title, its value and one
 * action. Anything longer than one short line lives here and opens on demand.
 */
@Immutable
data class InfoContent(val title: String, val body: List<String>) {
    constructor(title: String, vararg body: String) : this(title, body.toList())
}

/**
 * An outlined info icon (48 dp touch target) that opens [InfoSheet]. Its content description is
 * "More about <title>", so TalkBack and VoiceOver name the topic before the sheet opens.
 */
@Composable
fun InfoButton(
    info: InfoContent,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.touchTarget()) {
        Icon(AppIcons.Outlined.Info, contentDescription = stringResource(Res.string.info_more, info.title), tint = tint)
    }
    if (open) InfoSheet(info, onDismiss = { open = false })
}

/** The bottom sheet behind [InfoButton]: a heading, short paragraphs, and a visible Close button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: InfoContent, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        InfoSheetContent(info = info, closeLabel = stringResource(Res.string.info_close), onDismiss = onDismiss)
    }
}

@Composable
internal fun InfoSheetContent(info: InfoContent, closeLabel: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        BodyText(info.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.headingSemantics(), heading = true)
        info.body.forEach { paragraph ->
            Spacer(Modifier.height(10.dp))
            BodyText(paragraph, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        OutlinePillButton(
            text = closeLabel,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The header row of a card: the eyebrow title, an optional [InfoButton] right after it, and an
 * optional trailing control (a switch) at the end.
 */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    info: InfoContent? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The title and its "i" stay together on the left. The trailing control sits at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(title, color = color, heading = true, modifier = Modifier.weight(1f, fill = false))
            if (info != null) InfoButton(info, tint = color)
        }
        trailing?.invoke(this)
    }
}
