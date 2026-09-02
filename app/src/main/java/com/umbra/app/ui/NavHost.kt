package com.umbra.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.umbra.app.R
import com.umbra.app.domain.nip21.NostrUriEntity
import com.umbra.app.domain.nip21.resolveNostrUri
import com.umbra.app.ui.broadcast.BroadcastViewModel
import com.umbra.app.ui.components.BroadcastBanner
import com.umbra.app.ui.composer.ComposerScreen
import com.umbra.app.ui.composer.ComposerViewModel
import com.umbra.app.ui.auth.LoginScreen
import com.umbra.app.ui.feed.FeedScreen
import com.umbra.app.ui.feed.ThreadScreen
import com.umbra.app.ui.blossom.BlossomServersScreen
import com.umbra.app.ui.devoptions.DeveloperOptionsScreen
import com.umbra.app.ui.devoptions.DeveloperOptionsViewModel
import com.umbra.app.ui.devoptions.dbinspector.DbInspectorScreen
import com.umbra.app.ui.devoptions.dbinspector.DbInspectorViewModel
import com.umbra.app.ui.resourceusage.AppResourceUsageScreen
import com.umbra.app.ui.resourceusage.AppResourceUsageViewModel
import com.umbra.app.ui.feedconfig.FeedConfigScreen
import com.umbra.app.ui.feedconfig.FeedFilterEditScreen
import com.umbra.app.ui.profile.ProfileScreen
import com.umbra.app.ui.profile.EditProfileScreen
import com.umbra.app.ui.relay.ActiveSubscriptionsScreen
import com.umbra.app.ui.relay.RelayConfigScreen
import com.umbra.app.ui.relay.RelayDetailsScreen
import com.umbra.app.ui.settings.SettingsScreen
import com.umbra.app.ui.settings.AppearanceScreen
import com.umbra.app.ui.settings.AppearanceViewModel
import com.umbra.app.ui.tor.TorGateScreen
import com.umbra.app.ui.tor.TorState
import com.umbra.app.ui.auth.LoginViewModel
import com.umbra.app.ui.tor.TorGateViewModel
import com.umbra.app.ui.feed.FeedViewModel
import com.umbra.app.ui.feed.ThreadViewModel
import com.umbra.app.ui.relay.RelayConfigViewModel
import com.umbra.app.ui.blossom.BlossomServersViewModel
import com.umbra.app.ui.feedconfig.FeedConfigViewModel
import com.umbra.app.ui.profile.ProfileViewModel
import com.umbra.app.ui.profile.EditProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Login         : Screen("login")
    object TorGate       : Screen("tor_gate")
    object Feed          : Screen("feed")
    object Thread        : Screen("thread/{eventId}") {
        fun forEvent(eventId: String) = "thread/${Uri.encode(eventId)}"
    }
    object Settings      : Screen("settings")
    // Wraps RelayConfig/RelayDetails/ActiveSubscriptions (see the nested navigation() graph
    // below) so the three share one RelayConfigViewModel instance instead of each getting its
    // own screen-scoped one. Never navigated to directly — entered via RelayConfig, its start
    // destination.
    object RelayGraph    : Screen("relay_graph")
    object RelayConfig   : Screen("relay_config")
    object RelayDetails  : Screen("relay_details/{relayId}") {
        fun forRelay(relayId: String) = "relay_details/$relayId"
    }
    object ActiveSubscriptions : Screen("active_subscriptions")
    // Wraps FeedConfig/FeedFilterEdit (see the nested navigation() graph below) so both share one
    // FeedConfigViewModel instance instead of each getting its own screen-scoped one — same
    // reasoning as RelayGraph above. Never navigated to directly — entered via FeedConfig, its
    // start destination.
    object FeedConfigGraph : Screen("feed_config_graph")
    object FeedConfig    : Screen("feed_config")
    object FeedFilterEdit : Screen("feed_filter_edit")
    object BlossomServers : Screen("blossom_servers")
    object DeveloperOptions : Screen("developer_options")
    object AppResourceUsage : Screen("app_resource_usage")
    object DbInspector : Screen("db_inspector")
    object Appearance : Screen("appearance")
    object Profile       : Screen("profile/{pubkey}") {
        fun forPubkey(pubkey: String) = "profile/$pubkey"
    }
    object EditProfile   : Screen("edit_profile")
    object Composer      : Screen("composer?replyTo={replyTo}&quote={quote}") {
        fun new() = "composer"
        fun reply(eventId: String) = "composer?replyTo=${Uri.encode(eventId)}"
        fun quote(eventId: String) = "composer?quote=${Uri.encode(eventId)}"
    }
}

// Was 560/500 — well past Material's ~300ms guidance for a screen-to-screen slide, and long
// enough to read as sluggish on its own (independent of any actual jank during the transition).
private const val FORWARD_NAV_TRANSITION_DURATION_MS = 300
private const val BACK_NAV_TRANSITION_DURATION_MS = 300

private fun shouldSkipNavAnimation(fromRoute: String?, toRoute: String?): Boolean {
    if (fromRoute == null || toRoute == null) return false

    val enteringFeedFromBootstrap =
        (fromRoute == Screen.TorGate.route || fromRoute == Screen.Login.route) &&
            toRoute == Screen.Feed.route

    val leavingFeedToBootstrap =
        fromRoute == Screen.Feed.route &&
            (toRoute == Screen.TorGate.route || toRoute == Screen.Login.route)

    return enteringFeedFromBootstrap || leavingFeedToBootstrap
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardEnterTransition(): EnterTransition {
    if (shouldSkipNavAnimation(initialState.destination.route, targetState.destination.route)) {
        return EnterTransition.None
    }

    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = FORWARD_NAV_TRANSITION_DURATION_MS)
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExitTransition(): ExitTransition {
    if (shouldSkipNavAnimation(initialState.destination.route, targetState.destination.route)) {
        return ExitTransition.None
    }

    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(durationMillis = FORWARD_NAV_TRANSITION_DURATION_MS)
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backEnterTransition(): EnterTransition {
    if (shouldSkipNavAnimation(initialState.destination.route, targetState.destination.route)) {
        return EnterTransition.None
    }

    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(durationMillis = BACK_NAV_TRANSITION_DURATION_MS)
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExitTransition(): ExitTransition {
    if (shouldSkipNavAnimation(initialState.destination.route, targetState.destination.route)) {
        return ExitTransition.None
    }

    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(durationMillis = BACK_NAV_TRANSITION_DURATION_MS)
    )
}

@Composable
fun UmbraNavHost(deepLinkUri: String? = null) {
    val viewModel: AppLaunchViewModel = hiltViewModel()
    val startDestination by viewModel.startDestination.collectAsState()
    // Scoped to this composable (not a nav destination) so it's created once and survives
    // navigation between whichever screen triggered a publish and wherever the user goes next —
    // see BroadcastViewModel's doc comment.
    val broadcastViewModel: BroadcastViewModel = hiltViewModel()
    val activeBroadcasts by broadcastViewModel.activeBroadcasts.collectAsState()
    val torGateViewModel: TorGateViewModel = hiltViewModel()
    val torState by torGateViewModel.state.collectAsState()
    // Same "created once, survives navigation" scoping as broadcastViewModel/torGateViewModel
    // above — hosts the Amber launchers for search/index relay-list decryption so that keeps
    // working regardless of which screen/tab is currently showing, not just while Relay Settings
    // happens to be open. See AppSessionEffects' own doc comment.
    AppSessionEffects()
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    var backPressedOnce by remember { mutableStateOf(false) }
    var appInForeground by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentRoute = backStackEntry?.destination?.route

    val atRoot = backStackEntry?.destination
        ?.hierarchy
        ?.any {
            it.route == Screen.Feed.route ||
                it.route == Screen.Login.route ||
                it.route == Screen.TorGate.route
        }
        ?: false

    LaunchedEffect(currentRoute, atRoot) {
        if (!atRoot) {
            backPressedOnce = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Once the user has reached the feed at least once this process, a later Tor/Orbot blip
    // (a missed liveness probe, Orbot rebuilding circuits, a momentary network drop) must not
    // yank them back through the TorGate splash and blow away their nav stack — TorRuntimeManager
    // already keeps retrying Orbot on its own (see requestOrbotStart's maintenance loop /
    // LIVENESS_CHECK_INTERVAL_MS probe), and FeedViewModel.observeTorRuntimeState already reflects
    // live Tor state in the feed's status dot independent of navigation. This flag scopes the
    // force-navigate-to-TorGate behavior below to the initial bootstrap only.
    var hasReachedFeedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (currentRoute == Screen.Feed.route) hasReachedFeedOnce = true
    }

    LaunchedEffect(appInForeground, torState, currentRoute, startDestination, hasReachedFeedOnce) {
        if (!appInForeground) return@LaunchedEffect
        if (startDestination != Screen.TorGate.route) return@LaunchedEffect
        if (currentRoute == Screen.Login.route) return@LaunchedEffect
        if (hasReachedFeedOnce) return@LaunchedEffect

        val torReady = torState is TorState.Connected
        if (torReady) return@LaunchedEffect

        torGateViewModel.retry()

        if (currentRoute != Screen.TorGate.route) {
            navController.navigate(Screen.TorGate.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // NIP-21: consume a pending nostr: deep link (see MainActivity) once bootstrap has landed
    // on the feed — navigating any earlier would race the Tor/login flow's own navigation.
    // Guarded so it only fires once even though currentRoute keeps changing after that.
    var deepLinkConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(deepLinkUri, currentRoute) {
        if (deepLinkConsumed || deepLinkUri == null) return@LaunchedEffect
        if (currentRoute != Screen.Feed.route) return@LaunchedEffect

        when (val entity = resolveNostrUri(deepLinkUri)) {
            is NostrUriEntity.Profile -> navController.navigate(Screen.Profile.forPubkey(entity.pubkey))
            // deepLinkUri itself, not entity.eventId: a nevent1 deep link's relay hints (NIP-19
            // TLV type 1) are exactly what makes an externally-shared link resolvable at all when
            // the event isn't on any relay we're already connected to — Screen.Thread.forEvent's
            // argument is a generic reference ThreadViewModel.resolveAnchorFromReference parses
            // the same way regardless of form, so passing the bare id here would silently drop
            // them right before the one lookup that could use them.
            is NostrUriEntity.Note -> navController.navigate(Screen.Thread.forEvent(deepLinkUri))
            // No addressable-content (article/etc) reading screen yet to route naddr to.
            is NostrUriEntity.Address, null -> Unit
        }
        deepLinkConsumed = true
    }

    BackHandler {
        if (!atRoot) {
            val popped = navController.popBackStack()
            if (popped) return@BackHandler

            val fallbackRoute = when (startDestination) {
                Screen.Login.route,
                Screen.TorGate.route,
                Screen.Feed.route -> startDestination
                else -> Screen.Feed.route
            }

            navController.navigate(fallbackRoute) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            return@BackHandler
        }

        if (backPressedOnce) {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        } else {
            backPressedOnce = true
            Toast.makeText(context, context.getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { forwardEnterTransition() },
        exitTransition = { forwardExitTransition() },
        popEnterTransition = { backEnterTransition() },
        popExitTransition = { backExitTransition() }
    ) {
        composable(Screen.Login.route) {
            val loginViewModel: LoginViewModel = hiltViewModel()
            LoginScreen(navController = navController, viewModel = loginViewModel)
        }
        composable(Screen.TorGate.route) {
            val torGateViewModel: TorGateViewModel = hiltViewModel()
            TorGateScreen(
                onTorReady = {
                    navController.navigate(Screen.Feed.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = torGateViewModel
            )
        }
        composable(Screen.Feed.route) {
            val feedViewModel: FeedViewModel = hiltViewModel()
            val loginViewModel: LoginViewModel = hiltViewModel()
            FeedScreen(navController = navController, viewModel = feedViewModel, loginViewModel = loginViewModel)
        }
        composable(Screen.Thread.route) {
            val threadViewModel: ThreadViewModel = hiltViewModel()
            ThreadScreen(onBack = { navController.popBackStack() }, navController = navController, viewModel = threadViewModel)
        }
        composable(Screen.Settings.route) {
            val loginViewModel: LoginViewModel = hiltViewModel()
            SettingsScreen(navController = navController, loginViewModel = loginViewModel)
        }
        // RelayConfig/RelayDetails/ActiveSubscriptions nested under one graph so they share a
        // single RelayConfigViewModel (scoped to this graph's own back-stack entry, via
        // hiltViewModel(relayGraphEntry) below) instead of each navigate() minting a fresh
        // screen-scoped instance. A fresh instance meant every hop between these three screens
        // re-ran RelayConfigViewModel's init{} from scratch — re-collecting relays/relayRequests/
        // relayIssues/connectedRelayUrls and delivering each one's full current snapshot
        // instantly (see observeRelayRequests()'s "deliver first snapshot immediately" behavior),
        // which in turn forced RelayConfigScreen's remember{} blocks (relayBuckets,
        // relayConnectionStates, telemetrySnapshot, groupByPurpose) to redo their full O(relays ×
        // issues) computation synchronously during that screen's very first composition. That's
        // what made opening any of these three screens feel laggy even after relayRequests/
        // relayIssues emission-throttling fixes landed — those reduced update *frequency* once a
        // screen was already open, not the fixed cost paid on every single navigation into one.
        // Sharing the instance means that cost is paid once per visit to the relay section
        // (entering from Settings), not once per hop between its three screens.
        navigation(startDestination = Screen.RelayConfig.route, route = Screen.RelayGraph.route) {
            composable(Screen.RelayConfig.route) { backStackEntry ->
                val relayGraphEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.RelayGraph.route) }
                val relayConfigViewModel: RelayConfigViewModel = hiltViewModel(relayGraphEntry)
                RelayConfigScreen(navController = navController, viewModel = relayConfigViewModel)
            }
            composable(Screen.RelayDetails.route) { backStackEntry ->
                val relayId = backStackEntry.arguments?.getString("relayId")
                if (relayId != null) {
                    val relayGraphEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.RelayGraph.route) }
                    val relayConfigViewModel: RelayConfigViewModel = hiltViewModel(relayGraphEntry)
                    RelayDetailsScreen(
                        navController = navController,
                        relayId = relayId,
                        viewModel = relayConfigViewModel
                    )
                }
            }
            composable(Screen.ActiveSubscriptions.route) { backStackEntry ->
                val relayGraphEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.RelayGraph.route) }
                val relayConfigViewModel: RelayConfigViewModel = hiltViewModel(relayGraphEntry)
                ActiveSubscriptionsScreen(navController = navController, viewModel = relayConfigViewModel)
            }
        }
        navigation(startDestination = Screen.FeedConfig.route, route = Screen.FeedConfigGraph.route) {
            composable(Screen.FeedConfig.route) { backStackEntry ->
                val feedConfigGraphEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.FeedConfigGraph.route) }
                val feedConfigViewModel: FeedConfigViewModel = hiltViewModel(feedConfigGraphEntry)
                FeedConfigScreen(navController = navController, viewModel = feedConfigViewModel)
            }
            composable(Screen.FeedFilterEdit.route) { backStackEntry ->
                val feedConfigGraphEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.FeedConfigGraph.route) }
                val feedConfigViewModel: FeedConfigViewModel = hiltViewModel(feedConfigGraphEntry)
                FeedFilterEditScreen(navController = navController, viewModel = feedConfigViewModel)
            }
        }
        composable(Screen.BlossomServers.route) {
            val blossomServersViewModel: BlossomServersViewModel = hiltViewModel()
            BlossomServersScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = blossomServersViewModel
            )
        }
        composable(Screen.DeveloperOptions.route) {
            val developerOptionsViewModel: DeveloperOptionsViewModel = hiltViewModel()
            DeveloperOptionsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = developerOptionsViewModel
            )
        }
        composable(Screen.Appearance.route) {
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            AppearanceScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = appearanceViewModel
            )
        }
        composable(Screen.AppResourceUsage.route) {
            val appResourceUsageViewModel: AppResourceUsageViewModel = hiltViewModel()
            AppResourceUsageScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = appResourceUsageViewModel
            )
        }
        composable(Screen.DbInspector.route) {
            val dbInspectorViewModel: DbInspectorViewModel = hiltViewModel()
            DbInspectorScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = dbInspectorViewModel
            )
        }
        composable(Screen.Profile.route) {
            val profileViewModel: ProfileViewModel = hiltViewModel()
            ProfileScreen(navController = navController, viewModel = profileViewModel)
        }
        composable(Screen.EditProfile.route) {
            val editProfileViewModel: EditProfileViewModel = hiltViewModel()
            EditProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = editProfileViewModel
            )
        }
        composable(
            Screen.Composer.route,
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true },
                navArgument("quote") { type = NavType.StringType; nullable = true }
            )
        ) {
            val composerViewModel: ComposerViewModel = hiltViewModel()
            ComposerScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = composerViewModel
            )
        }
    }
    BroadcastBanner(
        broadcasts = activeBroadcasts,
        onRetryFailed = broadcastViewModel::retryFailedRelays,
        onDismiss = broadcastViewModel::dismiss,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    )
    }
}
