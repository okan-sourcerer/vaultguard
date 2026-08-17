package com.vaultguard.app.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

data class ParsedStructure(
    val usernameFields: List<AutofillId>,
    val passwordFields: List<AutofillId>,
    val webDomain: String?,
    val packageName: String?
)

class StructureParser(private val structure: AssistStructure) {

    private val usernameFields = mutableListOf<AutofillId>()
    private val passwordFields = mutableListOf<AutofillId>()
    private var webDomain: String? = null
    private var packageName: String? = null

    fun parse(): ParsedStructure {
        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            parseNode(windowNode.rootViewNode)
        }
        return ParsedStructure(usernameFields, passwordFields, webDomain, packageName)
    }

    private fun parseNode(node: AssistStructure.ViewNode) {
        if (packageName == null) {
            node.idPackage?.let { packageName = it }
        }

        if (webDomain == null) {
            node.webDomain?.let { webDomain = it }
        }

        val autofillId = node.autofillId
        if (autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val fieldType = classifyField(node)
            when (fieldType) {
                FieldType.USERNAME -> usernameFields.add(autofillId)
                FieldType.PASSWORD -> passwordFields.add(autofillId)
                FieldType.NONE -> {}
            }
        }

        for (i in 0 until node.childCount) {
            parseNode(node.getChildAt(i))
        }
    }

    private fun classifyField(node: AssistStructure.ViewNode): FieldType {
        // Check autofill hints first (most reliable)
        node.autofillHints?.forEach { hint ->
            when (hint.lowercase()) {
                View.AUTOFILL_HINT_PASSWORD,
                "password",
                "current-password",
                "new-password" -> return FieldType.PASSWORD

                View.AUTOFILL_HINT_USERNAME,
                View.AUTOFILL_HINT_EMAIL_ADDRESS,
                "username",
                "email",
                "login" -> return FieldType.USERNAME
            }
        }

        // Fall back to HTML attributes for web content
        node.htmlInfo?.let { html ->
            html.attributes?.forEach { pair ->
                val attrName = pair.first?.lowercase() ?: return@forEach
                val attrValue = pair.second?.lowercase() ?: return@forEach
                if (attrName == "type") {
                    when (attrValue) {
                        "password" -> return FieldType.PASSWORD
                        "email", "text" -> {
                            val name = getHtmlAttr(html, "name") ?: getHtmlAttr(html, "id") ?: ""
                            if (name.containsAny("user", "email", "login", "account")) return FieldType.USERNAME
                        }
                    }
                }
                if (attrName == "autocomplete") {
                    when {
                        attrValue.contains("password") -> return FieldType.PASSWORD
                        attrValue.contains("username") || attrValue.contains("email") -> return FieldType.USERNAME
                    }
                }
            }
        }

        // Fall back to input type flags
        val inputType = node.inputType
        if (inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
            inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0 ||
            inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
        ) {
            return FieldType.PASSWORD
        }
        if (inputType and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != 0 ||
            inputType and InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS != 0
        ) {
            return FieldType.USERNAME
        }

        // Fall back to hint text and resource ID
        val hintText = node.hint?.lowercase() ?: ""
        val idEntry = node.idEntry?.lowercase() ?: ""
        val combined = "$hintText $idEntry"

        if (combined.containsAny("password", "passwd", "pass")) return FieldType.PASSWORD
        if (combined.containsAny("user", "email", "login", "account")) return FieldType.USERNAME

        return FieldType.NONE
    }

    private fun getHtmlAttr(html: android.view.ViewStructure.HtmlInfo, name: String): String? {
        return html.attributes?.firstOrNull { it.first?.lowercase() == name }?.second
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }

    private enum class FieldType { USERNAME, PASSWORD, NONE }
}
