package io.github.xesam.android.grouplist.example.ui.compose

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import io.github.xesam.android.grouplist.compose.ComposeRowRegistry
import io.github.xesam.android.grouplist.compose.GroupListLazyColumn
import io.github.xesam.android.grouplist.example.ui.engine.EngineDemoViewModel
import io.github.xesam.android.grouplist.example.ui.model.FeedUiModels
import io.github.xesam.android.grouplist.example.ui.model.FeedViewTypes
import io.github.xesam.android.grouplist.vnext.shared.GroupKey
import io.github.xesam.android.grouplist.vnext.shared.ListSnapshot
import io.github.xesam.android.grouplist.vnext.shared.PositionKind
import io.github.xesam.android.grouplist.vnext.shared.PositionMapping

/**
 * 1:1 Compose counterpart of [io.github.xesam.android.grouplist.example.ui.engine.EngineDemoActivity]:
 * same ViewModel (EngineDemoViewModel) drives both, so data, filter behavior,
 * expand/collapse, loading resolution are identical by construction; only the
 * rendering layer differs.
 */
class ComposeDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        val viewModel = ViewModelProvider(this).get(EngineDemoViewModel::class.java)
        // State-backed snapshot: registry slots read it INSIDE item composition,
        // so expansion-derived text recomposes precisely when the snapshot changes
        // — without rebuilding the registry (which would re-allocate every slot
        // lambda and recompose all visible rows on each state update).
        val snapshotState = mutableStateOf(ListSnapshot.of(emptyList<ListSnapshot.Row<*>>(), null))
        viewModel.uiState.observe(this) { value -> snapshotState.value = value }
        setContent {
            ComposeDemoScreen(
                snapshotState = snapshotState,
                onFilterModeSelected = viewModel::onFilterModeSelected,
                onToggleGroupRequested = viewModel::onToggleGroupRequested,
            )
        }
    }
}

private val FeedBg = Color(0xFFF4F6FA)
private val TextPrimary = Color(0xFF1D2433)
private val TextSecondary = Color(0xFF2E3A52)
private val TextMuted = Color(0xFF5C6678)
private val White = Color(0xFFFFFFFF)

private val StandaloneBg = Color(0xFFF2F6FF)
private val StandaloneStroke = Color(0xFFD9E4FF)
private val FeedHeaderBg = Color(0xFFE9FFF6)
private val FeedHeaderStroke = Color(0xFFBAEFD7)
private val FeedItemStroke = Color(0xFFE4E9F2)
private val FeedFooterBg = Color(0xFFF8FAFD)
private val FeedFooterStroke = Color(0xFFE6ECF4)
private val TagBg = Color(0xFFDDEBFF)
private val TagText = Color(0xFF244A8D)
private val AvatarBg = Color(0xFF5D7DBA)

private val FilterChipDefaultBg = White
private val FilterChipDefaultStroke = Color(0xFFD9E4FF)
private val FilterChipDefaultText = Color(0xFF325275)
private val FilterChipSelectedBg = Color(0xFFDDEBFF)
private val FilterChipSelectedStroke = Color(0xFFB5CBF3)
private val FilterChipSelectedText = Color(0xFF193A77)

private val TopicHeaderBg = Color(0xFFFFF6E7)
private val TopicHeaderStroke = Color(0xFFF4D8A8)
private val TopicItemStroke = Color(0xFFF2E4C9)
private val TopicFooterBg = Color(0xFFFFFAF1)
private val TopicFooterStroke = Color(0xFFF4E6C9)
private val TopicChipBg = Color(0xFFFFE3B5)
private val TopicChipText = Color(0xFF7A4B00)
private val TopicVoteBg = Color(0xFFFFECD0)
private val TopicVoteText = Color(0xFF7A4B00)
private val SkeletonColor = Color(0xFFE6EBF3)

// View-side drawable parity: connected-card corners (header=14dp top-only,
// middle=square, footer=14dp bottom-only), standalone=12dp, pills=full round.
private val GroupHeaderShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
private val GroupMiddleShape = RoundedCornerShape(0.dp)
private val GroupFooterShape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
private val StandaloneShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(50)
private val SkeletonBlockShape = RoundedCornerShape(6.dp)
private val CardIndent = 12.dp

@Composable
fun ComposeDemoScreen(
    snapshotState: androidx.compose.runtime.MutableState<ListSnapshot>,
    onFilterModeSelected: (FeedUiModels.GroupFilterMode) -> Unit,
    onToggleGroupRequested: (GroupKey) -> Unit,
) {
    val context = LocalContext.current
    val snapshot = snapshotState.value
    val registry = remember(context) {
        ComposeRowRegistry().apply {
            registerFeedSlots(
                context = context,
                // Reads live state at item-composition time: correct invalidation,
                // stable lambda identity (no per-recomposition re-registration).
                isExpanded = { key -> snapshotState.value.hasGroupItems(key) },
                onFilterModeSelected = onFilterModeSelected,
                onToggleGroupRequested = onToggleGroupRequested,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeedBg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        GroupListLazyColumn(
            snapshot = snapshot,
            registry = registry,
            itemAnimation = true,
            // Parity with activity_demo_list.xml: RecyclerView paddingTop=8dp
            // paddingBottom=14dp + clipToPadding=false == LazyColumn contentPadding.
            contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Suppress("LongMethod")
private fun ComposeRowRegistry.registerFeedSlots(
    context: android.content.Context,
    isExpanded: (GroupKey) -> Boolean,
    onFilterModeSelected: (FeedUiModels.GroupFilterMode) -> Unit,
    onToggleGroupRequested: (GroupKey) -> Unit,
) {
    val mappingToast: (PositionMapping) -> Unit = { mapping ->
        val group = mapping.groupKey()?.value() ?: "-1"
        val child = if (mapping.kind() == PositionKind.GROUP_ITEM) mapping.childIndex() else -1
        Toast.makeText(
            context,
            "flat=" + mapping.flatPosition() + ", group=" + group + ", child=" + child,
            Toast.LENGTH_SHORT,
        ).show()
    }

    register<FeedUiModels.GroupFilterCard>(FeedViewTypes.TYPE_FILTER_STANDALONE) { row ->
        val card = row.data() as FeedUiModels.GroupFilterCard
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent, bottom = 10.dp)
                .background(StandaloneBg, StandaloneShape)
                .border(1.dp, StandaloneStroke, StandaloneShape)
                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
        ) {
            TextStyled("分组筛选", 12.sp, TextSecondary, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                FilterChip(
                    label = "显示全部分组",
                    selected = card.mode == FeedUiModels.GroupFilterMode.ALL_GROUPS,
                    onClick = { onFilterModeSelected(FeedUiModels.GroupFilterMode.ALL_GROUPS) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    label = "仅显示评论分组",
                    selected = card.mode == FeedUiModels.GroupFilterMode.COMMENT_ONLY,
                    onClick = { onFilterModeSelected(FeedUiModels.GroupFilterMode.COMMENT_ONLY) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                FilterChip(
                    label = "仅显示投票分组",
                    selected = card.mode == FeedUiModels.GroupFilterMode.TOPIC_ONLY,
                    onClick = { onFilterModeSelected(FeedUiModels.GroupFilterMode.TOPIC_ONLY) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
    }

    register<FeedUiModels.StandaloneCardUi>(FeedViewTypes.TYPE_STANDALONE) { row ->
        val card = row.data() as FeedUiModels.StandaloneCardUi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, top = 8.dp, end = CardIndent, bottom = 8.dp)
                .background(StandaloneBg, StandaloneShape)
                .border(1.dp, StandaloneStroke, StandaloneShape)
                .clickable { mappingToast(row.mapping()) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            TextPill(card.tag, TagBg, TagText)
            TextStyled(card.title, 16.sp, TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            TextStyled(card.body, 14.sp, TextSecondary, Modifier.padding(top = 6.dp))
        }
    }

    register<FeedUiModels.CommentHeader>(FeedViewTypes.TYPE_GROUP_HEADER) { row ->
        val header = row.data() as FeedUiModels.CommentHeader
        val groupKey = requireNotNull(row.mapping().groupKey())
        val expanded = isExpanded(groupKey)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, top = 10.dp, end = CardIndent)
                .background(FeedHeaderBg, GroupHeaderShape)
                .border(1.dp, FeedHeaderStroke, GroupHeaderShape)
                .clickable { onToggleGroupRequested(groupKey) }
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 10.dp),
        ) {
            TextPill(if (expanded) "收起评论" else "展开评论", TagBg, TagText)
            TextStyled(header.meta, 12.sp, TextMuted, Modifier.padding(top = 6.dp))
            TextStyled(header.title, 15.sp, TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        }
    }

    register<FeedUiModels.FeedCommentItem>(FeedViewTypes.TYPE_GROUP_ITEM) { row ->
        val item = row.data() as FeedUiModels.FeedCommentItem
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent)
                .background(White, GroupMiddleShape)
                .border(1.dp, FeedItemStroke, GroupMiddleShape)
                .clickable { mappingToast(row.mapping()) }
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(26.dp).background(AvatarBg, CircleShape),
            ) {
                TextStyled(firstChar(item.author), 12.sp, White, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                TextStyled(item.author, 12.sp, TextSecondary, fontWeight = FontWeight.Bold)
                TextStyled(item.content, 14.sp, TextPrimary, Modifier.padding(top = 3.dp))
            }
        }
    }

    register<Any>(FeedViewTypes.TYPE_FEED_GROUP_ITEM_LOADING) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent)
                .background(White, GroupMiddleShape)
                .border(1.dp, FeedItemStroke, GroupMiddleShape)
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
        ) {
            Box(modifier = Modifier.size(26.dp).background(SkeletonColor, CircleShape))
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(12.dp)
                        .background(SkeletonColor, SkeletonBlockShape),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp)
                        .padding(top = 6.dp)
                        .background(SkeletonColor, SkeletonBlockShape),
                )
            }
        }
    }

    register<String>(FeedViewTypes.TYPE_GROUP_FOOTER) { row ->
        val raw: String = row.data()
        val groupKey = requireNotNull(row.mapping().groupKey())
        val expanded = isExpanded(groupKey)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent, bottom = 10.dp)
                .background(FeedFooterBg, GroupFooterShape)
                .border(1.dp, FeedFooterStroke, GroupFooterShape)
                .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 10.dp),
        ) {
            TextStyled(raw, 12.sp, TextMuted)
            TextStyled(
                if (expanded) "收起评论" else "展开评论",
                13.sp,
                TextSecondary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { onToggleGroupRequested(groupKey) },
                fontWeight = FontWeight.Bold,
            )
        }
    }

    register<FeedUiModels.TopicHeader>(FeedViewTypes.TYPE_TOPIC_GROUP_HEADER) { row ->
        val header = row.data() as FeedUiModels.TopicHeader
        val groupKey = requireNotNull(row.mapping().groupKey())
        val expanded = isExpanded(groupKey)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, top = 10.dp, end = CardIndent)
                .background(TopicHeaderBg, GroupHeaderShape)
                .border(1.dp, TopicHeaderStroke, GroupHeaderShape)
                .clickable { onToggleGroupRequested(groupKey) }
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 10.dp),
        ) {
            TextPill(
                if (expanded) "收起投票选项" else "展开投票选项",
                TopicChipBg,
                TopicChipText,
            )
            TextStyled(header.title, 15.sp, TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            TextStyled(header.sponsor + "  ·  " + header.deadline, 12.sp, TextMuted, Modifier.padding(top = 5.dp))
        }
    }

    register<FeedUiModels.TopicOptionItem>(FeedViewTypes.TYPE_TOPIC_GROUP_ITEM) { row ->
        val item = row.data() as FeedUiModels.TopicOptionItem
        val option = requireNotNull(item.option) { "Loaded topic option item must contain option data" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent)
                .background(White, GroupMiddleShape)
                .border(1.dp, TopicItemStroke, GroupMiddleShape)
                .clickable { mappingToast(row.mapping()) }
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextStyled(option.optionText, 14.sp, TextPrimary, Modifier.weight(1f, fill = false))
            TextPill(option.voteCount.toString() + " 票", TopicVoteBg, TopicVoteText)
        }
    }

    register<Any>(FeedViewTypes.TYPE_TOPIC_GROUP_ITEM_LOADING) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent)
                .background(White, GroupMiddleShape)
                .border(1.dp, TopicItemStroke, GroupMiddleShape)
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .background(SkeletonColor, SkeletonBlockShape),
            )
            TextPill("…", SkeletonColor, TopicVoteText)
        }
    }

    register<FeedUiModels.TopicFooter>(FeedViewTypes.TYPE_TOPIC_GROUP_FOOTER) { row ->
        val footer = row.data() as FeedUiModels.TopicFooter
        val groupKey = requireNotNull(row.mapping().groupKey())
        val expanded = isExpanded(groupKey)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CardIndent, end = CardIndent, bottom = 10.dp)
                .background(TopicFooterBg, GroupFooterShape)
                .border(1.dp, TopicFooterStroke, GroupFooterShape)
                .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 10.dp),
        ) {
            TextStyled("已参与 " + footer.participantCount + " 人", 13.sp, TextSecondary, fontWeight = FontWeight.Bold)
            TextStyled(footer.lastUpdated, 12.sp, TextMuted, Modifier.padding(top = 4.dp))
            TextStyled(
                if (expanded) "收起投票选项" else "展开投票选项",
                13.sp,
                TextSecondary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { onToggleGroupRequested(groupKey) },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TextStyled(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            color = color,
            fontWeight = fontWeight,
        ),
    )
}

@Composable
private fun TextPill(text: String, bg: Color, textColor: Color) {
    TextStyled(
        text = text,
        fontSize = 11.sp,
        color = textColor,
        modifier = Modifier
            .background(bg, PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) FilterChipSelectedBg else FilterChipDefaultBg
    val stroke = if (selected) FilterChipSelectedStroke else FilterChipDefaultStroke
    val textColor = if (selected) FilterChipSelectedText else FilterChipDefaultText
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .background(background, RoundedCornerShape(17.dp))
            .border(1.dp, stroke, RoundedCornerShape(17.dp))
            .clickable { onClick() },
    ) {
        TextStyled(label, 12.sp, textColor, fontWeight = FontWeight.Bold)
    }
}

private fun firstChar(text: String?): String {
    if (text.isNullOrEmpty()) {
        return "U"
    }
    return text.substring(0, 1).uppercase()
}

/**
 * Group expansion derived from the snapshot itself: expanded == has visible items.
 * Same known caveat as shared GroupState#expanded: an expanded
 * group with zero children reports false (indistinguishable from collapsed in a
 * flat snapshot). The Compose layer intentionally keeps this single derivation
 * instead of reading the ViewModel's controller: the snapshot is the ONLY state
 * this rendering layer receives.
 */
private fun ListSnapshot.hasGroupItems(key: GroupKey): Boolean =
    rows().any { it.mapping().kind() == PositionKind.GROUP_ITEM && key == it.mapping().groupKey() }
