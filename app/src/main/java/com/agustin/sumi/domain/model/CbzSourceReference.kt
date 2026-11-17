package com.agustin.sumi.domain.model

/**
 * Opaque reference to the original selected document, independent of any import copy.
 * Only the document adapter interprets the value. Keep it out of diagnostics.
 */
@JvmInline
value class CbzSourceReference(val value: String) {
    override fun toString(): String = "CbzSourceReference"
}
