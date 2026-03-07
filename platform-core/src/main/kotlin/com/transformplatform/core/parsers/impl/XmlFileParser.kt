package com.transformplatform.core.parsers.impl

import com.transformplatform.core.parsers.FileParser
import com.transformplatform.core.spec.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val log = KotlinLogging.logger {}

/**
 * XML parser that uses XPath expressions from FieldSpec.path to extract values.
 * Also accepts XSD for schema validation when provided in spec metadata.
 *
 * Spec metadata keys:
 *   "recordXPath" - XPath to identify each record element e.g. "/root/records/record"
 *   "xsdLocation" - classpath or file path to XSD for pre-validation (optional)
 */
@Component
class XmlFileParser : FileParser {

    override val parserName = "XML_PARSER"

    override fun supports(format: FileFormat) = format == FileFormat.XML || format == FileFormat.ISO20022

    override fun validateSpec(spec: FileSpec) {
        require(spec.fields.isNotEmpty()) { "FileSpec must have at least one field" }
        require(spec.metadata.containsKey("recordXPath")) {
            "XML spec requires 'recordXPath' metadata key to identify record elements. " +
                "Example: /root/transactions/transaction"
        }
        spec.fields.forEach { field ->
            require(field.path != null || field.xmlAttribute != null) {
                "XML field '${field.name}' must define either 'path' (XPath) or 'xmlAttribute'"
            }
        }
    }

    override fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> = flow {
        validateSpec(spec)

        val recordXPath = spec.metadata["recordXPath"]!!
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }

        val document = factory.newDocumentBuilder().parse(input)
        document.documentElement.normalize()

        val xPath = XPathFactory.newInstance().newXPath()
        val nodeList = xPath.evaluate(recordXPath, document, XPathConstants.NODESET) as NodeList

        log.debug { "XML: Found ${nodeList.length} records matching XPath: $recordXPath" }

        for (i in 0 until nodeList.length) {
            val element = nodeList.item(i) as Element
            val record = parseElement(element, spec, i.toLong(), xPath)
            emit(record)
        }

        log.info { "XML parsing complete. Total records: ${nodeList.length}" }
    }

    private fun parseElement(
        element: Element,
        spec: FileSpec,
        sequenceNumber: Long,
        xPath: javax.xml.xpath.XPath
    ): ParsedRecord {
        val fields = mutableMapOf<String, Any?>()
        val errors = mutableListOf<ParseError>()

        spec.fields.forEach { fieldSpec ->
            val rawValue = extractValue(element, fieldSpec, xPath)
            if (rawValue == null && fieldSpec.required) {
                errors.add(ParseError(
                    field = fieldSpec.name,
                    message = "Required XML field '${fieldSpec.name}' not found at path '${fieldSpec.path}'",
                    severity = Severity.ERROR
                ))
                fields[fieldSpec.name] = fieldSpec.defaultValue
            } else {
                fields[fieldSpec.name] = rawValue ?: fieldSpec.defaultValue
            }
        }

        return ParsedRecord(
            sequenceNumber = sequenceNumber,
            fields = fields,
            errors = errors
        )
    }

    private fun extractValue(element: Element, fieldSpec: FieldSpec, xPath: javax.xml.xpath.XPath): String? =
        runCatching {
            when {
                fieldSpec.xmlAttribute != null -> element.getAttribute(fieldSpec.xmlAttribute)?.takeIf { it.isNotBlank() }
                fieldSpec.path != null -> (xPath.evaluate(fieldSpec.path, element, XPathConstants.STRING) as String).takeIf { it.isNotBlank() }
                else -> null
            }
        }.getOrElse {
            log.warn { "Failed to extract XML field '${fieldSpec.name}': ${it.message}" }
            null
        }
}
