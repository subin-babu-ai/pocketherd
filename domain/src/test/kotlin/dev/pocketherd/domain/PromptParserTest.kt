package dev.pocketherd.domain

import dev.pocketherd.core.model.PromptFlavor
import dev.pocketherd.core.model.PromptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the SNAP strings from the design prototype, with its per-line render
 * prefixes removed, so these are the shapes the parser will actually meet.
 */
class PromptParserTest {

    private val permission = """
        ⏺ Read(sim/client.py)
          ⎿  Read 288 lines

        ⏺ The cache was built against the old schema. Rebuilding.

          Bash command
            cd sim && python3 client.py --rebuild --target=all
            Rebuild solver cache after schema change

          Do you want to proceed?
          ❯ 1. Yes
            2. Yes, and don't ask again for python3 commands
               in ~/work/project
            3. No, and tell Claude what to do differently (esc)
    """.trimIndent()

    private val plan = """
        ⏺ Read(src/hmi/battery_12v/state_machine.cc)
          ⎿  Read 412 lines

          Ready to code?

          Here is Claude's plan:
          1. Add LOW_12V_WARN between NORMAL and SHUTDOWN_PENDING,
             entered below 11.8 V for 30 s
          2. Surface the warning tile in hmi/cluster

          Would you like to proceed?
          ❯ 1. Yes, and auto-accept edits
            2. Yes, and manually approve edits
            3. No, keep planning
    """.trimIndent()

    private val edit = """
        ⏺ Update(planner/route_planner.cc)

          Edit file  planner/route_planner.cc
          211    const Route* best = nullptr;
          213 -    if (candidate.cost < best->cost) {
          213 +    if (best == nullptr || candidate.cost < best->cost) {
          217 -  return *best;
          217 +  if (best == nullptr) {

          Do you want to make this edit to route_planner.cc?
          ❯ 1. Yes
            2. Yes, allow all edits during this session
            3. No, and tell Claude what to do differently (esc)
    """.trimIndent()

    private val yesNo = """
        $ repo sync -j8 acme/platform
        Fetching: 100% (214/214), done in 41.2s

        ⏺ Bash(git log --oneline origin/main..HEAD)
          ⎿  8f2c1d0 Bump manifest

        Push 3 commits to origin/main? (y/N)
    """.trimIndent()

    private val freeText = """
        ⏺ Bash(bazel test //can/replay:replay_test --runs_per_test=50)
          ⎿  FAILED in 7 out of 50 in 38.4s

        ⏺ All 7 failures have frames arriving out of timestamp order.

          Which do you prefer?

        >
    """.trimIndent()

    @Test
    fun `permission prompt yields three options and the question`() {
        val kind = PromptParser.parse(permission) as PromptKind.Menu
        assertEquals("Do you want to proceed?", kind.question)
        assertEquals(PromptFlavor.Permission, kind.flavor)
        assertEquals(listOf("1", "2", "3"), kind.options.map { it.key })
        assertEquals("Yes", kind.options[0].label)
    }

    @Test
    fun `a wrapped option is folded into one, and its path is shortened`() {
        val kind = PromptParser.parse(permission) as PromptKind.Menu
        // "in ~/work/project" was on its own line and is not worth a phone line.
        assertEquals("Yes, don't ask again for python3 commands here", kind.options[1].label)
        assertFalse(kind.options[1].label.contains("project"))
    }

    @Test
    fun `the tell-Claude option asks for prose afterwards`() {
        val kind = PromptParser.parse(permission) as PromptKind.Menu
        assertTrue(kind.options[2].thenCompose)
        assertFalse(kind.options[0].thenCompose)
        // "(esc)" is a keyboard hint on a device with no escape key.
        assertFalse(kind.options[2].label.contains("(esc)"))
    }

    @Test
    fun `a plan is a plan even though its body lists numbered steps`() {
        val kind = PromptParser.parse(plan) as PromptKind.Menu
        assertEquals(PromptFlavor.Plan, kind.flavor)
        assertEquals("Would you like to proceed?", kind.question)
        // The plan's own "1." / "2." steps sit above the real options; the question
        // boundary is what separates them.
        assertEquals(3, kind.options.size)
        assertEquals("Yes, auto-accept edits", kind.options[0].label)
    }

    @Test
    fun `a diff is an edit approval`() {
        val kind = PromptParser.parse(edit) as PromptKind.Menu
        assertEquals(PromptFlavor.Edit, kind.flavor)
        assertEquals("Do you want to make this edit to route_planner.cc?", kind.question)
    }

    @Test
    fun `yes-no reads the default from the capitalised letter`() {
        val kind = PromptParser.parse(yesNo) as PromptKind.YesNo
        assertEquals("Push 3 commits to origin/main? (y/N)", kind.question)
        assertFalse("(y/N) defaults to no", kind.defaultYes)

        val other = PromptParser.parse("Overwrite the file? (Y/n)") as PromptKind.YesNo
        assertTrue(other.defaultYes)
    }

    @Test
    fun `a bare prompt arrow is a free-text question`() {
        val kind = PromptParser.parse(freeText) as PromptKind.FreeText
        assertEquals("Which do you prefer?", kind.question)
    }

    @Test
    fun `ordinary output is not mistaken for a prompt`() {
        val working = """
            ⏺ Bash(./gradlew testDebugUnitTest)
              ⎿  BUILD SUCCESSFUL in 23s

            ✻ Working… (esc to interrupt)
        """.trimIndent()
        assertEquals(PromptKind.Unrecognised, PromptParser.parse(working))
    }

    @Test
    fun `empty input is unrecognised rather than an exception`() {
        assertEquals(PromptKind.Unrecognised, PromptParser.parse(""))
        assertEquals(PromptKind.Unrecognised, PromptParser.parse("\n\n   \n"))
    }

    @Test
    fun `labels carry the display text but keys carry the answer`() {
        val kind = PromptParser.parse(permission) as PromptKind.Menu
        // Shortening the label must never change what gets sent.
        kind.options.forEach { assertTrue(it.key.length == 1 && it.key[0].isDigit()) }
    }

    @Test
    fun `prompt labels match the copy deck`() {
        assertEquals("Permission", (PromptParser.parse(permission) as PromptKind.Menu).label)
        assertEquals("Plan approval", (PromptParser.parse(plan) as PromptKind.Menu).label)
        assertEquals("Edit approval", (PromptParser.parse(edit) as PromptKind.Menu).label)
        assertEquals("Yes / no", PromptParser.parse(yesNo).label)
        assertEquals("Question", PromptParser.parse(freeText).label)
    }
}
