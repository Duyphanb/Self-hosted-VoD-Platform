/**
 * Encoding-owned V1/V5 persistence. Flyway owns the schema; entities expose read access only.
 * Later lifecycle/publication changes use conditional updates. Catalog consumes the encoding
 * status lookup through its own port and does not import this package.
 */
package com.vodplatform.encoding.persistence;
