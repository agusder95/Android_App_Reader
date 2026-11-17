# ADR-003 — Acceso a documentos CBZ

**Estado: Approved.** Registra la decisión técnica de acceso a archivos indicada por el usuario el 11 de noviembre de 2026. El estado de la especificación se registra en [SPEC-002](../../specs/002-cbz-import/spec.md).

## Contexto

La importación offline necesita conservar el acceso al CBZ seleccionado y validar el ZIP. Un proveedor SAF puede entregar un flujo sin posicionamiento, y el acceso persistente puede perderse si el documento se mueve, desaparece o se revoca el permiso. [Permisos SAF](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions) · [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver)

## Decisión

Conservar el URI obtenido con `ACTION_OPEN_DOCUMENT` y solicitar permiso persistente de solo lectura. Priorizar el acceso directo cuando permita completar las verificaciones del ZIP. Para proveedores sin acceso aleatorio, usar una copia privada temporal y acotada del contenedor. Eliminarla al terminar por éxito, error o cancelación. Tras reiniciar la app, limpiar los temporales propios de operaciones interrumpidas antes de aceptar una nueva importación. Si se pierde el acceso, permitir que el usuario vuelva a elegir el archivo.

## Consecuencias

- El repositorio devuelve una referencia opaca. La gestión de URIs y temporales queda en los adaptadores de datos, conforme a [ADR-002](ADR-002-package-architecture.md).
- El respaldo temporal ocupa espacio mientras dura la operación. Los límites, la limpieza y la validación estructural se detallan en el [plan de SPEC-002](../../specs/002-cbz-import/plan.md).
- El permiso persistido permite intentar reabrir el URI. La disponibilidad depende del documento y el proveedor. La futura lectura deberá reabrir el URI y, si requiere respaldo, crear un temporal nuevo para esa operación. El registro durable de importaciones corresponde a la futura biblioteca.

## Ampliación para carpetas SAF en SPEC-003

**Estado de la ampliación.** `Approved`. Ratificada el 12 de noviembre de 2026 en el mensaje «Quiero cerrar las decisiones técnicas y funcionales pendientes de SPEC-003 de Sumi». Aplica a la ruta de carpetas de [SPEC-003](../../specs/003-local-library/spec.md).

- Usar `ACTION_OPEN_DOCUMENT_TREE` para una importación puntual y persistir la concesión de lectura del árbol antes de recorrerlo. Conservar la referencia al árbol y el URI de cada descendiente que se pueda abrir bajo esa concesión. Verificar pertenencia y acceso al reabrir. Si se pierde el acceso, ofrecer reselección del árbol o del archivo afectado. [Guía SAF](https://developer.android.com/training/data-storage/shared/documents-files) · [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract#buildDocumentUriUsingTree(android.net.Uri,java.lang.String)).
- Separar la concesión y el URI de reapertura de la identidad SAF (`authority` + `documentId`). Usar esa identidad para unificar la selección directa y por árbol bajo el mismo ID interno del libro. Verificar el descendiente con el URI construido para el árbol concedido y el acceso autorizado por el proveedor. [Identidad de documentos SAF](https://developer.android.com/reference/android/provider/DocumentsContract.Document#COLUMN_DOCUMENT_ID).
- Tratar las concesiones como compartidas. Liberar una concesión administrada por la biblioteca solo cuando ningún libro dependa de ella y hayan terminado las operaciones que la usan. Conservar las concesiones preexistentes cuya titularidad no esté establecida.
- Mantener el acceso de solo lectura, la validación de SPEC-002 y el respaldo temporal acotado. La prueba de integración debe cubrir reapertura tras reiniciar, revocación, recuperación por reselección, rechazo de URI fuera del árbol y liberación tras la última referencia. Los límites del recorrido y del lote se definen en [SPEC-003](../../specs/003-local-library/spec.md#límites-de-importación).
- La decisión de SPEC-002 sobre `ACTION_OPEN_DOCUMENT`, su filtro MIME y el contrato opaco del importador sigue vigente. La concesión del árbol se resuelve en `data`. Los límites de validación por CBZ y la limpieza de temporales se aplican también a los descendientes.
