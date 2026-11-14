package com.voyagerfiles.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import com.voyagerfiles.viewmodel.OperationState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.StartTabPlan
import com.voyagerfiles.data.index.StorageCategory
import com.voyagerfiles.R
import com.voyagerfiles.util.FolderShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Browser : Screen("browser/{path}") {
        fun createRoute(path: String): String =
            "browser/${URLEncoder.encode(path, "UTF-8")}"
    }
    data object Duplicates : Screen("duplicates/{path}") {
        fun createRoute(path: String): String = "duplicates/${Uri.encode(path)}"
    }
    data object Category : Screen("category/{category}") {
        fun createRoute(category: StorageCategory): String = "category/${category.name}"
    }
    data object Search : Screen("search")
    data object Connections : Screen("connections")
    data object Trash : Screen("trash")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    viewModel: FileBrowserViewModel,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    requestedFolder: String? = null,
    folderRequestGeneration: Long = 0,
    onFolderRequestConsumed: () -> Unit = {},
    sharedFiles: List<Uri> = emptyList(),
    onShareConsumed: () -> Unit = {},
    /** The start screen is decided and shown (the start tab, or Home): the splash can go. */
    onLaunchSettled: () -> Unit = {},
) {
    // Relays Back so a cancelled gesture can't leave a screen shifted (see BackCancelRelay).
    BackCancelRelay {
        AppNavigationContent(
            viewModel, hasAllFilesAccess, onRequestAllFilesAccess, requestedFolder, folderRequestGeneration,
            onFolderRequestConsumed, sharedFiles, onShareConsumed, onLaunchSettled,
        )
    }
}

@Composable
private fun AppNavigationContent(
    viewModel: FileBrowserViewModel,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    requestedFolder: String?,
    folderRequestGeneration: Long,
    onFolderRequestConsumed: () -> Unit,
    sharedFiles: List<Uri>,
    onShareConsumed: () -> Unit,
    onLaunchSettled: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var shortcutFailed by remember { mutableStateOf(false) }
    var confirmRoot by remember { mutableStateOf(false) }
    var showShizuku by remember { mutableStateOf(false) }
    var storageSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val sessionClosureGeneration by viewModel.sessionClosureGeneration.collectAsState()
    var launchMessage by remember { mutableStateOf<LaunchMessage?>(null) }
    // The jump into the start tab happens without a transition.
    var instantNavigation by remember { mutableStateOf(false) }
    fun showBrowserFromHome() {
        if (navController.currentDestination?.route == Screen.Home.route) {
            navController.navigate(Screen.Browser.createRoute(viewModel.browseState.value.currentPath)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(sessionClosureGeneration) {
        if (
            sessionClosureGeneration > 0L &&
            viewModel.sessions.value.isEmpty() &&
            navController.currentDestination?.route in setOf(Screen.Browser.route, Screen.Duplicates.route)
        ) {
            navController.navigateHome()
        }
    }

    Column(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
            enterTransition = {
                if (instantNavigation) return@NavHost EnterTransition.None
                val direction = if (targetState.destination.route == Screen.Home.route) {
                    AnimatedContentTransitionScope.SlideDirection.End
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Start
                }
                slideIntoContainer(direction, tween(300, easing = FastOutSlowInEasing)) { it / 8 } +
                    fadeIn(tween(220))
            },
            exitTransition = {
                if (instantNavigation) return@NavHost ExitTransition.None
                val direction = if (targetState.destination.route == Screen.Home.route) {
                    AnimatedContentTransitionScope.SlideDirection.End
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Start
                }
                slideOutOfContainer(direction, tween(300, easing = FastOutSlowInEasing)) { it / 8 } +
                    fadeOut(tween(180))
            },
            // Back (and its predictive gesture, which drives these while it is held): both screens
            // side by side, slightly scaled down, the previous one coming in whole from the start
            // edge. No fading, and it finishes quickly once released.
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(BACK_POP_MS, easing = BACK_POP_EASING)) +
                    scaleIn(tween(BACK_POP_MS, easing = BACK_POP_EASING), initialScale = BACK_POP_SCALE)
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(BACK_POP_MS, easing = BACK_POP_EASING)) +
                    scaleOut(tween(BACK_POP_MS, easing = BACK_POP_EASING), targetScale = BACK_POP_SCALE)
            },
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToBrowser = { path ->
                        viewModel.openLocalFolder(path)
                        navController.navigate(Screen.Browser.createRoute(path))
                    },
                    onNavigateToSession = { sessionId, path ->
                        viewModel.activateSession(sessionId)
                        navController.navigate(Screen.Browser.createRoute(path))
                    },
                    onNavigateToConnections = {
                        navController.navigate(Screen.Connections.route)
                    },
                    onNavigateToTrash = {
                        navController.navigate(Screen.Trash.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onOpenSafTree = { uri: Uri ->
                        viewModel.openSafRoot(uri)
                        navController.navigate(Screen.Browser.createRoute(uri.toString()))
                    },
                    hasAllFilesAccess = hasAllFilesAccess,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onNavigateToCategory = { navController.navigate(Screen.Category.createRoute(it)) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    launchMessage = launchMessage,
                    onLaunchMessageDone = { launchMessage = null },
                )
            }

            composable(
                route = Screen.Browser.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        if (navController.previousBackStackEntry?.destination?.route == Screen.Search.route) navController.popBackStack()
                        else navController.navigateHome()
                    },
                    onNavigateHome = { navController.navigateHome() },
                    leaveViaSystemBack = true,
                    onOpenRoot = { confirmRoot = true },
                    onOpenShizuku = { showShizuku = true },
                    onFindDuplicates = { path -> navController.navigate(Screen.Duplicates.createRoute(path)) },
                    hasAllFilesAccess = hasAllFilesAccess,
                    onSearchSharedStorage = { query ->
                        storageSearchQuery = query
                        navController.navigate(Screen.Search.route)
                    },
                )
            }

            composable(Screen.Duplicates.route, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                val sourceSessionId by rememberSaveable { mutableStateOf(viewModel.activeSession.value?.id) }
                DuplicatesScreen(checkNotNull(entry.arguments?.getString("path")), onNavigateBack = {
                    if (viewModel.sessions.value.any { it.id == sourceSessionId }) {
                        viewModel.refresh()
                        navController.popBackStack()
                    } else {
                        navController.navigateHome()
                    }
                })
            }

            composable(
                route = Screen.Category.route,
                arguments = listOf(navArgument("category") { type = NavType.StringType }),
            ) { entry ->
                val category = StorageCategory.entries.firstOrNull { it.name == entry.arguments?.getString("category") }
                val browseState by viewModel.browseState.collectAsState()
                if (category != null) {
                    CategoryScreen(
                        category = category,
                        showHidden = browseState.showHidden,
                        hasAllFilesAccess = hasAllFilesAccess,
                        onRequestAllFilesAccess = onRequestAllFilesAccess,
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Screen.Search.route) {
                val browseState by viewModel.browseState.collectAsState()
                StorageSearchScreen(
                    showHidden = browseState.showHidden,
                    hasAllFilesAccess = hasAllFilesAccess,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFolder = { path ->
                        viewModel.openLocalFolder(path)
                        navController.navigate(Screen.Browser.createRoute(path))
                    },
                    requestedQuery = storageSearchQuery,
                    onRequestedQueryConsumed = { storageSearchQuery = null },
                )
            }

            composable(Screen.Connections.route) {
                ConnectionsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onConnected = {
                        navController.navigate(Screen.Browser.createRoute("/")) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                )
            }

            composable(Screen.Trash.route) {
                TrashScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    hasAllFilesAccess = hasAllFilesAccess,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onOpenRoot = { confirmRoot = true },
                    onOpenShizuku = { showShizuku = true },
                )
            }
        }
        if (sharedFiles.isNotEmpty()) {
            val entry by navController.currentBackStackEntryAsState()
            val state by viewModel.browseState.collectAsState()
            val operation by viewModel.operationState.collectAsState()
            BottomAppBar(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    stringResource(R.string.share_choose_destination, sharedFiles.size),
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                TextButton(onClick = onShareConsumed) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    enabled = entry?.destination?.route == Screen.Browser.route &&
                        !state.isLoading && state.error == null && operation !is OperationState.Running,
                    onClick = {
                        viewModel.uploadDocuments(sharedFiles)
                        if (viewModel.operationState.value is OperationState.Running) onShareConsumed()
                    },
                ) { Text(stringResource(R.string.share_save_here)) }
            }
        }
    }
    if (showShizuku) {
        ShizukuAccessDialog(
            onDismiss = { showShizuku = false },
            onConnected = { provider ->
                viewModel.openShizukuSession(provider)
                showShizuku = false
                if (navController.currentDestination?.route != Screen.Browser.route) {
                    navController.navigate(Screen.Browser.createRoute("/storage/emulated/0/Android"))
                }
            },
        )
    }
    if (confirmRoot) {
        AlertDialog(
            onDismissRequest = { confirmRoot = false },
            title = { Text(stringResource(R.string.root_title)) },
            text = { Text(stringResource(R.string.root_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoot = false
                    viewModel.openRootSession()
                    if (navController.currentDestination?.route != Screen.Browser.route) {
                        navController.navigate(Screen.Browser.createRoute("/"))
                    }
                }) { Text(stringResource(R.string.root_open)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoot = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    // The start tab: opened once per launch, and not when a folder shortcut or shared files opened
    // the app. A start tab that can be opened opens straight in the browser, without a transition;
    // a server tab connects there, with the browser's own progress and its error and Retry.
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launchHandled) {
            onLaunchSettled()
            return@LaunchedEffect
        }
        launchHandled = true
        try {
            if (requestedFolder != null || sharedFiles.isNotEmpty()) return@LaunchedEffect
            val plan = withTimeoutOrNull(LAUNCH_DECISION_TIMEOUT_MS) { viewModel.startTabPlan(hasAllFilesAccess) }
            when (plan) {
                null, StartTabPlan.None -> Unit
                StartTabPlan.Opened -> {
                    instantNavigation = true
                    showBrowserFromHome()
                }
                is StartTabPlan.NeedsAccess -> launchMessage = LaunchMessage.NeedsAccess(plan.label)
                is StartTabPlan.Unavailable -> launchMessage = LaunchMessage.Unavailable(plan.label)
                is StartTabPlan.Server -> {
                    viewModel.activateSession(plan.sessionId)
                    instantNavigation = true
                    showBrowserFromHome()
                }
            }
            // Let the browser be composed and drawn before the splash goes.
            repeat(2) { withFrameNanos { } }
        } finally {
            instantNavigation = false
            // The splash stays until here (MainActivity), so Home doesn't flash before the start tab.
            onLaunchSettled()
        }
    }

    LaunchedEffect(requestedFolder, folderRequestGeneration, hasAllFilesAccess) {
        if (requestedFolder != null && hasAllFilesAccess) {
            val folder = try {
                withContext(Dispatchers.IO) { FolderShortcuts.resolve(context, requestedFolder) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                shortcutFailed = true
                onFolderRequestConsumed()
                return@LaunchedEffect
            }
            withContext(Dispatchers.Main.immediate) {
                viewModel.openLocalFolder(folder.path)
                if (navController.currentDestination?.route != Screen.Browser.route) {
                    navController.navigate(Screen.Browser.createRoute(folder.path)) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                }
                shortcutFailed = false
                onFolderRequestConsumed()
            }
        }
    }
    if (shortcutFailed) {
        AlertDialog(
            onDismissRequest = { shortcutFailed = false },
            text = { Text(stringResource(R.string.shortcut_folder_unavailable)) },
            confirmButton = {
                TextButton(onClick = { shortcutFailed = false }) { Text(stringResource(R.string.action_done)) }
            },
        )
    }
}

private fun NavHostController.navigateHome() {
    navigate(Screen.Home.route) {
        popUpTo(Screen.Home.route) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

/** The Back transition: quick, and the screens shrink this much while side by side. */
private const val BACK_POP_MS = 220
private const val BACK_POP_SCALE = 0.92f

/**
 * Material's emphasized decelerate. The held gesture seeks through the transition, so this moves
 * most of the way early: a short swipe already brings the previous screen well in.
 */
private val BACK_POP_EASING = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** How long launch waits for the start tab to be decided before showing Home anyway. */
private const val LAUNCH_DECISION_TIMEOUT_MS = 1_500L
