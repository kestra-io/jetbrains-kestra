package io.kestra.jetbrains.run

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

// The Run tool window content for a flow run: header with the execution state and actions, the run phase and
// errors, then one collapsible section per task run holding its state, duration and logs. Mirrors the VS Code
// run panel. Stop and rerun stay on the Run tool window toolbar.
class KestraRunView(private val model: RunModel, defaultLevel: String) : ExecutionConsole {
    private val badge = StateBadge()
    private val copy = JButton("Copy logs", AllIcons.Actions.Copy)
    private val open = JButton("Open in Kestra", AllIcons.Ide.External_link_arrow)
    private val phase = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val level = ComboBox(LOG_LEVELS.reversed().map(::titleCase).toTypedArray())
    private val errors = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.RED
        isVisible = false
    }
    private val truncation = JBLabel("Older log lines were dropped to keep the view responsive.").apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }
    private val tasks = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val scroll = JBScrollPane(JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(0, 8, 8, 8)
        add(tasks, BorderLayout.NORTH)
    })
    private val root = JPanel(BorderLayout())
    private val sections = LinkedHashMap<String, TaskSection>()
    private val scheduled = AtomicBoolean()
    private var renderedVersion = -1L
    private var executionUrl: String? = null
    @Volatile private var disposed = false

    init {
        level.selectedItem = titleCase(defaultLevel)
        level.addActionListener { sections.values.forEach { it.renderLogs(force = true) } }
        copy.isVisible = false
        open.isVisible = false
        copy.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(model.copyText())) }
        open.addActionListener { executionUrl?.let(BrowserUtil::browse) }

        val title = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel(model.flow).apply { font = JBUI.Fonts.label().asBold() })
            add(badge)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(copy)
            add(open)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
            add(title, BorderLayout.WEST)
            add(actions, BorderLayout.EAST)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            add(phase, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Log level"))
                add(level)
            }, BorderLayout.EAST)
        }
        val messages = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 8, 6, 8)
            add(errors)
            add(truncation)
        }
        root.add(JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
            add(messages, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        scroll.border = JBUI.Borders.empty()

        model.addListener(::scheduleRender)
        scheduleRender()
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusableComponent(): JComponent = scroll

    override fun dispose() {
        disposed = true
    }

    // Updates arrive from the stream threads in bursts: render at most once per EDT turn.
    private fun scheduleRender() {
        if (scheduled.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater({
                scheduled.set(false)
                if (!disposed) render()
            }, ModalityState.any())
        }
    }

    internal fun render() {
        val snapshot = model.snapshot()
        if (snapshot.version == renderedVersion) return
        renderedVersion = snapshot.version
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)

        badge.state = snapshot.state
        phase.text = snapshot.phase
        executionUrl = snapshot.executionUrl
        open.isVisible = snapshot.executionUrl != null
        copy.isVisible = snapshot.tasks.any { it.logs.isNotEmpty() }
        errors.text = snapshot.errors.joinToString("\n")
        errors.isVisible = snapshot.errors.isNotEmpty()
        truncation.isVisible = snapshot.truncated
        for (task in snapshot.tasks) {
            sections.getOrPut(task.key) { TaskSection().also(tasks::add) }.update(task)
        }
        root.revalidate()
        root.repaint()
        if (atBottom) {
            ApplicationManager.getApplication().invokeLater({ bar.value = bar.maximum }, ModalityState.any())
        }
    }

    internal fun badgeText(): String = badge.text

    internal fun sectionTexts(): List<String> = sections.values.map { it.headText() + "\n" + it.logText() }

    private inner class TaskSection : JPanel(BorderLayout()) {
        private val chevron = JBLabel(AllIcons.General.ArrowRight)
        private val label = JBLabel()
        private val taskBadge = StateBadge()
        private val duration = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        private val body = JTextPane().apply {
            isEditable = false
            font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.CONSOLE_PLAIN)
            border = JBUI.Borders.empty(4, 24, 6, 8)
            isVisible = false
        }
        private var logs: List<LogEntry> = emptyList()
        private var renderedLogs: List<LogEntry> = emptyList()
        private var expanded = false
        private var userToggled = false

        init {
            isOpaque = false
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            val head = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 4)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(chevron)
                    add(label)
                    add(taskBadge)
                }, BorderLayout.WEST)
                add(duration, BorderLayout.EAST)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        userToggled = true
                        expand(!expanded)
                    }
                })
            }
            add(head, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        fun update(task: RunModel.TaskSnapshot) {
            label.text = task.label
            taskBadge.state = task.state
            duration.text = task.duration.orEmpty()
            logs = task.logs
            // A failed task opens by itself, unless the user already chose.
            if (!userToggled && StateBucket.of(task.state) == StateBucket.FAILED) expand(true)
            renderLogs(force = false)
        }

        private fun expand(value: Boolean) {
            expanded = value
            chevron.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            body.isVisible = value
            revalidate()
        }

        // Appends the new lines, or rewrites the body when the level changed or old lines were dropped.
        fun renderLogs(force: Boolean) {
            val doc = body.styledDocument
            val append = !force && renderedLogs.isNotEmpty() && logs.size >= renderedLogs.size &&
                logs.firstOrNull() === renderedLogs.firstOrNull()
            if (!append) doc.remove(0, doc.length)
            val min = logLevelRank(selectedLevel())
            for (entry in logs.drop(if (append) renderedLogs.size else 0)) {
                val entryLevel = (entry.level ?: "INFO").uppercase()
                if (logLevelRank(entryLevel) < min) continue
                if (doc.length > 0) doc.insertString(doc.length, "\n", null)
                doc.insertString(doc.length, entry.time() + "  ", MUTED)
                doc.insertString(doc.length, entryLevel.padEnd(6), LEVEL_STYLES[entryLevel] ?: MUTED)
                doc.insertString(doc.length, entry.message.orEmpty(), null)
            }
            renderedLogs = logs
        }

        fun headText() = listOfNotNull(label.text, taskBadge.text.ifEmpty { null }, duration.text.ifEmpty { null }).joinToString(" ")

        fun logText(): String = body.text
    }

    private fun selectedLevel() = (level.selectedItem as String).uppercase()

    private fun LogEntry.time() = timestamp.orEmpty().substringAfter('T', "").removeSuffix("Z").take(12).padEnd(12)

    // A rounded, tinted state label, as in the Kestra UI.
    private class StateBadge : JBLabel() {
        var state: String? = null
            set(value) {
                field = value
                text = value?.let(::titleCase).orEmpty()
                foreground = StateBucket.of(value)?.color ?: UIUtil.getLabelForeground()
                isVisible = !value.isNullOrEmpty()
            }

        init {
            border = JBUI.Borders.empty(2, 8)
            isVisible = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val color = foreground
            g2.color = Color(color.red, color.green, color.blue, 40)
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    companion object {
        private fun style(color: Color) = SimpleAttributeSet().apply { StyleConstants.setForeground(this, color) }

        private val MUTED = style(JBColor(0x7F8389, 0x8C9096))
        private val LEVEL_STYLES = mapOf(
            "ERROR" to style(StateBucket.FAILED.color),
            "WARN" to style(StateBucket.WARNING.color),
            "INFO" to style(StateBucket.INFO.color),
            "DEBUG" to style(StateBucket.NEUTRAL.color),
            "TRACE" to style(StateBucket.NEUTRAL.color),
        )
    }
}
