package io.github.tonyp7.ps3dec.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.tonyp7.ps3dec.catalog.ImageItem
import io.github.tonyp7.ps3dec.catalog.KeyItem
import io.github.tonyp7.ps3dec.catalog.Listed
import io.github.tonyp7.ps3dec.engine.ImageInfo
import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.KeyInfo
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.job.JobSnapshot
import io.github.tonyp7.ps3dec.job.JobState
import io.github.tonyp7.ps3dec.runtime.Interruption
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.FolderState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** The `android-ui` spec's scenarios, driven against the stateless screens. */
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    // -- fixtures ------------------------------------------------------------------------------

    private val gib = 1L shl 30
    private val key: KeyItem = KeyItem(Entry("k1", "Game.dkey", 33), KeyInfo("Game.dkey", true))
    private val badKey: KeyItem = KeyItem(Entry("k2", "broken.dkey", 4), KeyInfo("broken.dkey", false, "expected 32 hex characters, found 4"))

    private fun image(name: String, kind: ImageKind, size: Long = 4 * gib, reason: String? = null, suggested: KeyItem? = null): ImageItem =
        ImageItem(Entry("id-$name", name, size), if (kind == ImageKind.INVALID) ImageInfo.invalid(size, reason!!) else ImageInfo(kind, size), suggested?.entry)

    private val standard: ImageItem = image("Game.iso", ImageKind.STANDARD, suggested = key)
    private val encrypted: ImageItem = image("Kenc.iso", ImageKind.THREE_K3Y_ENCRYPTED)
    private val decrypted: ImageItem = image("Kdec.iso", ImageKind.THREE_K3Y_DECRYPTED)
    private val invalid: ImageItem = image("junk.iso", ImageKind.INVALID, size = 5000, reason = "region table is empty (not a PS3 disc image?)")

    private fun folders(vararg states: FolderState): List<FolderUi> = FolderRole.entries.mapIndexed { i, role ->
        FolderUi(role, if (states[i] == FolderState.NotChosen) null else "Internal storage / PS3 / ${role.key}", states[i])
    }

    private val ready: List<FolderUi> = folders(FolderState.Ready, FolderState.Ready, FolderState.Ready)

    private fun state(
        images: List<ImageItem> = listOf(standard, encrypted, decrypted, invalid),
        keyFiles: List<Entry> = listOf(key.entry, badKey.entry),
        selected: ImageItem? = null,
        mode: Mode = Mode.DECRYPT,
        keyId: String? = null,
        checked: KeyItem? = null,
        pending: Boolean = false,
        picker: Boolean = false,
        confirm: Boolean = false,
        error: String? = null,
    ): UiState = UiState(
        loaded = true,
        folders = ready,
        images = Listed(true, images),
        keyFiles = Listed(true, keyFiles),
        selectedImageId = selected?.id,
        mode = mode,
        keyId = keyId,
        checkedKey = checked,
        keyPending = pending,
        showKeyPicker = picker,
        confirmOverwrite = confirm,
        startError = error,
    )

    private fun running(fraction: Double = 0.5, eta: Double? = 90.0): JobSnapshot = JobSnapshot(
        id = "j1", state = JobState.RUNNING, image = "Game.iso", mode = Mode.DECRYPT, output = "Game.iso",
        bytesDone = (4 * gib * fraction).toLong(), bytesTotal = 4 * gib, elapsedSeconds = 61.0,
        bytesPerSecond = 300.0 * (1 shl 20), etaSeconds = eta, log = listOf("   Copying sectors 0 to 3", "Decrypting sectors 4 to 9"),
        error = null, cancelRequested = false,
    )

    private fun show(state: UiState, job: JobSnapshot? = null, interruption: Interruption? = null, actions: MainActions = MainActions()) {
        rule.setContent { Ps3DecTheme { MainScreen(state, job, interruption, actions) } }
    }

    // -- setup ---------------------------------------------------------------------------------

    @Test
    fun setup_lists_the_three_folders_and_blocks_continue_until_all_are_chosen() {
        val chosen = mutableListOf<FolderRole>()
        val s = UiState(loaded = true, folders = folders(FolderState.Ready, FolderState.NotChosen, FolderState.NotChosen))
        rule.setContent { Ps3DecTheme { SetupScreen(s, { chosen += it }, {}, notificationsNeeded = false, onAllowNotifications = {}) } }

        rule.onNodeWithTag("setup-images").assertExists()
        rule.onNodeWithTag("setup-keys").assertExists()
        rule.onNodeWithTag("setup-output").assertExists()
        rule.onNodeWithTag("setup-finish").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("setup-hint").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun setup_explains_that_a_subfolder_is_needed_and_what_each_folder_is_for() {
        val s = UiState(loaded = true)
        rule.setContent { Ps3DecTheme { SetupScreen(s, {}, {}, false, {}) } }
        rule.onNodeWithText("subfolder", substring = true).assertExists()
        rule.onNodeWithText("must be a different folder", substring = true).assertExists()
    }

    @Test
    fun setup_choosing_a_folder_reports_which_role() {
        val chosen = mutableListOf<FolderRole>()
        val s = UiState(loaded = true)
        rule.setContent { Ps3DecTheme { SetupScreen(s, { chosen += it }, {}, false, {}) } }
        rule.onAllNodesWithText("Choose folder")[0].performScrollTo().performClick() // first card: images
        assertEquals(listOf(FolderRole.IMAGES), chosen)
    }

    @Test
    fun setup_shows_why_a_choice_was_rejected() {
        val s = UiState(loaded = true, folderError = "The output folder must be separate from the image folder.")
        rule.setContent { Ps3DecTheme { SetupScreen(s, {}, {}, false, {}) } }
        rule.onNodeWithTag("setup-error").performScrollTo().assertTextContains("separate", substring = true)
    }

    @Test
    fun setup_continues_once_all_three_are_chosen() {
        var finished = false
        val s = UiState(loaded = true, folders = ready)
        rule.setContent { Ps3DecTheme { SetupScreen(s, {}, { finished = true }, false, {}) } }
        rule.onNodeWithTag("setup-finish").performScrollTo().assertIsEnabled().performClick()
        assertEquals(true, finished)
    }

    @Test
    fun setup_offers_notifications_only_when_needed_and_does_not_require_them() {
        var asked = false
        val s = UiState(loaded = true, folders = ready)
        rule.setContent { Ps3DecTheme { SetupScreen(s, {}, {}, notificationsNeeded = true, onAllowNotifications = { asked = true }) } }
        rule.onNodeWithTag("setup-notifications").assertExists()
        rule.onNodeWithTag("setup-finish").performScrollTo().assertIsEnabled() // optional
        rule.onNodeWithText("Allow notifications").performScrollTo().performClick()
        assertEquals(true, asked)
    }

    // -- image list ----------------------------------------------------------------------------

    @Test
    fun list_shows_name_size_classification_and_key_information() {
        show(state())
        rule.onNodeWithTag("image-Game.iso").assertTextContains("Game.iso", substring = true)
        rule.onNodeWithTag("image-Game.iso").assertTextContains("4.0 GiB", substring = true)
        rule.onNodeWithTag("image-Game.iso").assertTextContains("Key suggested: Game.dkey", substring = true)
        rule.onNodeWithTag("image-Kenc.iso").assertTextContains("3k3y, encrypted", substring = true)
        rule.onNodeWithTag("image-Kdec.iso").assertTextContains("3k3y, decrypted", substring = true)
    }

    @Test
    fun list_shows_invalid_images_with_their_reason_and_does_not_let_them_be_selected() {
        var selected: ImageItem? = null
        show(state(), actions = MainActions(onSelect = { selected = it }))
        rule.onNodeWithTag("image-junk.iso").assertTextContains("region table is empty", substring = true)
        rule.onNodeWithTag("image-junk.iso").assertIsNotEnabled()
        rule.onNodeWithTag("image-Game.iso").performClick()
        assertEquals("Game.iso", selected?.name)
    }

    @Test
    fun list_names_the_folder_when_it_is_empty() {
        show(state(images = emptyList()))
        rule.onNodeWithTag("images-empty").assertIsDisplayed()
        rule.onNodeWithText("No disc images found").assertIsDisplayed()
        rule.onNodeWithText("PS3 / images", substring = true).assertIsDisplayed()
    }

    @Test
    fun list_explains_when_the_folder_is_unavailable_and_offers_to_choose_it_again() {
        var chosen: FolderRole? = null
        val s = state().copy(images = Listed(false, emptyList(), "the folder no longer exists"))
        show(s, actions = MainActions(onChoose = { chosen = it }))
        rule.onNodeWithTag("images-unavailable").assertIsDisplayed()
        rule.onNodeWithText("the folder no longer exists", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Choose the disc image folder again").performClick()
        assertEquals(FolderRole.IMAGES, chosen)
    }

    @Test
    fun list_refreshes_on_demand() {
        var refreshed = 0
        show(state(), actions = MainActions(onRefresh = { refreshed++ }))
        rule.onNodeWithTag("refresh").performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun list_shows_a_loading_state() {
        show(UiState(loaded = true, folders = ready, loading = true))
        rule.onNodeWithTag("images-loading").assertIsDisplayed()
    }

    // -- selection and Start -------------------------------------------------------------------

    @Test
    fun start_is_blocked_with_a_visible_reason_until_a_key_is_chosen_for_a_standard_image() {
        show(state(selected = standard))
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("start-reason").assertTextContains("Choose a key", substring = true)
    }

    @Test
    fun start_is_allowed_with_the_suggested_key_checked_and_shown() {
        show(state(selected = standard, keyId = "k1", checked = key))
        rule.onNodeWithTag("start").assertIsEnabled()
        rule.onNodeWithTag("start-reason").assertDoesNotExist()
        rule.onNodeWithTag("key-current").assertTextEquals("Game.dkey")
        rule.onNodeWithTag("key-status").assertTextContains("Suggested", substring = true)
    }

    @Test
    fun a_key_is_shown_as_checking_and_blocks_start_until_it_has_been_read() {
        show(state(selected = standard, keyId = "k1", pending = true))
        rule.onNodeWithTag("key-status").assertTextContains("Checking", substring = true)
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("start-reason").assertTextContains("Checking the key", substring = true)
    }

    @Test
    fun an_invalid_key_shows_its_reason_and_blocks_start() {
        show(state(selected = standard, keyId = "k2", checked = badKey))
        rule.onNodeWithTag("key-status").assertTextContains("expected 32 hex characters", substring = true)
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("start-reason").assertTextContains("not valid", substring = true)
    }

    @Test
    fun an_image_without_a_matching_key_file_asks_for_one_to_be_chosen() {
        val unmatched = image("Other.iso", ImageKind.STANDARD)
        show(state(images = listOf(unmatched), selected = unmatched))
        rule.onNodeWithTag("key-none").assertTextContains("No key file has the same name", substring = true)
        rule.onNodeWithText("Choose a key...").assertIsDisplayed()
    }

    @Test
    fun choosing_another_key_opens_the_picker() {
        var opened = 0
        show(
            state(selected = standard, keyId = "k1", checked = key),
            actions = MainActions(onOpenKeyPicker = { opened++ }),
        )
        rule.onNodeWithTag("choose-key").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun the_key_picker_lists_keys_filters_them_and_reports_the_choice() {
        var picked: String? = null
        show(
            state(selected = standard, keyId = "k1", checked = key, picker = true),
            actions = MainActions(onKey = { picked = it }),
        )
        rule.onNodeWithTag("key-picker").assertIsDisplayed()
        rule.onNodeWithTag("key-count").assertTextContains("2 shown")
        rule.onNodeWithTag("key-filter").performTextInput("broken")
        rule.onNodeWithTag("key-count").assertTextContains("1 shown")
        rule.onNodeWithTag("key-option-broken.dkey").performClick()
        assertEquals("k2", picked)
    }

    @Test
    fun the_key_picker_copes_with_a_full_redump_set() {
        val many = (1..4370).map { Entry("kid-$it", "Game number $it (USA).dkey", 34) }
        show(state(selected = standard, keyFiles = many, picker = true))
        rule.onNodeWithTag("key-count").assertTextContains("4370 shown")
        rule.onNodeWithTag("key-filter").performTextInput("number 4000 ")
        rule.onNodeWithTag("key-count").assertTextContains("1 shown")
    }

    @Test
    fun key_selection_is_disabled_with_an_explanation_for_3k3y_images_and_start_needs_no_key() {
        show(state(selected = encrypted))
        rule.onNodeWithTag("key-disabled").assertTextContains("own key", substring = true)
        rule.onNodeWithTag("start").assertIsEnabled()
    }

    @Test
    fun a_mode_that_conflicts_with_a_3k3y_image_blocks_start_with_the_reason() {
        show(state(selected = decrypted, mode = Mode.DECRYPT))
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("start-reason").assertTextContains("already decrypted", substring = true)
    }

    @Test
    fun the_mode_can_be_changed() {
        var mode: Mode? = null
        show(state(selected = standard, keyId = "k1", checked = key), actions = MainActions(onMode = { mode = it }))
        rule.onNodeWithTag("mode-encrypt").performClick()
        assertEquals(Mode.ENCRYPT, mode)
    }

    @Test
    fun start_is_disabled_while_a_job_runs_with_the_reason() {
        show(state(selected = standard, keyId = "k1", checked = key), job = running())
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("start-reason").assertTextContains("already running", substring = true)
    }

    @Test
    fun a_rejected_start_is_shown_next_to_the_start_control() {
        show(state(selected = standard, keyId = "k1", checked = key, error = "not enough free space in the output folder: need 4.0 GiB, have 1.0 GiB"))
        rule.onNodeWithTag("start-error").assertTextContains("not enough free space", substring = true)
    }

    // -- overwrite confirmation ----------------------------------------------------------------

    @Test
    fun overwrite_asks_first_and_reports_the_choice() {
        var confirmed = 0
        var dismissed = 0
        show(
            state(selected = standard, keyId = "k1", checked = key, confirm = true),
            actions = MainActions(onConfirmOverwrite = { confirmed++ }, onDismissOverwrite = { dismissed++ }),
        )
        rule.onNodeWithTag("overwrite-dialog").assertIsDisplayed()
        rule.onNodeWithText("Game.iso already exists", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("overwrite-cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun overwrite_confirmation_is_a_separate_explicit_action() {
        var confirmed = 0
        show(state(selected = standard, keyId = "k1", checked = key, confirm = true), actions = MainActions(onConfirmOverwrite = { confirmed++ }))
        rule.onNodeWithTag("overwrite-confirm").performClick()
        assertEquals(1, confirmed)
    }

    // -- job display ---------------------------------------------------------------------------

    @Test
    fun a_running_job_shows_progress_throughput_eta_and_the_log() {
        show(state(), job = running())
        rule.onNodeWithTag("progress-text").assertTextContains("50%", substring = true)
        rule.onNodeWithTag("progress-text").assertTextContains("of 4.0 GiB", substring = true)
        rule.onNodeWithTag("progress-stats").assertTextContains("Elapsed 1:01", substring = true)
        rule.onNodeWithTag("progress-stats").assertTextContains("300.0 MiB/s", substring = true)
        rule.onNodeWithTag("progress-stats").assertTextContains("1:30 left", substring = true)
        rule.onNodeWithText("Decrypting sectors 4 to 9", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_eta_is_omitted_until_throughput_is_measurable() {
        show(state(), job = running(eta = null).copy(bytesPerSecond = null))
        rule.onNodeWithTag("progress-stats").assertTextContains("measuring", substring = true)
    }

    @Test
    fun cancel_is_offered_while_running_and_reports_the_request() {
        var cancelled = 0
        show(state(), job = running(), actions = MainActions(onCancel = { cancelled++ }))
        rule.onNodeWithTag("cancel").assertIsEnabled().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun a_job_being_cancelled_cannot_be_cancelled_again() {
        show(state(), job = running().copy(cancelRequested = true))
        rule.onNodeWithTag("cancel").assertIsNotEnabled()
        rule.onNodeWithText("Cancelling...").assertIsDisplayed()
    }

    @Test
    fun a_successful_job_shows_the_output_size_duration_and_where_it_is() {
        val done = running().copy(state = JobState.SUCCEEDED, bytesDone = 4 * gib, elapsedSeconds = 75.0, etaSeconds = 0.0)
        show(state(), job = done)
        rule.onNodeWithTag("outcome").assertTextContains("Finished Game.iso", substring = true)
        rule.onNodeWithText("4.0 GiB in 1:15", substring = true).assertIsDisplayed()
        rule.onNodeWithText("output folder", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("cancel").assertDoesNotExist()
    }

    @Test
    fun a_failed_job_shows_its_error() {
        show(state(), job = running().copy(state = JobState.FAILED, error = "the input ended early, at byte 1234"))
        rule.onNodeWithTag("outcome").assertTextContains("Failed", substring = true)
        rule.onNodeWithTag("outcome-detail").assertTextEquals("the input ended early, at byte 1234")
    }

    @Test
    fun a_cancelled_job_says_so() {
        show(state(), job = running().copy(state = JobState.CANCELLED))
        rule.onNodeWithTag("outcome").assertTextContains("Cancelled", substring = true)
    }

    // -- interruption --------------------------------------------------------------------------

    @Test
    fun an_interrupted_job_is_announced_and_can_be_dismissed() {
        var dismissed = 0
        show(
            state(),
            interruption = Interruption("Game.iso", "the app was closed or stopped by the system while converting"),
            actions = MainActions(onDismissInterruption = { dismissed++ }),
        )
        rule.onNodeWithText("Game.iso did not finish", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    // -- folders screen ------------------------------------------------------------------------

    @Test
    fun folders_screen_shows_each_folder_and_offers_to_choose_an_unavailable_one_again() {
        var chosen: FolderRole? = null
        val s = UiState(
            loaded = true,
            folders = folders(FolderState.Ready, FolderState.Unavailable("access to the folder was revoked; choose it again"), FolderState.Ready),
        )
        rule.setContent { Ps3DecTheme { FoldersScreen(s, jobRunning = false, onChoose = { chosen = it }, onBack = {}) } }
        rule.onNodeWithTag("folder-images").assertIsDisplayed()
        rule.onNodeWithTag("folder-keys-unavailable").assertTextContains("revoked", substring = true)
        rule.onNodeWithText("Choose again").performClick()
        assertEquals(FolderRole.KEYS, chosen)
    }

    @Test
    fun folders_cannot_be_changed_while_a_job_runs() {
        val s = UiState(loaded = true, folders = ready)
        rule.setContent { Ps3DecTheme { FoldersScreen(s, jobRunning = true, onChoose = {}, onBack = {}) } }
        rule.onNodeWithTag("folders-locked").assertIsDisplayed()
        rule.onAllNodesWithTextCount("Change", 3)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, count: Int) {
        val nodes = onAllNodes(hasText(text)).fetchSemanticsNodes()
        assertEquals(count, nodes.size)
        onAllNodes(hasText(text)).let { all -> repeat(count) { all[it].assertIsNotEnabled() } }
    }
}
