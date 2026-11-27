# Contributing to Kestra for JetBrains IDEs

## Code of Conduct

This project and everyone participating in it is governed by the
[Kestra Code of Conduct](https://github.com/kestra-io/kestra/blob/develop/.github/CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code. Please report unacceptable behavior
to <hello@kestra.io>.

## I Want To Contribute

> ### Legal Notice
> When contributing to this project, you must agree that you have authored 100% of the content, that you have the necessary rights to the content and that the content you contribute may be provided under the project license.

### Reporting bugs

Search the [existing issues](https://github.com/kestra-io/jetbrains-kestra/issues) before opening a new one. A good report includes your IDE and its version, the plugin version, your Kestra instance version, and the steps to reproduce.

### Reporting security issues

Please do not create a public GitHub issue. If you have found a security issue, email us directly at <hello@kestra.io> instead of raising an issue.

## Build and package locally

### Requirements

- JDK 21 or later. Gradle selects a JDK 21 toolchain and downloads one if none is installed.
- Nothing else: the Gradle wrapper downloads Gradle and the IntelliJ Platform the plugin builds against.

### Commands

| Command | Result |
|---|---|
| `./gradlew runIde` | Starts a sandbox IDE with the plugin loaded, for manual testing and debugging |
| `./gradlew runPyCharm` (`runWebStorm`, `runGoLand`) | The same sandbox in another IDE, at `latestPlatformVersion` |
| `./gradlew test` | Runs the tests in IntelliJ IDEA at `platformVersion` (see below) |
| `./gradlew testAllIdes` | Runs them again inside IntelliJ IDEA at `latestPlatformVersion` and PyCharm at both releases |
| `./gradlew buildPlugin` | Builds the installable archive `build/distributions/jetbrains-kestra-<version>.zip` |
| `./gradlew verifyPlugin` | Runs the JetBrains Plugin Verifier against the IDEs of `verifyIdes` in `gradle.properties` (IntelliJ IDEA and PyCharm), at both releases (API compatibility). It downloads each IDE the first time, about 1 GB, 4 GB unpacked: `-PverifyIdes=GO` checks another one |

The first build downloads the IntelliJ Platform, a few minutes. The sandbox IDE keeps its settings in `.intellijPlatform/sandbox`, so the instance URL and credentials you set there survive restarts.

To try the archive in your regular IDE: *Settings | Plugins | ⚙ | Install Plugin from Disk…*, select the `.zip`, and restart the IDE.

### Project layout

| Package | Content |
|---|---|
| `settings` | *Settings \| Tools \| Kestra*, credentials in the password safe |
| `api` | HTTP client for the Kestra API, Server-Sent Events, multipart bodies |
| `flow` | Reads flows from the YAML PSI |
| `schema` | Instance schema download, JSON Schema provider, completion narrowing by `type` |
| `validation` | Live validation through the instance's validate endpoint |
| `completion` | `{{ }}` completion |
| `run` | *Kestra flow* run configuration, inputs dialog, execution streaming |
| `actions` | Sign in, sign out, download schema, save flow, open and upload namespace files |
| `topology` | Flow topology preview in the embedded browser, plugin icons |
| `docs` | Kestra documentation in the embedded browser, fed by the instance |
| `namespace` | *Kestra Namespaces* tool window: browse, create, rename, delete and upload namespace files |
| `vfs` | The `kestra://` file system that opens namespace files in the editor, and their background upload |

### Tests

`./gradlew test` runs unit tests and IntelliJ Platform tests against a fake Kestra server: validation, completion, schema, runs.

The Plugin Verifier only checks API compatibility. Some failures only show once the plugin loads in a given IDE: in
2026.x JCEF moved from the platform to a bundled plugin, and without a `<depends>` on it the topology view failed in
PyCharm while the verifier reported the plugin as compatible. `testAllIdes` runs the suite inside the other IDEs, and
`PluginDependenciesTest` fails when a class the plugin uses comes from a bundled plugin that `plugin.xml` does not
declare. When the code starts using another bundled plugin, add one of its classes to that test.

The PR checks only run `check`: each IDE is a large download, so `testAllIdes` and `verifyPlugin` are run locally after
changing `plugin.xml`, the dependencies or the supported versions. The release workflow runs `verifyPlugin`, so a version
the verifier rejects is not published.

`LiveKestraTest` runs against a real instance and is skipped unless `KESTRA_TEST_URL` is set. It creates flows in the `jetbrains.test` namespace and deletes them. To start a local instance and run it:

```shell
docker run -d --name kestra-test --memory=6g --memory-swap=6g -p 18080:8080 \
  -e KESTRA_CONFIGURATION='kestra:
  server:
    basic-auth:
      username: admin@kestra.io
      password: Admin1234!' \
  kestra/kestra:latest server local

KESTRA_TEST_URL=http://localhost:18080 KESTRA_TEST_USERNAME=admin@kestra.io KESTRA_TEST_PASSWORD='Admin1234!' \
  ./gradlew test --tests 'io.kestra.jetbrains.LiveKestraTest'
```

The `Live Kestra` workflow runs it on every PR against `v1.3-slim`, `v2.0-slim` and `develop-slim`, since some routes differ between versions. `develop` is the next Kestra line: it also runs every weekday and does not block a PR. On an Enterprise Edition instance, also set `KESTRA_TEST_TENANT` to a tenant the user belongs to: without one, requests go to the `main` tenant.

## Release

### One-time setup

1. Create the Kestra organization (vendor) on the [JetBrains Marketplace](https://plugins.jetbrains.com/), and add the maintainers to it.
2. Create a Marketplace token in [your profile](https://plugins.jetbrains.com/author/me/tokens) and store it as the `PUBLISH_TOKEN` repository secret.
3. Generate the [plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) key and certificate:

   ```shell
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```

   Store the content of `chain.crt` as the `CERTIFICATE_CHAIN` secret, the content of `private.pem` as `PRIVATE_KEY`, and the password entered at the first command as `PRIVATE_KEY_PASSWORD`. Keep the files out of the repository. The certificate expires after the number of days given: renew it before then.
4. Build the first version with `./gradlew buildPlugin -Pversion=0.1.0` and upload it **manually** on the [Marketplace](https://plugins.jetbrains.com/plugin/add), under the Kestra organization. JetBrains reviews new plugins before they become public. Later versions are published from CI.

### Publishing a version

The plugin's change notes, shown in the IDE's *What's New* tab and on the Marketplace, come from
[CHANGELOG.md](CHANGELOG.md): the section of the version being built, or *Unreleased* when it has none. Add
user-facing changes under *Unreleased* as they land. Before tagging, move them under the new version and commit:

```shell
./gradlew patchChangelog -Pversion=0.2.0
```

Then push a tag:

```shell
git tag v0.2.0
git push origin v0.2.0
```

The [Release](.github/workflows/release.yml) workflow runs the tests and the Plugin Verifier, builds and signs the plugin with the tag's version, publishes it to the Marketplace, and creates the GitHub release with the signed archive attached for offline installation. Each upload goes through the Marketplace's review before it reaches users.

A tag with a pre-release suffix, such as `v0.2.0-beta.1`, is published to the `beta` channel and marked as a pre-release on GitHub.
