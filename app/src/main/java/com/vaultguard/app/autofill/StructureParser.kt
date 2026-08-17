package com.vaultguard.app.autofill

import android.app.assist.AssistStructure
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

    /**
     * Adapts a `ViewNode` into plain values and defers to [FieldClassifier], which is
     * testable on the host JVM — a `ViewNode` cannot be constructed in a unit test, and
     * classification is the part where being wrong types a password into the wrong box.
     */
    private fun classifyField(node: AssistStructure.ViewNode): FieldType {
        val htmlAttributes = node.htmlInfo?.attributes
            ?.mapNotNull { pair ->
                val name = pair.first ?: return@mapNotNull null
                val value = pair.second ?: return@mapNotNull null
                name to value
            }
            .orEmpty()

        return FieldClassifier.classify(
            autofillHints = node.autofillHints?.toList().orEmpty(),
            htmlAttributes = htmlAttributes,
            inputType = node.inputType,
            hintText = node.hint,
            idEntry = node.idEntry
        )
    }

}
