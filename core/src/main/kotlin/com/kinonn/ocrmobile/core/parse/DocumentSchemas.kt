package com.kinonn.ocrmobile.core.parse

import com.kinonn.ocrmobile.core.model.DocumentType
import com.kinonn.ocrmobile.core.model.FieldSchema
import com.kinonn.ocrmobile.core.model.FieldType
import com.kinonn.ocrmobile.core.model.VerticalZone

/**
 * Field schemas per document type. Order matters: fields earlier in the list consume
 * blocks first. Patterns are tried before keywords; the whole document list is scored
 * for type detection in [FieldExtractor.detectDocumentType].
 */
object DocumentSchemas {

    val nric: List<FieldSchema> = listOf(
        FieldSchema(
            key = "name",
            label = "Name",
            type = FieldType.NAME,
            keywords = listOf("NAME"),
            required = true,
        ),
        FieldSchema(
            key = "nric_number",
            label = "NRIC Number",
            type = FieldType.ID_NUMBER,
            patterns = listOf("[STFGM]\\d{7}[A-Z]"),
            keywords = listOf("NRIC", "IDENTITY CARD", "IDENTITY"),
            required = true,
        ),
        FieldSchema(
            key = "date_of_birth",
            label = "Date of Birth",
            type = FieldType.DATE,
            patterns = listOf("\\d{2}[-/.]\\d{2}[-/.]\\d{4}"),
            keywords = listOf("DATE OF BIRTH", "DOB", "BIRTH DATE"),
            required = true,
        ),
        FieldSchema(
            key = "race",
            label = "Race",
            type = FieldType.TEXT,
            keywords = listOf("RACE", "DIALECT"),
        ),
        FieldSchema(
            key = "sex",
            label = "Sex",
            type = FieldType.TEXT,
            keywords = listOf("SEX", "GENDER"),
        ),
        FieldSchema(
            key = "nationality",
            label = "Nationality",
            type = FieldType.TEXT,
            keywords = listOf("NATIONALITY", "CITIZENSHIP"),
        ),
        FieldSchema(
            key = "address",
            label = "Address",
            type = FieldType.ADDRESS,
            keywords = listOf("ADDRESS"),
            verticalZone = VerticalZone.BOTTOM,
        ),
    )

    val driversLicense: List<FieldSchema> = listOf(
        FieldSchema(
            key = "name",
            label = "Name",
            type = FieldType.NAME,
            keywords = listOf("NAME"),
            required = true,
        ),
        FieldSchema(
            key = "license_number",
            label = "License Number",
            type = FieldType.ID_NUMBER,
            patterns = listOf("[A-Z]{1,3}\\d{5,8}"),
            keywords = listOf("LICENCE NO", "LICENSE NO", "DL NO", "LICENCE NUMBER"),
            required = true,
        ),
        FieldSchema(
            key = "date_of_birth",
            label = "Date of Birth",
            type = FieldType.DATE,
            patterns = listOf("\\d{2}[-/.]\\d{2}[-/.]\\d{4}"),
            keywords = listOf("DATE OF BIRTH", "DOB"),
        ),
        FieldSchema(
            key = "valid_until",
            label = "Valid Until",
            type = FieldType.DATE,
            patterns = listOf("\\d{2}[-/.]\\d{2}[-/.]\\d{4}"),
            keywords = listOf("VALID UNTIL", "EXPIRY", "EXPIRES"),
        ),
    )

    val bankForm: List<FieldSchema> = listOf(
        FieldSchema(
            key = "account_number",
            label = "Account Number",
            type = FieldType.ID_NUMBER,
            patterns = listOf("\\d{8,12}"),
            keywords = listOf("ACCOUNT NO", "ACCOUNT NUMBER", "ACCT NO"),
            required = true,
        ),
        FieldSchema(
            key = "amount",
            label = "Amount",
            type = FieldType.AMOUNT,
            patterns = listOf("[\$S]?\\s?\\d{1,3}(,\\d{3})*(\\.\\d{2})?"),
            exactBlockMatch = true,
            keywords = listOf("AMOUNT", "SUM"),
            required = true,
        ),
        FieldSchema(
            key = "date",
            label = "Date",
            type = FieldType.DATE,
            patterns = listOf("\\d{2}[-/.]\\d{2}[-/.]\\d{4}"),
            keywords = listOf("DATE"),
            required = true,
        ),
        FieldSchema(
            key = "name",
            label = "Name",
            type = FieldType.NAME,
            keywords = listOf("NAME"),
        ),
    )

    /** Field keys used by the UI to render a parsed document. */
    fun keysFor(type: DocumentType): List<String> = all[type].orEmpty().map { it.key }

    val all: Map<DocumentType, List<FieldSchema>> = mapOf(
        DocumentType.NRIC to nric,
        DocumentType.DRIVERS_LICENSE to driversLicense,
        DocumentType.BANK_FORM to bankForm,
        DocumentType.GENERIC to emptyList(),
    )
}
