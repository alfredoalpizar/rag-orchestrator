package com.alfredoalpizar.rag.model.request

data class IngestDocumentRequest(
    val filePath: String
)

data class IngestDirectoryRequest(
    val directoryPath: String? = null,  // null = use configured path
    val recursive: Boolean = false
)

data class ReindexRequest(
    val recursive: Boolean = false
)

data class DeleteDocumentRequest(
    val docId: String
)
