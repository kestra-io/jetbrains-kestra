package io.kestra.jetbrains

import com.intellij.ide.AppLifecycleListener
import junit.framework.TestCase
import javax.xml.parsers.DocumentBuilderFactory

// The exit guard must exist when nothing of the plugin was used since the start (a tab restored at startup, edited,
// then Cmd+Q): it is registered from a listener declared in plugin.xml, not from a service that has to be created.
// ApplicationListener, which can refuse an exit, has no message bus topic, so the declared one is AppLifecycleListener.
class ExitDeclarationTest : TestCase() {
    fun testTheLifecycleListenerThatRegistersTheExitGuardIsDeclared() {
        val descriptor = ExitDeclarationTest::class.java.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val listeners = descriptor.getElementsByTagName("listener")
        val declared = (0 until listeners.length).map { listeners.item(it) }
            .filter { it.attributes.getNamedItem("topic").nodeValue == AppLifecycleListener::class.java.name }
            .map { it.attributes.getNamedItem("class").nodeValue }
        assertEquals(listOf("io.kestra.jetbrains.vfs.SaveOnExit"), declared)
        assertTrue(AppLifecycleListener::class.java.isAssignableFrom(Class.forName(declared.single())))
    }
}
