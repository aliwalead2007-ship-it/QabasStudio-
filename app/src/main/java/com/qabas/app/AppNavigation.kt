package com.qabas.app

/**
 * AppNavigation.kt
 * مسار الإنشاء الرئيسي:
 * INPUT → UNDERSTANDING → RESOURCES → PROCESSING → REVIEW → …
 */

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    state: ProjectState,
    viewModel: ProjectViewModel,
    context: Context,
    bottomNav: @Composable () -> Unit
) {
    AnimatedContent(
        targetState = state.appState,
        transitionSpec = {
            (slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = tween(240)) +
                fadeIn(animationSpec = tween(240)))
                .togetherWith(
                    slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(240)) +
                        fadeOut(animationSpec = tween(240))
                )
        },
        label = "qabas_screen_transition"
    ) { appState ->
    when (appState) {
        AppState.DATA_LOADING -> {
            DataLoadingScreen(
                onLoadProjects = { viewModel.loadProjectsSuspend() },
                onFinished = {
                    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                    val isLoggedIn = prefs.getBoolean("is_logged_in", false)
                    val isAdmin = prefs.getBoolean("is_admin", false)
                    val hasAcceptedCovenant = prefs.getBoolean("has_accepted_covenant", false)
                    val nextState = if (!hasAcceptedCovenant) {
                        AppState.ONBOARDING
                    } else if (isLoggedIn) {
                        if (isAdmin) AppState.DEVELOPER_DASHBOARD else AppState.HOME
                    } else {
                        AppState.LOGIN
                    }
                    viewModel.updateState { copy(appState = nextState) }
                }
            )
        }
        AppState.ONBOARDING -> {
            OnboardingScreen(
                onFinish = { viewModel.updateState { copy(appState = AppState.LOGIN) } }
            )
        }
        AppState.LOGIN -> {
            LoginScreen(
                onLoginSuccess = { isAdmin ->
                    viewModel.updateState { copy(appState = if (isAdmin) AppState.DEVELOPER_DASHBOARD else AppState.HOME) }
                },
                onNavigateToRegister = { viewModel.updateState { copy(appState = AppState.REGISTER) } },
                onNavigateToForgotPassword = { viewModel.updateState { copy(appState = AppState.FORGOT_PASSWORD) } }
            )
        }
        AppState.REGISTER -> {
            RegisterScreen(
                onRegisterSuccess = {
                    viewModel.updateState { copy(appState = AppState.HOME) }
                },
                onNavigateToLogin = { viewModel.updateState { copy(appState = AppState.LOGIN) } }
            )
        }
        AppState.FORGOT_PASSWORD -> {
            ForgotPasswordScreen(
                onNavigateToLogin = { viewModel.updateState { copy(appState = AppState.LOGIN) } }
            )
        }
        AppState.PREMIUM_UPGRADE -> {
            PremiumUpgradeScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onUpgraded = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.HOME -> {
            HomeScreen(
                recentProjects = state.projects,
                onNewProject = { initialText ->
                    viewModel.createProject(initialText ?: "")
                    val analyticsService = AppServices.getAnalyticsService(context)
                    analyticsService.logProjectCreated("new_project", "none", "0")
                },
                onViewProjects = { viewModel.updateState { copy(appState = AppState.PROJECTS) } },
                onViewSettings = { viewModel.updateState { copy(appState = AppState.SETTINGS) } },
                onViewProfile = { viewModel.updateState { copy(appState = AppState.PROFILE) } },
                onViewNotifications = { viewModel.updateState { copy(appState = AppState.NOTIFICATIONS) } },
                onOpenProject = { project -> viewModel.openProject(project) },
                onViewApiDocs = { viewModel.updateState { copy(appState = AppState.API_DOCS) } },
                onTeleprompter = { viewModel.updateState { copy(appState = AppState.TELEPROMPTER) } },
                onAudioLibrary = { viewModel.updateState { copy(appState = AppState.AUDIO_LIBRARY) } },
                onAiAssistant = { viewModel.updateState { copy(appState = AppState.AI_ASSISTANT) } },
                onYouTubeStudio = { viewModel.updateState { copy(appState = AppState.YOUTUBE_STUDIO) } },
                onReels = { viewModel.updateState { copy(appState = AppState.REELS) } },
                onDeveloperDashboard = { viewModel.updateState { copy(appState = AppState.DEVELOPER_DASHBOARD) } },
                onLeaderboard = { viewModel.updateState { copy(appState = AppState.LEADERBOARD) } },
                onNavigateToTasteProfile = { viewModel.updateState { copy(appState = AppState.TASTE_PROFILE) } },
                onPremiumUpgrade = { viewModel.updateState { copy(appState = AppState.PREMIUM_UPGRADE) } },
                onRewards = { viewModel.updateState { copy(appState = AppState.REWARDS) } },
                onKnowledgeHub = { viewModel.updateState { copy(appState = AppState.KNOWLEDGE_HUB) } },
                onContentGuard = { viewModel.updateState { copy(appState = AppState.CONTENT_GUARD) } },
                onQuranHub = { viewModel.updateState { copy(appState = AppState.QURAN_HUB) } },
                onHadithStudio = { viewModel.updateState { copy(appState = AppState.HADITH_STUDIO) } },
                onVideoStyleCloner = { viewModel.updateState { copy(appState = AppState.VIDEO_STYLE_CLONER) } },
                onPhotoStudio = { viewModel.updateState { copy(appState = AppState.PHOTO_STUDIO) } },
                onStyleSelection = { viewModel.updateState { copy(appState = AppState.STYLE_SELECTION) } },
                bottomBar = bottomNav
            )
        }
        AppState.PHOTO_STUDIO -> {
            PhotoStudioScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onNavigateToAdvancedEdit = { videoPath ->
                    viewModel.updateState {
                        copy(
                            libraryScenes = listOf(Scene("فيديو استوديو الصور", "فيديو جاهز", 15, mediaUrl = videoPath)),
                            appState = AppState.ADVANCED_EDIT
                        )
                    }
                    // Persist PhotoStudio export to Supabase so it shows up
                    // in cloud history alongside the local Room copy.
                    viewModel.viewModelScope.launch {
                        SupabaseServices.Database.saveProject(
                            title = "استوديو الصور - فيديو سريع",
                            description = "تم توليد الفيديو من استوديو الصور",
                            status = "ready",
                            cost = 0.0,
                            tags = listOf("photo_studio", "generated"),
                            metadata = mapOf(
                                "sourceType" to "photo_studio",
                                "videoPath" to videoPath
                            )
                        )
                    }
                },
                onSaveProject = { videoPath ->
                    viewModel.createProject("استوديو الصور - فيديو سريع")
                    viewModel.updateState {
                        copy(
                            appState = AppState.PROJECTS,
                            finalVideoPath = videoPath
                        )
                    }
                    // Persist PhotoStudio export to Supabase cloud history.
                    viewModel.viewModelScope.launch {
                        SupabaseServices.Database.saveProject(
                            title = "استوديو الصور - فيديو سريع",
                            description = "تم توليد الفيديو من استوديو الصور",
                            status = "ready",
                            cost = 0.0,
                            tags = listOf("photo_studio", "saved"),
                            metadata = mapOf(
                                "sourceType" to "photo_studio",
                                "videoPath" to videoPath
                            )
                        )
                    }
                }
            )
        }
        AppState.DEVELOPER_DASHBOARD -> {
            DeveloperDashboardScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.QURAN_HUB -> {
            QuranTajweedScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onCreateVideoFromVerse = { verseText, surahName, verseNumber ->
                    val fullVerseFormatted = buildString {
                        append("﴿ $verseText ﴾")
                        if (surahName.isNotBlank()) {
                            append("\n\nسورة $surahName")
                            if (verseNumber != null && verseNumber > 0) {
                                append(" - الآية $verseNumber")
                            }
                        }
                    }
                    val projectTitle = if (surahName.isNotBlank()) {
                        "ريلز سورة $surahName" + if (verseNumber != null && verseNumber > 0) " (آية $verseNumber)" else ""
                    } else {
                        "ريلز قرآني"
                    }
                    viewModel.createProject(fullVerseFormatted)
                    viewModel.updateState {
                        copy(
                            projectTitle = projectTitle,
                            inputText = fullVerseFormatted,
                            appState = AppState.INPUT,
                            selectedRatio = "9:16",
                            contentType = "quran",
                            contentTone = "خاشع",
                            videoDuration = "30 ثانية"
                        )
                    }
                },
                bottomBar = bottomNav
            )
        }
        AppState.YOUTUBE_STUDIO -> {
            YouTubeStudioScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onStartSeries = {
                    viewModel.updateState { copy(appState = AppState.PROJECTS) }
                },
                onStartEpisode = { title, scriptText, format ->
                    val selectedRatio = if (format.contains("9:16")) "9:16" else if (format.contains("16:9")) "16:9" else "4:5"
                    viewModel.createProject(title)
                    viewModel.updateState {
                        copy(
                            appState = AppState.RESOURCES,
                            inputText = scriptText,
                            selectedRatio = selectedRatio,
                            contentType = if (selectedRatio == "9:16") "ريلز / شورتس" else "فيديو يوتيوب",
                            videoDuration = "60 ثانية"
                        )
                    }
                }
            )
        }
        AppState.API_DOCS -> {
            ApiDocsScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.PROJECTS -> {
            ProjectsScreen(
                projects = state.projects,
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onOpenProject = { project -> viewModel.openProject(project) },
                onDeleteProject = { id -> viewModel.deleteProject(id) },
                onViewSettings = { viewModel.updateState { copy(appState = AppState.SETTINGS) } },
                bottomBar = bottomNav
            )
        }
        AppState.HADITH_STUDIO -> {
            HadithCardStudioScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onExportAsReel = { text, ratio ->
                    viewModel.createProject(text)
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            selectedRatio = ratio,
                            contentType = "بطاقة حديث شريف",
                            videoDuration = "15 ثانية"
                        )
                    }
                },
                onViewProjects = { viewModel.updateState { copy(appState = AppState.PROJECTS) } },
                bottomBar = bottomNav
            )
        }
        AppState.ENTERPRISE_PORTAL -> {
            EnterprisePortalScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onNavigate = { newState -> viewModel.updateState { copy(appState = newState) } },
                bottomBar = bottomNav
            )
        }
        AppState.REELS -> {
            ReelsScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onStartCreating = {
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            selectedRatio = "9:16",
                            contentType = "ريلز / شورتس",
                            videoDuration = "30 ثانية"
                        )
                    }
                },
                onStartCreatingWithScript = { scriptText ->
                    viewModel.createProject(scriptText)
                    viewModel.updateState {
                        copy(
                            appState = AppState.RESOURCES,
                            selectedRatio = "9:16",
                            contentType = "ريلز / شورتس",
                            videoDuration = "30 ثانية"
                        )
                    }
                },
                onOpenTeleprompter = { scriptText ->
                    viewModel.updateState {
                        copy(
                            inputText = scriptText,
                            appState = AppState.TELEPROMPTER
                        )
                    }
                },
                onStartSmartDirector = {
                    viewModel.updateState { copy(appState = AppState.SMART_DIRECTOR) }
                },
                bottomBar = bottomNav
            )
        }
        AppState.SETTINGS -> {
            SettingsScreen(
                onLogout = {
                    try { com.google.firebase.auth.FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}
                    context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE).edit()
                        .remove("is_logged_in")
                        .remove("is_admin")
                        .remove("is_developer")
                        .remove("user_email")
                        .remove("user_name")
                        .apply()
                    viewModel.updateState { copy(appState = AppState.LOGIN) }
                },
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onNavigateToTasteProfile = { viewModel.updateState { copy(appState = AppState.TASTE_PROFILE) } },
                onNavigateToPremiumUpgrade = { viewModel.updateState { copy(appState = AppState.PREMIUM_UPGRADE) } },
                bottomBar = bottomNav
            )
        }
        AppState.TELEPROMPTER -> {
            TeleprompterScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                initialScript = state.inputText.takeIf { it.isNotBlank() },
                onNavigateToEditor = { script, audioPath ->
                    viewModel.createProject(script)
                    if (!audioPath.isNullOrBlank()) {
                        viewModel.updateState { copy(voiceOver = audioPath) }
                    }
                    viewModel.updateState {
                        copy(
                            appState = AppState.ADVANCED_EDIT,
                            selectedRatio = "9:16",
                            contentType = "تسجيل مُلقن ذكي",
                            videoDuration = "30 ثانية"
                        )
                    }
                }
            )
        }
        AppState.AUDIO_LIBRARY -> {
            AudioLibraryScreen(
                onBack = {
                    viewModel.updateState {
                        copy(appState = if (inputText.isNotBlank()) AppState.INPUT else AppState.HOME)
                    }
                },
                onAudioSelected = { audioUrl ->
                    viewModel.updateState {
                        copy(
                            voiceOver = audioUrl,
                            appState = if (inputText.isNotBlank()) AppState.INPUT else AppState.HOME
                        )
                    }
                },
                onApplyToProject = { audioPath, usageType ->
                    viewModel.updateState {
                        if (usageType == "ambient") {
                            copy(
                                musicVibe = audioPath,
                                ambientSound = audioPath,
                                appState = if (inputText.isNotBlank()) AppState.INPUT else AppState.HOME
                            )
                        } else {
                            copy(
                                voiceOver = audioPath,
                                appState = if (inputText.isNotBlank()) AppState.INPUT else AppState.HOME
                            )
                        }
                    }
                }
            )
        }
        AppState.AI_ASSISTANT -> {
            AIAssistantScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onCreateReelFromScript = { scriptText ->
                    viewModel.createProject(scriptText)
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            selectedRatio = "9:16",
                            contentType = "ريلز / شورتس",
                            videoDuration = "30 ثانية"
                        )
                    }
                }
            )
        }
        AppState.LEADERBOARD -> {
            LeaderboardScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onUseInspirationScript = { script, topic ->
                    viewModel.createProject(script)
                    viewModel.updateState {
                        copy(
                            projectTitle = topic.ifBlank { "مشروع إلهام - قبس" },
                            inputText = script,
                            selectedRatio = "9:16",
                            contentType = "ريلز / شورتس",
                            videoDuration = "30 ثانية",
                            appState = AppState.INPUT
                        )
                    }
                },
                onOpenRewards = {
                    viewModel.updateState { copy(appState = AppState.REWARDS) }
                }
            )
        }
        AppState.NOTIFICATIONS -> {
            NotificationsScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onOpenContentGuard = { viewModel.updateState { copy(appState = AppState.CONTENT_GUARD) } }
            )
        }
        AppState.PROFILE -> {
            ProfileScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onNavigate = { newState -> viewModel.updateState { copy(appState = newState) } }
            )
        }
        AppState.INPUT -> {
            InputScreen(
                state = state,
                onStateChange = { newState -> viewModel.updateState { newState } },
                onProceed = { viewModel.updateState { copy(appState = AppState.UNDERSTANDING) } },
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onOpenAudioLibrary = { viewModel.updateState { copy(appState = AppState.AUDIO_LIBRARY) } }
            )
        }
        AppState.UNDERSTANDING -> {
            UnderstandingScreen(
                inputText = state.inputText,
                contentType = state.contentType,
                contentTone = state.contentTone,
                selectedRatio = state.selectedRatio,
                videoDuration = state.videoDuration,
                videoStyleAnalysis = state.videoStyleAnalysis,
                styleDescription = state.styleDescription,
                onStyleDescriptionChange = { newStyle ->
                    viewModel.updateState { copy(styleDescription = newStyle) }
                },
                onEditIdea = { viewModel.updateState { copy(appState = AppState.INPUT) } },
                onProceed = { viewModel.updateState { copy(appState = AppState.RESOURCES) } }
            )
        }
        AppState.PROCESSING -> {
            val scenesForEngine = state.scenesToProcess?.takeIf { it.isNotEmpty() }
            ProcessingScreen(
                inputText = state.inputText,
                styleDescription = state.styleDescription,
                selectedTemplate = state.selectedTemplate.ifEmpty { "الافتراضي" },
                videoQuality = state.videoQuality.ifEmpty { "1080p" },
                ambientSound = state.ambientSound.ifEmpty { "بدون" },
                videoStyleAnalysis = state.videoStyleAnalysis,
                contentType = state.contentType,
                contentTone = state.contentTone,
                videoDuration = state.videoDuration,
                existingScenes = scenesForEngine,
                onProcessingComplete = { processedScenes ->
                    viewModel.updateState {
                        copy(
                            libraryScenes = processedScenes,
                            scenesToProcess = processedScenes,
                            finalVideoPath = processedScenes.firstOrNull()?.mediaUrl ?: "",
                            appState = AppState.REVIEW
                        )
                    }
                },
                onCancel = {
                    val backState = if (scenesForEngine != null) AppState.REVIEW else AppState.RESOURCES
                    viewModel.updateState { copy(appState = backState) }
                }
            )
        }
        AppState.REVIEW -> {
            ReviewScreen(
                state = state,
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onUpdateScenes = { updatedScenes ->
                    viewModel.updateState { copy(libraryScenes = updatedScenes, scenesToProcess = updatedScenes) }
                },
                onUpdateAmbientSound = { sound ->
                    viewModel.updateState { copy(ambientSound = sound) }
                },
                onAdvancedEdit = { viewModel.updateState { copy(appState = AppState.ADVANCED_EDIT) } },
                onApprove = { viewModel.updateState { copy(appState = AppState.SAVE_SHARE) } }
            )
        }
        AppState.ADVANCED_EDIT -> {
            val scenesToEdit = (state.scenesToProcess?.takeIf { it.isNotEmpty() } ?: state.libraryScenes).ifEmpty {
                listOf(
                    Scene("المقدمة", "مشهد افتتاحي مهيب مع شروق الشمس", 3),
                    Scene("المشهد الرئيسي", "عرض النص بأسلوب حركي تفاعلي", 5),
                    Scene("الخاتمة", "خاتمة هادئة مع شعار التطبيق", 4)
                )
            }
            AdvancedEditScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.REVIEW) } },
                scenes = scenesToEdit,
                onUpdateScenes = { updatedScenes ->
                    viewModel.updateState {
                        copy(
                            libraryScenes = updatedScenes,
                            scenesToProcess = updatedScenes,
                            appState = AppState.REVIEW
                        )
                    }
                }
            )
        }
        AppState.PAYMENT -> {
            PaymentScreen(
                onPaymentSuccess = { viewModel.updateState { copy(appState = AppState.SAVE_SHARE) } },
                onBack = { viewModel.updateState { copy(appState = AppState.REVIEW) } }
            )
        }
        AppState.SAVE_SHARE -> {
            if (state.libraryScenes.isEmpty() && state.finalVideoPath.isBlank()) {
                ReviewScreen(
                    state = state,
                    onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                    onUpdateScenes = { updatedScenes ->
                        viewModel.updateState { copy(libraryScenes = updatedScenes, scenesToProcess = updatedScenes) }
                    },
                    onUpdateAmbientSound = { sound ->
                        viewModel.updateState { copy(ambientSound = sound) }
                    },
                    onAdvancedEdit = { viewModel.updateState { copy(appState = AppState.ADVANCED_EDIT) } },
                    onApprove = { viewModel.updateState { copy(appState = AppState.SAVE_SHARE) } }
                )
            } else {
            SaveShareScreen(
                scriptText = state.inputText,
                videoDuration = state.videoDuration,
                selectedRatio = state.selectedRatio,
                scenes = state.libraryScenes,
                ambientSound = state.ambientSound,
                videoStyleAnalysis = state.videoStyleAnalysis,
                onBackToHome = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onBackToEditor = { viewModel.updateState { copy(appState = AppState.ADVANCED_EDIT) } },
                onStartSeries = {
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            inputText = "",
                            mediaResources = emptyList(),
                            libraryScenes = emptyList()
                        )
                    }
                }
            )
            }
        }
        AppState.SMART_DIRECTOR -> {
            AiSmartDirectorScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.REELS) } },
                onMakeMagic = { viewModel.updateState { copy(appState = AppState.ADVANCED_EDIT) } }
            )
        }
        AppState.REWARDS -> {
            RewardsScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.KNOWLEDGE_HUB -> {
            KnowledgeHubScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onStartScriptWithText = { script ->
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            inputText = script,
                            selectedRatio = "9:16",
                            contentType = "قصة وتزكية"
                        )
                    }
                },
                onOpenTeleprompterWithText = { _ ->
                    viewModel.updateState { copy(appState = AppState.TELEPROMPTER) }
                }
            )
        }
        AppState.CONTENT_GUARD -> {
            ContentGuardScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onStartScriptWithText = { script ->
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            inputText = script,
                            selectedRatio = "9:16",
                            contentType = "مدافعة ورد الشبهات"
                        )
                    }
                },
                onOpenTeleprompterWithText = { _ ->
                    viewModel.updateState { copy(appState = AppState.TELEPROMPTER) }
                }
            )
        }
        AppState.VIDEO_STYLE_CLONER -> {
            VideoStyleClonerScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onGenerateProjectWithStyle = { topic, style ->
                    viewModel.createProject(topic)
                    viewModel.updateState {
                        copy(
                            videoStyleAnalysis = style,
                            inputText = topic,
                            appState = AppState.RESOURCES
                        )
                    }
                }
            )
        }
        AppState.TASTE_PROFILE -> {
            TasteProfileScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.STYLE_SELECTION -> {
            StyleSelectionScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onApplyMasterStyleToProject = { masterStyle ->
                    val analysis = masterStyle.toVideoStyleAnalysis()
                    viewModel.createProject(masterStyle.name)
                    viewModel.updateState {
                        copy(
                            videoStyleAnalysis = analysis,
                            styleDescription = masterStyle.fusionSummary,
                            editingStyle = masterStyle.name,
                            contentTone = masterStyle.contentTone.tone,
                            appState = AppState.INPUT
                        )
                    }
                },
                onCreateCustomStyle = {
                    viewModel.updateState { copy(appState = AppState.CREATE_STYLE_OBJECT) }
                }
            )
        }
        AppState.CREATE_STYLE_OBJECT -> {
            CreateStyleObjectScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.STYLE_SELECTION) } },
                onStyleSaved = { _ ->
                    viewModel.updateState { copy(appState = AppState.STYLE_SELECTION) }
                },
                onApplyDirectlyToProject = { styleObject ->
                    val analysis = styleObject.toVideoStyleAnalysis()
                    viewModel.createProject(styleObject.name)
                    viewModel.updateState {
                        copy(
                            videoStyleAnalysis = analysis,
                            styleDescription = styleObject.analysisSummary,
                            editingStyle = styleObject.name,
                            contentTone = styleObject.contentTone.tone,
                            appState = AppState.INPUT
                        )
                    }
                }
            )
        }
        AppState.RESOURCES -> {
            ResourceReviewScreen(
                ideaText = state.inputText,
                mediaResources = state.mediaResources,
                onAddResource = { resource ->
                    viewModel.updateState { copy(mediaResources = mediaResources + resource) }
                },
                onUpdateResource = { updatedResource ->
                    viewModel.updateState {
                        copy(mediaResources = mediaResources.map { if (it.id == updatedResource.id) updatedResource else it })
                    }
                },
                onRemoveResource = { resId ->
                    viewModel.updateState { copy(mediaResources = mediaResources.filter { it.id != resId }) }
                },
                onNext = { viewModel.updateState { copy(appState = AppState.PROCESSING) } },
                onBack = { viewModel.updateState { copy(appState = AppState.UNDERSTANDING) } },
                selectedRatio = state.selectedRatio,
                onRatioChange = { ratio -> viewModel.updateState { copy(selectedRatio = ratio) } },
                videoDuration = state.videoDuration,
                onDurationChange = { dur -> viewModel.updateState { copy(videoDuration = dur) } },
                editingStyle = state.editingStyle,
                onStyleChange = { st -> viewModel.updateState { copy(editingStyle = st) } },
                voiceOver = state.voiceOver,
                onVoiceChange = { v -> viewModel.updateState { copy(voiceOver = v) } },
                ambientSound = state.ambientSound,
                onAmbientChange = { amb -> viewModel.updateState { copy(ambientSound = amb) } },
                videoQuality = state.videoQuality,
                onQualityChange = { q -> viewModel.updateState { copy(videoQuality = q) } },
                videoStyleAnalysis = state.videoStyleAnalysis
            )
        }
        AppState.APP_IDEA_FORM -> {
            AppIdeaFormScreen(
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onSubmitSuccess = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.REQUEST_CHAT -> {
            RequestChatScreen(
                requestId = state.selectedRequestId ?: "",
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } }
            )
        }
        AppState.REQUEST_DETAILS -> {
            RequestDetailsScreen(
                requestId = state.selectedRequestId ?: "",
                onBack = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onOpenChat = { viewModel.updateState { copy(appState = AppState.REQUEST_CHAT) } }
            )
        }
        AppState.SAVE_PROJECT -> {
            SaveShareScreen(
                scriptText = state.inputText,
                videoDuration = state.videoDuration,
                selectedRatio = state.selectedRatio,
                scenes = state.libraryScenes,
                ambientSound = state.ambientSound,
                videoStyleAnalysis = state.videoStyleAnalysis,
                onBackToHome = { viewModel.updateState { copy(appState = AppState.HOME) } },
                onBackToEditor = { viewModel.updateState { copy(appState = AppState.ADVANCED_EDIT) } },
                onStartSeries = {
                    viewModel.updateState {
                        copy(
                            appState = AppState.INPUT,
                            inputText = "",
                            mediaResources = emptyList(),
                            libraryScenes = emptyList()
                        )
                    }
                }
            )
        }
        else -> {
            HomeScreen(
                recentProjects = state.projects,
                onNewProject = { initialText ->
                    viewModel.createProject(initialText ?: "")
                },
                onViewProjects = { viewModel.updateState { copy(appState = AppState.PROJECTS) } },
                onViewSettings = { viewModel.updateState { copy(appState = AppState.SETTINGS) } },
                onViewProfile = { viewModel.updateState { copy(appState = AppState.PROFILE) } },
                onViewNotifications = { viewModel.updateState { copy(appState = AppState.NOTIFICATIONS) } },
                onOpenProject = { project -> viewModel.openProject(project) },
                onViewApiDocs = { viewModel.updateState { copy(appState = AppState.API_DOCS) } },
                onTeleprompter = { viewModel.updateState { copy(appState = AppState.TELEPROMPTER) } },
                onAudioLibrary = { viewModel.updateState { copy(appState = AppState.AUDIO_LIBRARY) } },
                onAiAssistant = { viewModel.updateState { copy(appState = AppState.AI_ASSISTANT) } },
                onYouTubeStudio = { viewModel.updateState { copy(appState = AppState.YOUTUBE_STUDIO) } },
                onReels = { viewModel.updateState { copy(appState = AppState.REELS) } },
                onDeveloperDashboard = { viewModel.updateState { copy(appState = AppState.DEVELOPER_DASHBOARD) } },
                onLeaderboard = { viewModel.updateState { copy(appState = AppState.LEADERBOARD) } },
                onNavigateToTasteProfile = { viewModel.updateState { copy(appState = AppState.TASTE_PROFILE) } },
                onPremiumUpgrade = { viewModel.updateState { copy(appState = AppState.PREMIUM_UPGRADE) } },
                onRewards = { viewModel.updateState { copy(appState = AppState.REWARDS) } },
                onKnowledgeHub = { viewModel.updateState { copy(appState = AppState.KNOWLEDGE_HUB) } },
                onContentGuard = { viewModel.updateState { copy(appState = AppState.CONTENT_GUARD) } },
                onQuranHub = { viewModel.updateState { copy(appState = AppState.QURAN_HUB) } },
                onHadithStudio = { viewModel.updateState { copy(appState = AppState.HADITH_STUDIO) } },
                onVideoStyleCloner = { viewModel.updateState { copy(appState = AppState.VIDEO_STYLE_CLONER) } },
                onPhotoStudio = { viewModel.updateState { copy(appState = AppState.PHOTO_STUDIO) } },
                onStyleSelection = { viewModel.updateState { copy(appState = AppState.STYLE_SELECTION) } },
                bottomBar = bottomNav
            )
        }
    }
    }
}
