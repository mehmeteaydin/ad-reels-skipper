package com.adreels.skipper

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

class ManifestVerificationTest {

    @Test
    fun `manifest must declare accessibility service and forbid internet permission`() {
        val manifestDoc = parseXml(resolveAppFile("src/main/AndroidManifest.xml"))

        val skipperService = manifestDoc.getElementsByTagName("service").toElements().firstOrNull {
            it.androidAttr("name") == SERVICE_CLASS
        }
        assertNotNull(skipperService, "Manifest XML must declare <service> $SERVICE_CLASS")
        assertEquals(
            BIND_ACCESSIBILITY_PERMISSION,
            skipperService.androidAttr("permission"),
            "Accessibility service must require $BIND_ACCESSIBILITY_PERMISSION"
        )

        val configMetaData = skipperService.getElementsByTagName("meta-data").toElements().firstOrNull {
            it.androidAttr("name") == ACCESSIBILITY_META_NAME
        }
        assertNotNull(configMetaData, "Accessibility service must link $ACCESSIBILITY_META_NAME metadata")
        assertEquals(
            ACCESSIBILITY_CONFIG_RESOURCE,
            configMetaData.androidAttr("resource"),
            "Accessibility metadata must point to $ACCESSIBILITY_CONFIG_RESOURCE"
        )

        val requestedPermissions = listOf("uses-permission", "uses-permission-sdk-23")
            .flatMap { manifestDoc.getElementsByTagName(it).toElements() }
            .map { it.androidAttr("name") }
        assertFalse(
            requestedPermissions.contains(INTERNET_PERMISSION),
            "Manifest MUST NOT request $INTERNET_PERMISSION"
        )
    }

    @Test
    fun `accessibility service config must scope target package to Instagram`() {
        val configDoc = parseXml(resolveAppFile("src/main/res/xml/accessibility_service_config.xml"))
        val root = configDoc.documentElement

        assertEquals("accessibility-service", root.tagName)
        assertEquals(
            TARGET_PACKAGE,
            root.androidAttr("packageNames"),
            "Accessibility config must restrict monitoring strictly to $TARGET_PACKAGE"
        )
        assertEquals(
            "true",
            root.androidAttr("canRetrieveWindowContent"),
            "Accessibility config must enable window content retrieval"
        )
        assertEquals(
            "true",
            root.androidAttr("canPerformGestures"),
            "Accessibility config must enable gesture execution"
        )
    }

    private fun resolveAppFile(appRelativePath: String): File {
        return listOf(
            File("app/$appRelativePath"),
            File(appRelativePath),
            File("../app/$appRelativePath")
        ).firstOrNull { it.exists() }
            ?: error("Could not locate $appRelativePath from test working directory")
    }

    private fun parseXml(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun NodeList.toElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SERVICE_CLASS = ".service.SkipperAccessibilityService"
        const val BIND_ACCESSIBILITY_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"
        const val INTERNET_PERMISSION = "android.permission.INTERNET"
        const val ACCESSIBILITY_META_NAME = "android.accessibilityservice"
        const val ACCESSIBILITY_CONFIG_RESOURCE = "@xml/accessibility_service_config"
        const val TARGET_PACKAGE = "com.instagram.android"
    }
}
