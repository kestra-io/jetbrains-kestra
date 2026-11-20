# Changelog

All notable changes to the Kestra plugin for JetBrains IDEs. The section of the version being built becomes
the plugin's change notes, shown in the IDE's *What's New* tab and on the Marketplace.

## [Unreleased]

## [0.1.0] - 2026-11-20

### Added

- Task, trigger and property completion and structural validation from the schema of the connected instance,
  matching its installed plugin versions.
- Live validation of the open flow against the instance's validate endpoint.
- `{{ }}` expression completion: variables, task outputs, inputs, secrets, KV pairs, namespace files,
  functions and filters, from the instance on Kestra 2.0 and later.
- Run, save and execute flows from the IDE, with a run view showing the execution state, one section per task
  run with its logs, and a log level filter.
- Topology preview of the flow beside the editor, colored live during a run.
- Kestra documentation beside the editor, versioned to the instance, with the documentation of the task under
  the cursor.
- Namespace files: browse and edit them, upload files and sync folders to a namespace, and edit the flows of a
  namespace from its `_flows` folder.
- Sign in with basic auth, an Enterprise Edition API token or a JWT session token, stored in the IDE password
  safe.

[Unreleased]: https://github.com/kestra-io/jetbrains-kestra/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/kestra-io/jetbrains-kestra/commits/v0.1.0
