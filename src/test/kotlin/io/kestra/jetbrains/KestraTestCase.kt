package io.kestra.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kestra.jetbrains.settings.KestraCredentials
import io.kestra.jetbrains.settings.KestraSettings

abstract class KestraTestCase : BasePlatformTestCase() {
    protected lateinit var kestra: FakeKestra

    // What /configs reports. Null: the endpoint fails, as when the version cannot be read.
    protected var kestraVersion: String? = "2.0.10"

    override fun setUp() {
        super.setUp()
        kestra = FakeKestra()
        kestra.handle("GET", "/configs") { exchange, _ ->
            val version = kestraVersion
            if (version == null) FakeKestra.respond(exchange, 500, """{"message":"unavailable"}""")
            else FakeKestra.respond(exchange, 200, """{"version":"$version"}""")
        }
        KestraSettings.instance.update(kestra.url, "", "INFO")
    }

    override fun tearDown() {
        try {
            KestraCredentials.clear(kestra.url)
            KestraSettings.instance.update("", "", "INFO")
            kestra.close()
        } finally {
            super.tearDown()
        }
    }
}

const val FLOW = """id: hello
namespace: company.team
inputs:
  - id: user
    type: STRING
    defaults: Kestra
tasks:
  - id: greet
    type: io.kestra.plugin.core.log.Log
    message: Hello
"""
