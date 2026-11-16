package io.kestra.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.kestra.jetbrains.settings.KestraCredentials
import io.kestra.jetbrains.settings.KestraSettings

abstract class KestraTestCase : BasePlatformTestCase() {
    protected lateinit var kestra: FakeKestra

    override fun setUp() {
        super.setUp()
        kestra = FakeKestra()
        kestra.json("GET", "/configs") { """{"version":"2.0.10"}""" }
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
