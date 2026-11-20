package io.kestra.jetbrains

import io.kestra.jetbrains.run.RunModel

// Turns a run's model updates into text, so tests can assert on what the view went through.
class RunRecorder(private val model: RunModel) {
    private val lines = ArrayList<String>()
    private var last: RunModel.Snapshot? = null

    init {
        model.addListener(::record)
    }

    @Synchronized
    private fun record() {
        val snapshot = model.snapshot()
        val previous = last
        if (snapshot.phase.isNotEmpty() && snapshot.phase != previous?.phase) lines += snapshot.phase
        snapshot.errors.drop(previous?.errors?.size ?: 0).forEach { lines += "ERROR $it" }
        if (snapshot.state != null && snapshot.state != previous?.state) lines += "State ${snapshot.state}"
        if (snapshot.executionUrl != null && snapshot.executionUrl != previous?.executionUrl) lines += "Url ${snapshot.executionUrl}"
        last = snapshot
    }

    @Synchronized
    fun text(): String = (lines + last?.tasks.orEmpty().flatMap { task ->
        listOf("[${task.label}] ${task.state} ${task.duration.orEmpty()}".trim()) + task.logs.map { it.format() }
    }).joinToString("\n")
}
