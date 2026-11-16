<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png" alt="Kestra workflow orchestrator" />
  </a>
</p>

<h3 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h3>

<div align="center">
  <a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra"></a>
  <a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

# Kestra for JetBrains IDEs

Author and run Kestra flows in IntelliJ IDEA, PyCharm, WebStorm and the other JetBrains IDEs: completion and validation from the plugins installed on your instance, `{{ }}` autocompletion, and runs with live logs, all backed by the Kestra instance you connect to.

## Features

- **Instance-aware schema**: task, trigger and property completion plus structural validation from your instance's installed plugin versions. Once a task declares its `type`, only that plugin's properties are suggested.
- **Live validation** as you type: the instance's validate endpoint runs on the flow and reports the same errors as `flow validate`, on the line they concern.
- **`{{ }}` autocompletion** for Pebble expressions: on Kestra 2.0+ the instance reports what this exact flow can reference, including nested task outputs, inputs, secrets, KV pairs and namespace files. Functions and filters come from the instance. A built-in list covers 1.x, and flows that do not parse yet.
- **Run from the IDE**: validate, deploy and execute the open flow, enter its inputs, and follow the execution in the Run tool window: its state, one section per task run with its state, duration and logs, a log level filter, and a link to the execution in the Kestra UI. **Stop** kills the execution.
- **Topology preview**: the flow's graph beside the editor, updated as you type and colored live during a run. Click a task to jump to it.
- **Save to Kestra**: deploy the flow without running it.

## Requirements

- A JetBrains IDE 2025.3 or later: IntelliJ IDEA, PyCharm, WebStorm, GoLand, and the other IDEs built on the IntelliJ Platform.
- A Kestra instance, 1.x or 2.x. `{{ }}` completion from the instance needs 2.0 or later.

## Installation

### From the JetBrains Marketplace

In the IDE, open *Settings | Plugins | Marketplace*, search for **Kestra**, click *Install* and restart the IDE. Updates arrive through the IDE.

### From an archive

For machines that cannot reach the Marketplace, download the `.zip` attached to a [GitHub release](https://github.com/kestra-io/jetbrains-kestra/releases), then open *Settings | Plugins | ⚙ | Install Plugin from Disk…*, select the archive, and restart the IDE.

### Pre-releases

Pre-release versions are published to the `beta` channel. To get them, open *Settings | Plugins | ⚙ | Manage Plugin Repositories…* and add:

```
https://plugins.jetbrains.com/plugins/beta/list
```

## Setup

Open *Settings | Tools | Kestra* and set the URL of your instance, for example `http://localhost:8080`, and its tenant if it is multi-tenant. The setting applies to every project.

On a secured instance, click **Sign In…** (or *Tools | Kestra | Sign In…*). It supports basic auth (username and password), an Enterprise Edition API token (sent as a Bearer token), and a legacy JWT session token. Credentials are stored in the IDE password safe, per instance URL. *Tools | Kestra | Sign Out* clears them. When a request is refused, a notification offers to sign in.

The plugin uses the IDE's proxy settings (*Settings | Appearance & Behavior | System Settings | HTTP Proxy*) and the certificates the IDE trusts (*Settings | Tools | Server Certificates*), so an instance behind an internal certificate authority works once its certificate is accepted there.

## Usage

### Schema and completion

The flow schema is downloaded from the configured instance, so it matches its installed plugin versions. It is cached per instance, downloaded when the URL changes or after signing in, and *Tools | Kestra | Download Kestra Schema* forces a refresh, for example after installing a plugin on the instance.

The schema attaches to any YAML file detected as a flow (it declares `id`, `namespace`, and `tasks` or `triggers`), and to YAML files under a `_flows` directory.

### Live validation

While a flow file is open, its content is sent to the instance's validate endpoint, and the errors and warnings it returns are shown in the editor and in the *Problems* tool window.

### Expressions

Inside `{{ }}`, completion opens on `{{`, `.` and `|`, and on demand with the usual completion shortcut: variables (`inputs`, `outputs`, `vars`, `execution`…), their members, task outputs, secrets, KV pairs and namespace files, then functions after a name and filters after `|`.

### Running a flow

Click the ▶ icon next to the flow's `id`, or use *Run* from the editor's context menu. The plugin validates the flow, deploys the current editor content (saved or not), asks for its inputs, starts an execution, and follows it in the Run tool window:

- a header with the execution state, **Copy logs**, and **Open in Kestra**,
- the run's progress and any validation or deployment errors,
- one section per task run with its state and duration. Click a section to show its logs; a failed task opens by itself.

The **Log level** selector filters the logs shown. It starts at *Settings | Tools | Kestra | Default log level*. **Stop** kills the execution on the instance. The run is saved as a *Kestra flow* run configuration, so it can be started again from the toolbar.

### Topology preview

*Show Flow Topology* in the editor's context menu or *Tools | Kestra* opens the *Kestra Topology* tool window. It draws the graph of the flow in the editor, as the instance computes it, with plugin icons, and follows the editor as you type or switch files. While the flow runs from the IDE, each task is outlined with its state. Click a task to move the cursor to it, and use ⟳ to switch between a vertical and a horizontal layout.

The preview uses the IDE's embedded browser (JCEF), which every JetBrains IDE ships with.

### Saving a flow

To deploy the flow without running it, use *Save Flow to Kestra* from the editor's context menu or *Tools | Kestra*.

## Feedback

Report bugs and request features in the [issues](https://github.com/kestra-io/jetbrains-kestra/issues). See [CONTRIBUTING.md](CONTRIBUTING.md) to build the plugin locally.
