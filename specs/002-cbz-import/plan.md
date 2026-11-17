# SPEC-002 — Plan técnico de importación CBZ

**Estado de SPEC-002: Validated.** El plan desarrolla las [decisiones técnicas](spec.md#decisiones-técnicas-y-verificación-pendiente) y el [ADR-003](../../docs/decisions/ADR-003-cbz-document-access.md). La [matriz de verificación](verification.md#evidencia-integral-de-t08) registra las comprobaciones ejecutadas y las variantes pendientes.

## Componentes

| Ubicación | Responsabilidad propuesta |
| --- | --- |
| `feature/library` | Presentar la acción mínima de importar, invocar el selector SAF desde la UI y mostrar estados de espera, resultado o error. |
| Paquete base `com.agustin.sumi` | Conectar la presentación con el contrato de importación. |
| `domain/model` y `domain/repository` | Definir la referencia opaca al documento, los descriptores de página y los resultados. Mantener la comparación natural como lógica Kotlin comprobable. Incorporar un caso de uso solo si concentra reglas o coordina operaciones. |
| `data/repository` | Coordinar el acceso al origen, la validación y la entrega del resultado. Traducir fallos técnicos a resultados del contrato. |
| `data/local/document` | Operar sobre el URI, abrir el documento en solo lectura, gestionar permisos y crear y limpiar el temporal privado cuando corresponda. |
| `data/local/archive` | Validar el ZIP, inspeccionar entradas y aplicar presupuestos de lectura. Entregar metadatos de página al repositorio. |

La UI puede manejar el URI de Android que devuelve el selector. El límite de entrada hacia dominio usa un identificador o una referencia opaca. Los detalles del proveedor, los descriptores de archivo y el ZIP quedan en datos. El ensamblado sigue [ADR-002](../../docs/decisions/ADR-002-package-architecture.md) y la [arquitectura aprobada](../../docs/architecture.md#responsabilidades-y-dependencias).

## Flujo de importación

1. Abrir `ACTION_OPEN_DOCUMENT` para un solo documento abierto en lectura. Incluir `application/vnd.comicbook+zip`, `application/zip` y los alias `application/x-cbz` y `application/x-zip-compressed`. El filtro ayuda a encontrar el archivo. La app comprueba el contenido y maneja un documento no disponible offline como error de acceso. [SAF](https://developer.android.com/training/data-storage/shared/documents-files) · [Registro MIME](https://www.iana.org/assignments/media-types/application/vnd.comicbook+zip)
2. Agrupar selecciones del mismo URI y solicitar el permiso persistente de lectura con el permiso concedido por el selector. Si no se puede persistir, informar que debe elegirse otro origen. Un permiso revocado o un archivo ausente ofrece reselección. Tras una importación fallida, liberar solo un permiso nuevo que no use otro resultado. [Permisos URI persistibles](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions)
3. Ejecutar la apertura, lectura y validación con coroutines en `Dispatchers.IO`. Usar acceso directo cuando permita consultar la longitud, verificar el directorio central y leer las entradas de la misma fuente. Si el descriptor SAF es secuencial, o el acceso directo no permite completar esas comprobaciones, copiar el CBZ por bloques al temporal privado y validarlo allí. Abrir siempre en solo lectura. [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) · [Dispatchers.IO](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html)
4. Inspeccionar estructura, rutas, contenido y tamaños conforme a la sección de validación. Entregar el resultado completo tras validar, ordenar y cerrar los recursos. Propagar la cancelación y permitir que la UI reintente o vuelva al selector.

## Ciclo de vida del archivo

- **Importación.** El adaptador de documentos crea temporales identificables en un directorio privado dedicado a la importación. Ante éxito, error o cancelación, cierra primero flujos, descriptores y ZIP, luego elimina el temporal de esa operación. Si la eliminación falla, registra el fallo sin exponer la ruta y deja el temporal identificado para reintento.
- **Reinicio.** En la inicialización y antes de admitir otra importación, el adaptador detecta temporales de operaciones interrumpidas y reintenta su eliminación. La limpieza se limita al directorio de la función y preserva archivos ajenos y temporales de operaciones activas. Si un archivo propio no se puede eliminar, su espacio sigue contando al comprobar la capacidad para una nueva copia.
- **Lectura futura.** El resultado de importación conserva la referencia al URI y metadatos, sin recursos abiertos ni dependencias del temporal. El futuro lector deberá reabrir el URI en solo lectura y tratar un documento ausente, modificado o revocado como un origen que requiere comprobarse de nuevo. Si necesita respaldo, creará un temporal nuevo para su propia operación. El contrato se comprueba desde el adaptador de importación en esta especificación.

## Validación del ZIP

- Antes de aceptar el ZIP, verificar la presencia y coherencia del registro de fin y el rango del directorio central dentro de la longitud real del contenedor. Contrastar el recuento y las referencias de entradas con las cabeceras locales, teniendo en cuenta los descriptores de datos admitidos. Una estructura ausente, truncada o incoherente se rechaza, aunque `ZipInputStream` haya devuelto entradas. [ZipInputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipInputStream.html)
- Usar `ZipFile` para acceder a entradas cuando haya un archivo compatible, incluido el temporal. Para acceso directo sin un archivo compatible, `ZipInputStream` es una opción para leer secuencialmente solo si la misma fuente se puede validar estructuralmente por separado. Si no se pueden verificar ambas condiciones, usar el temporal. [ZipFile](https://developer.android.com/reference/java/util/zip/ZipFile)
- Recorrer los datos expandidos de cada entrada de archivo hasta su fin por bloques acotados, con verificación de rutas antes de la lectura. Para cada imagen admitida, calcular `CRC32` sobre todos sus bytes expandidos y compararlo con el CRC de su entrada del directorio central. Mantener la comprobación de integridad de las demás entradas de archivo y contar sus bytes en el total expandido. Un CRC ausente o incorrecto, un tamaño incoherente o una entrada cifrada o incompatible generan un error controlado. [ZipEntry.getCrc](https://developer.android.com/reference/java/util/zip/ZipEntry#getCrc())
- Identificar las páginas mediante firmas JPG/JPEG, PNG o WebP. Una firma identifica el formato. La decodificación visual completa queda para el lector.
- Aplicar los [límites iniciales](spec.md#límites-iniciales) con contadores de 64 bits y buffers acotados. Medir longitud real cuando se disponga de ella y contar bytes reales al leer o copiar. Revisar el espacio disponible antes de la copia y distinguir falta de espacio de otros fallos de escritura. Comprobar cancelación entre lecturas y evitar operaciones que consuman una entrada sin contabilizarla.

### Viabilidad con APIs estándar

`ZipFile`, `ZipInputStream` y `CRC32` son suficientes como base para abrir el contenedor, leer entradas y calcular CRC sin una dependencia ZIP adicional. El adaptador debe aportar la comprobación estructural acotada, los límites efectivos y la política de temporales. La apertura exitosa del parser y el recuento declarado en el ZIP sirven como señales, junto con la verificación de entradas reales.

T04 debe demostrar que se pueden verificar por bloques la estructura y el límite de 5000 entradas antes de cargar un índice potencialmente grande, sin reservar memoria a partir de tamaños declarados. Probar discrepancias entre los recuentos declarados y reales, nombres y campos extra largos, offsets inválidos y el tratamiento de ZIP64. Si los controles no pueden garantizarse con las APIs estándar y un adaptador acotado, registrar el caso y revisar el enfoque antes de incorporar otra dependencia o debilitar la validación. T03 y T06 comprueban la viabilidad del acceso directo con proveedores reales y la equivalencia con el respaldo temporal.

## Orden e identidad

- Comparar segmentos de la ruta relativa y secuencias de dígitos por valor numérico sin convertirlas a enteros de tamaño fijo. Para valores iguales, ordenar primero la secuencia de dígitos más corta. Comparar texto sin depender del idioma del dispositivo y usar la ruta literal como último desempate. Así, `page2.jpg`, `page02.jpg` y `page10.jpg` se ordenan en esa secuencia.
- Distinguir páginas con el mismo nombre base en rutas distintas. Rechazar entradas con la misma ruta normalizada para evitar resultados ambiguos.
- Deduplicar por el URI exacto durante la sesión. Las selecciones concurrentes se agrupan en una sola importación y las repetidas reutilizan el resultado válido de esa sesión. Si el origen dejó de estar disponible, ofrecer reselección. La biblioteca podrá definir más adelante la identidad entre sesiones. El CRC de entrada se usa para integridad, sin calcular un hash completo del CBZ para deduplicar.

## Riesgos y manejo de errores

| Riesgo | Tarea | Control y evidencia |
| --- | --- | --- |
| Directorio central sobredimensionado, ZIP64 y alta expansión | T04 | Probar estructura, offsets y recuentos reales antes de cargar el índice, con casos ZIP64 válidos y malformados. Contar bytes reales y comprobar cada límite ZIP y el primer exceso. Registrar el tratamiento de las variantes ZIP64 según la viabilidad del parser acotado. |
| Acceso directo mediante SAF | T03 y T06 | T03 comprueba el mecanismo y sus límites con una fuente que permite acceso aleatorio y otra secuencial, permisos persistentes y respaldo temporal. T06 comprueba que ambas rutas den resultados equivalentes y que la pérdida de acceso permita recuperarse mediante reselección. |
| Cancelación de lectura bloqueada | T06 | Simular una fuente que confirme el inicio de la lectura y permanezca bloqueada hasta una señal de la prueba. Cancelar mientras sigue bloqueada, comprobar que no se publique un resultado parcial ni se registre éxito y liberar la fuente en el bloque de limpieza de la prueba. Tras liberarla, comprobar que los datos tardíos tampoco produzcan un resultado o éxito, que los recursos se cierren y que el temporal propio se elimine. |
| Falta de espacio o temporales huérfanos | T03 | Verificar espacio antes y durante la copia, y recuperar temporales propios tras terminar y reiniciar el proceso. |
| Rutas inseguras, duplicados y colisiones | T04 y T05 | Validar rutas absolutas, prefijos de unidad, separadores y segmentos `..` antes de aceptar entradas. Usar nombres internos solo para identificar entradas y comprobar colisiones al ordenar. [Protección ZIP en Android](https://developer.android.com/about/versions/14/behavior-changes-14#zip-path-traversal) |
| Diferencias entre parser y contenido | T04, T05 y T06 | Probar entradas locales legibles con directorio central inválido, una imagen con firma válida y CRC incorrecto, y extensiones engañosas. Confirmar en Android que ambas rutas aplican los mismos controles. |
| CBZ oculto por el filtro MIME | T07 | Verificar si algún proveedor oculta el CBZ al informar un MIME genérico. |

La cancelación de una coroutine es cooperativa. Una operación bloqueante de E/S puede continuar hasta que el proveedor devuelva el control. La prueba de T06 sincroniza el bloqueo y la liberación mediante señales y exige la limpieza al liberarse la lectura. El criterio carece de un plazo fijo de interrupción o limpieza para proveedores externos. [Cancelación de coroutines](https://kotlinlang.org/docs/cancellation-and-timeouts.html) · [runInterruptible](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)

## Estrategia de pruebas

- **Unitarias:** orden natural y desempates, firmas, nombres inseguros, deduplicación y resultados de error. Usar fixtures sintéticos y límites reducidos inyectables para verificar bordes sin generar archivos gigantes.
- **Integración:** ZIP de cero bytes, vacío, truncado, CRC inválido, sin imágenes y válido con formatos mezclados. Probar metadatos engañosos, alta expansión, falta de espacio, errores de lectura y el caso de cancelación bloqueada de T06. Verificar la limpieza del temporal y medir el consumo con un CBZ grande representativo.
- **Android:** selector real, cancelación, acceso persistido tras reiniciar, permiso revocado, proveedor sin persistencia y flujo offline. Incluir API 23 y API 37 si hay dispositivos disponibles. Registrar los casos, el entorno y los resultados.
- **Verificación del módulo:** ejecutar `:app:assembleDebug`, `:app:testDebugUnitTest` y `:app:lintDebug` con el Wrapper. Cualquier incorporación de dependencias requiere autorización.

[Especificación](spec.md) · [Tareas propuestas](tasks.md)
