package com.agustin.sumi.data.local.document

import com.agustin.sumi.domain.model.CbzImportError
import java.io.IOException

internal class DocumentAccessException(val error: CbzImportError) : IOException(error.name)
