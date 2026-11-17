# SPEC-004 — Plan técnico del lector CBZ

**Estado.** Plan de SPEC-004 `Approved` de forma condicionada el 16 de noviembre de 2026; las tareas de implementación aún requieren autorización explícita. Aplicar [ADR-002](../../docs/decisions/ADR-002-package-architecture.md), [ADR-003](../../docs/decisions/ADR-003-cbz-document-access.md), [SPEC-002](../002-cbz-import/spec.md) y [SPEC-003](../003-local-library/spec.md). No modifica contratos o decisiones aprobadas.

## Stack y recomendaciones aplicables

La inspección de `android-clean-architecture` respalda contratos de dominio puros, adaptadores en `data`, coroutines estructuradas y casos de uso solo cuando coordinan reglas. Sus ejemplos de módulos separados y DI con Hilt/Koin no se adoptan: contradicen el módulo único aprobado y no son necesarios para esta función. `mobile-android-design` recomienda estado hoisted, controles Compose accesibles, targets de 48 dp, adaptación de ventanas y restauración de estado; se incorpora en los criterios. Su ejemplo con carga asíncrona de imágenes no requiere Coil.

No se propone agregar dependencias: Compose/Material 3, Room, coroutines y AndroidX ya están en el proyecto; `BitmapFactory` es API Android. Coil y Navigation Compose siguen fuera de esta propuesta. Zoom hasta 3x se logra con transformación de la página muestreada; la decodificación regional no imprescindible se pospone a SPEC-005. Si las mediciones prueban que las APIs existentes no bastan, registrar la necesidad y pedir aprobación antes de cambiar stack.

## Componentes propuestos

| Ubicación en `:app` | Responsabilidad |
| --- | --- |
| `feature/reader` | `ReaderScreen`, `ReaderViewModel`, UI state/actions, interacción Material 3 y ciclo de vida. Depende de contratos/tipos del dominio y recibe el ID interno del libro. |
| `domain/model`, `domain/repository` | Estado de sesión, identidad de página, posición, dirección y resultados tipados. Proponer un contrato aditivo `ReaderRepository` o `ReaderPageSource<T>` para abrir metadatos y leer una página; sin Android, URI, ZIP, bitmap concreto ni temporales en los modelos puros. |
| `domain/usecase` | Casos para abrir, solicitar página y guardar posición solo si coordinan validación, posición o progreso; las operaciones simples pueden invocar contratos desde ViewModel conforme a ADR-002. |
| `data/repository` | Resolver `LibraryEntryId` con `LibraryRepository`, tomar la referencia SAF vigente, coordinar fuente de páginas, progreso y resultados. Reusar `CbzImportRepository`, sus errores, límites y orden, sin cambiar su contrato. |
| `data/local/document`, `data/local/archive` | Reabrir documentos mediante la fuente SAF existente; consultar ZIP y extraer una entrada bajo demanda con el parser compartido. Reusar permiso, acceso solo lectura, cancelación, `TemporaryCbzStore`/presupuesto y arbitraje `CbzWorkGate` de SPEC-003. |
| `data/local/database` | Guardar progreso por ID de libro en Room. La base está en versión 1; si se mantiene esta decisión, incorporar migración versionada v1→v2, preservar filas y cubrir migración. No editar el esquema v1 ya validado. |
| paquete base | Conectar la acción de la biblioteca con lector y dependencias. La navegación Compose actual no está aprobada como dependencia; comenzar con navegación de actividad/estado existente y reevaluar solo si el flujo lo requiere. |

## Flujo propuesto

1. `LibraryScreen` invoca `onOpen(LibraryEntryId)`. El ensamblado abre el lector para ese ID. El ViewModel carga entrada y progreso desde contratos; no guarda el bitmap ni el URI en estado persistido.
2. Data resuelve la referencia actual desde la biblioteca, comprueba acceso SAF y prepara un índice de páginas ordenado compatible con `CbzPageDescriptor`. Usa hasta 12 bytes descomprimidos por candidato para identificar su firma. Comprueba el origen actual antes de entregar una página.
3. La UI solicita una sola página. El adaptador abre esa entrada, aplica límites y verifica bytes reales y CRC según SPEC-002; luego `BitmapFactory` decodifica con muestreo al tamaño de presentación. El zoom transforma esa imagen hasta 3x; no se exige recuperar detalle mediante decodificación regional. No guarda páginas extraídas en Room ni precarga páginas vecinas.
4. Los cambios de página invalidan trabajos previos. La cancelación espera cierre de recursos; ningún resultado atrasado alcanza la UI ni sobrescribe progreso posterior. El bloqueo externo puede retrasar limpieza sin plazo garantizado.
5. Tras mostrar una página válida, guardar inmediatamente su identidad como última página confirmada y conservar la dirección por libro. Al reanudar, validar que la página aún exista y abrir una sesión nueva si el proceso se recreó.
6. En error de acceso, reutilizar el estado de disponibilidad y flujo de reselección de SPEC-003. Un error de página mantiene navegación posible y ofrece reintento/volver; no borrar ni fusionar entradas.

## Acceso aleatorio y presupuesto

La importación de SPEC-002 valida la estructura y el CRC de todas las entradas. T02 reutiliza la validación estructural, los límites declarados y el orden del mismo parser. El índice comprueba el directorio central y consulta como máximo 12 bytes de salida por entrada no vacía que no sea un directorio. Para STORED lee directamente ese prefijo. Para DEFLATE limita la salida entregada por el inflador al prefijo y cuenta aparte los bytes comprimidos que necesita leer. La firma identifica JPG, PNG o WebP y descarta auxiliares, independientemente de la extensión. La lectura completa de una entrada, la comprobación del tamaño real y el CRC ocurren cuando se pide esa página, antes de devolver éxito. Se conservan las identidades por ruta normalizada y el contrato `CbzImportRepository`.

En SAF seekable se reabre el origen en solo lectura por operación y se compara la estructura actual con el índice de la sesión. Para acceso secuencial se conserva una copia privada del ZIP comprimido por sesión. Ocupa el único cupo de copia y cuenta en el presupuesto agregado de 2 GiB y el espacio libre real. Cada página vuelve a comprobar la referencia de biblioteca, el permiso, la disponibilidad y los campos de tamaño y modificación publicados por el proveedor. Si dichos campos faltan o quedan obsoletos, un cambio del original puede pasar inadvertido hasta reabrir la sesión. La página se valida sobre la copia propia. `CbzWorkGate` protege cada operación de I/O. Se libera entre solicitudes. El cierre invalida trabajos activos y borra la copia tras liberar los recursos. Una apertura cancelada borra su copia y el siguiente acceso SAF recupera temporales huérfanos del proceso anterior.

El renderer muestra una página y no precarga. Usa bounds y `inSampleSize` para ajustar al tamaño de presentación, conserva proporción y libera el bitmap anterior al completar el cambio. Mantiene objetivo de 32 MiB y techo provisional de 64 MiB para bitmaps de páginas; al acercar hasta 3x transforma la imagen muestreada, por lo que el detalle puede suavizarse. Sin decodificación regional en SPEC-004. T03 debe medir bytes asignados y picos en API 23/37, incluida transformación y liberación, y ajustar el muestreo para no superar el techo. La medición debe incluir la caché de portadas existente. Los topes CBZ existentes se mantienen.

## Decisiones de producto para revisión

| Tema | Propuesta inicial | Pendiente de aprobación |
| --- | --- | --- |
| Dirección | LTR inicial; LTR/RTL configurable y persistido por libro. La secuencia del ZIP no cambia, solo el sentido del gesto. | Ninguna decisión funcional abierta. T01 define el modelo que preserva esta regla. |
| Navegación | Una página por vez; anterior/siguiente con botones accesibles y gesto horizontal opcional. | Ajustes menores de animación/gesto se pueden resolver en implementación sin cambiar el alcance. |
| Zoom | Ajustar a pantalla, controles accesibles y escala máxima 3x sobre bitmap muestreado. | Calidad visual medida bajo el techo de RAM; regional queda para SPEC-005. |
| Progreso | Última página confirmada por `LibraryEntryId`, guardada tras cada confirmación. Migración Room v1→v2: `lastReadPageId` nullable y `readingDirection` con valor predeterminado LTR; conservar filas, IDs y concesiones. | T04 verifica el esquema y la migración no destructiva. |
| RAM | Objetivo 32 MiB y techo provisional 64 MiB para bitmaps de páginas; una página y sin precarga. | Medir con la caché de portadas activa en API 23/37 y confirmar el techo antes de cerrar SPEC-004. |
| Acceso SAF | Índice estructural, acceso individual y CRC al consumir la entrada; copia privada del CBZ por sesión si no hay seek. | Validar proveedores y presupuesto de temporal en T02. |

## Estrategia de verificación y riesgos

- Dominio JVM: orden y navegación LTR/RTL, límites, progreso por libro, índices inválidos y cancelación.
- Datos JVM/instrumentados: acceso a una entrada sin descomprimir las demás, ZIP/CRC/tamaño malformados, cambio del origen, seekable y secuencial, presión de espacio, cierre de streams/canales/temporales, migración Room y reanudación después de reinicio.
- Compose/UI: botones anterior/siguiente, zoom 1x–3x, estados, accesibilidad, recomposición, cambio de configuración, segundo plano, dos tamaños de ventana y giro.
- Verificación Gradle: `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:connectedDebugAndroidTest`; JDK 21, API 23/37 cuando disponibles, regresiones de importador/biblioteca. Informar conteos reales y separar pruebas Gradle, fixtures y mediciones.
- Medir bytes leídos al abrir y por página, bitmaps residentes/picos, memoria retenida tras navegar y bytes/vida de temporal. Usar CBZ grande sintético y varios reales representativos sin datos personales. Ninguna cifra sintética se presentará como medición de hardware.

Condiciones obligatorias para validar y cerrar SPEC-004: T02 demuestra que el parser puede indexar entradas y validar estructura sin expandir todas las páginas, preservar las protecciones ZIP de SPEC-002 y verificar CRC de la entrada solicitada; también prueba SAF secuencial y cuota temporal. T03 mide el techo provisional de 64 MiB junto con la caché de portadas en API 23/37. T04 verifica la migración Room no destructiva v1→v2. La aprobación condicionada no presume superados estos puntos. Riesgos operativos: una fuente SAF secuencial puede exigir copia del CBZ completa (hasta 2 GiB), consumir la cuota temporal y aumentar latencia; un proveedor puede retrasar cancelación; el zoom sobre imagen muestreada puede perder nitidez; y un archivo sustituido puede invalidar el ID de página/progreso. T02 prueba acceso, T03 memoria/zoom, T04 migración, T05 ciclo de vida y T06/T07 UI e integración.
