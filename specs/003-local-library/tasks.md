# SPEC-003 — Tareas propuestas

SPEC-003 está `Validated`. T01 a T14 están `Validated`. Los identificadores `CA` remiten a la [especificación](spec.md#criterios-de-aceptación).

| ID | Entrega | Depende de | Criterios y condición de finalización |
| --- | --- | --- | --- |
| T01 | Alinear el daemon con JDK 21 y configurar las pruebas. | Autorización explícita de la tarea, registrada arriba. | RNF-01 y RNF-03. Documentar las versiones de testing, verificar JDK 21 sin rutas personales y conservar Kotlin, AGP y Gradle aprobados. Configurar JUnit y `AndroidJUnitRunner`, comprobar la compilación y preservar las regresiones. |
| T02 | Definir modelos, contrato de repositorio y reglas de título, búsqueda y orden. | T01. | CA-02 y CA-03. El dominio compila en Kotlin puro. Gradle ejecuta pruebas unitarias de Unicode, acentos, caracteres literales y desempates. |
| T03 | Incorporar Room 2.8.5 y KSP2 2.3.12. Implementar entidad, DAO, esquema, consultas y repositorio Room. | T02. | CA-01 a CA-03. Verificar unicidad, ID interno, concesiones, consultas, orden y transacciones. Con JDK 21 y el Wrapper, comprobar el DAO generado y el esquema inicial JSON reproducible en compilación limpia e incremental. Ejecutar escritura, lectura y reapertura persistente con `room-testing` 2.8.5 y `connectedDebugAndroidTest`. Si KSP2 2.3.12 es incompatible, detenerse y solicitar autorización antes de cambiarlo. |
| T04 | Conectar la importación CBZ existente con el alta durable de biblioteca. | T03 y SPEC-002 `Validated`. | CA-01 y CA-02. Validar alta, duplicados simultáneos y tras reinicio, archivos inválidos, cancelación antes del guardado y fallo de escritura sin éxito publicado. |
| T05 | Implementar detección de inaccesibilidad y reselección individual. | T04. | CA-06. Probar revocación, ausencia, errores transitorios, recuperación con el mismo ID interno, colisión de identidad SAF y conservación del registro al cancelar o fallar. |
| T06 | Generar bajo demanda la portada de la primera página válida con APIs Android y gestionar su caché. | T04 y T05. | CA-04 y CA-10. Incorporar el control compartido de validaciones y temporales que usa T09. Verificar 512 px máximos, proporción, ausencia de ampliación y respaldo ante inaccesibilidad. Medir caché RAM y pico de decodificación. Verificar el tope de 256 MiB, desalojo, regeneración y limpieza. |
| T07 | Implementar eliminación de entradas, limpieza de miniaturas y liberación segura de concesiones administradas. | T05 y T06. | CA-05. Probar eliminación durable, original intacto, reimportación, descarte de portada tardía y liberación solo tras la última referencia y el último uso activo. Verificar que una concesión de titularidad incierta se conserve. |
| T08 | Completar la cuadrícula adaptable de biblioteca en Material 3 y persistir la preferencia de orden. | T04 a T07. | CA-01 y CA-03 a CA-07. Verificar dos anchos, portadas bajo demanda, búsqueda sin distinguir mayúsculas, tres órdenes y restauración tras reiniciar. Comprobar recuperación, eliminación y semántica accesible. |
| T09 | Coordinar lotes y arbitrar recursos entre importación y portadas. | T04 y T06. | CA-08 y CA-10. Verificar una validación y una copia activas, cola de 32, tope de 1000 candidatos y temporales de hasta 2 GiB agregados. Medir metadatos frente a 16 MiB. Probar fallos, cancelación y ausencia de altas tardías. |
| T10 | Incorporar el selector múltiple y conectar sus resultados al lote. | T08 y T09. | CA-08. Verificar `EXTRA_ALLOW_MULTIPLE`, `ClipData`, respuesta con un solo URI, filtros de SPEC-002 y resultados parciales con permisos individuales. |
| T11 | Habilitar acceso durable mediante la concesión de árbol para el importador existente. | T07 y ampliación aprobada de ADR-003. | CA-02, CA-05 y CA-09. Demostrar reapertura después de reiniciar, rechazo de URI fuera del árbol, revocación y deduplicación con URI directos. Verificar conservación mientras quede un libro u operación activa, liberación al final y regresiones de SPEC-002. |
| T12 | Incorporar selección, recorrido controlado y reselección de carpeta. | T09 y T11. | CA-06, CA-09 y CA-10. Verificar `ACTION_OPEN_DOCUMENT_TREE`, recuperación por identidad, límites de 10 niveles y 10 000 nodos, `.cbz` y `.zip` con MIME genérico, omisión de ocultos, ciclos, ramas inaccesibles y cancelación. |
| T13 | Presentar progreso, cancelación y resumen de importación en Material 3. | T10 y T12. | CA-07 a CA-10. Verificar las tres acciones y su semántica accesible, progreso con total conocido o indeterminado, cancelación y resumen que identifica límites, omisiones conocidas y exploración incompleta. |
| T14 | Ejecutar la verificación integral y registrar la evidencia para revisión. | T01 a T13. | CA-01 a CA-10 y RNF-03. Ejecutar compilación, pruebas JVM, lint e instrumentación por Gradle. Comprobar los tres flujos, persistencia offline, regresiones de SPEC-002, compatibilidad y métricas de memoria y disco. Registrar comandos, conteos, advertencias y pendientes. |

## T01 — Reproducción de regresiones de SPEC-002

`testDebugUnitTest` ejecuta dos pruebas JUnit que invocan los puntos de entrada originales de archivo CBZ y almacenamiento temporal. `connectedDebugAndroidTest` ejecuta las comprobaciones de acceso SAF y repositorio mediante `AndroidJUnitRunner`.

`SafDocumentSourceTest` extiende `AndroidJUnitRunner` y conserva el despacho por fase para las comprobaciones de sistema y reinicio. Con ambas APK instaladas en un emulador de prueba, usar `adb shell am instrument -r -w -e phase <fase> com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest`. Ejecutar `access`, detener `com.agustin.sumi` y ejecutar `reopen`. Ejecutar `picker` en modo avión. Para `interrupt-copy`, detener el proceso al observar la copia parcial de 12 bytes y ejecutar `recover-copy`. Estas fases complementan las tareas de Gradle y se informan por separado. [Evidencia previa y secuencia de SPEC-002](../002-cbz-import/tasks.md#t08--evidencia-de-ejecución-del-12-de-noviembre-de-2026).

## T01 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. Autorización de ejecución en el mensaje «Autorizo exclusivamente T01 de SPEC-003». La incorporación de Room, KSP2 y `room-testing`, junto con la generación del DAO y la exportación JSON, corresponde a T03 por instrucción de esa autorización.

El Wrapper informó Gradle 9.5.0 y el criterio de Java 21. Un script de inicialización local registró `java.version=21.0.12` desde el daemon durante el build. El criterio versionable usa un JDK local y carece de rutas personales. Se conservaron Kotlin 2.4.21 y AGP 9.3.1. Las versiones de pruebas incorporadas son JUnit 4.13.2, AndroidX Test Runner 1.7.0 y AndroidX Test JUnit 1.3.0.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --version` | Código 0. Gradle 9.5.0, JVM de lanzamiento 21.0.12 y criterio del daemon Java 21. |
| `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` | Código 0 en la ejecución final con `--offline --no-daemon --max-workers=2 --continue` y el script local de comprobación del daemon. APK debug generada en la primera compilación y al día en la final. |
| `testDebugUnitTest` | Dos pruebas JUnit ejecutadas y aprobadas tras corregir el conjunto de fuentes Kotlin. Ejecutaron 44 comprobaciones de archivo y 9 de temporales. Resultado reutilizado en la ejecución final. |
| `lintDebug` | 0 errores, 2 advertencias conocidas (`AndroidGradlePluginVersion` y `MissingApplicationIcon`). Se conservó AGP 9.3.1. |
| `connectedDebugAndroidTest` | Dos pruebas instrumentadas ejecutadas y aprobadas en el emulador API 37, acceso SAF y repositorio. XML con 0 fallos, 0 errores y 0 omitidas. |
| Fases SAF vía ADB en API 37 | `access` 5, `reopen` 2 tras detener el proceso, `picker` 6 en modo avión y `recover-copy` 2 tras interrumpir la copia real. El primer intento de `picker` falló por un aviso de Pixel Launcher y pasó en el reintento. |

RNF-01 y RNF-03 quedan cubiertos en lo que corresponde al setup de T01. La ejecución en API 23 y la repetición de la suite autónoma ZIP/temporales directamente en Android quedan pendientes. Los registros locales están en `.gradle/spec-003-t01/` y los informes Gradle en `app/build/`.

**Validación de T01, 16 de noviembre de 2026.** El usuario autorizó la validación y la implementación exclusiva de T02 en el mensaje «Autorizo la validación de T01 y la implementación exclusiva de T02 de SPEC-003». Revisé el registro del daemon JDK 21.0.12, la compilación, lint, los XML de JUnit e instrumentación y los registros de las fases SAF. Los pendientes de compatibilidad y regresión indicados arriba se conservan.

## T02 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. Se incorporaron los modelos y el contrato de repositorio en `domain`, independientes de Android y de almacenamiento.

- **CA-02, dominio.** La identidad compara `authority` y `documentId` como valores opacos. El ID interno y la referencia de reapertura son independientes. La unicidad durable y la integración SAF siguen en las tareas asignadas.
- **CA-03, dominio.** El título elimina solo el sufijo final `.cbz` o `.zip` y usa el respaldo provisto por el llamador cuando falta nombre. Búsqueda literal por subcadena con NFC y minúsculas independientes de la configuración regional, conservando acentos. El orden de título compara lexicográficamente las claves normalizadas. Los tres órdenes desempatan por ID interno ascendente. Las consultas persistentes y la restauración de la preferencia se verifican en las tareas asignadas.

`gradlew.bat --offline --no-daemon --console=plain --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` terminó con código 0. El script local de comprobación confirmó JDK 21.0.12 desde el daemon. Gradle ejecutó 12 pruebas JUnit con 0 fallos y 0 omitidas. Diez cubren el nuevo dominio y las otras dos ejecutan 53 comprobaciones JVM de SPEC-002. Lint informó 0 errores y 2 advertencias conocidas (`AndroidGradlePluginVersion` y `MissingApplicationIcon`). No correspondió instrumentación para este cambio de dominio. Evidencia local en `.gradle/spec-003-t02/gradle-final.log` y `app/build/test-results/testDebugUnitTest/`.

**Validación de T02, 16 de noviembre de 2026.** El usuario autorizó la validación y la implementación exclusiva de T03. Se cotejaron los modelos, el contrato, las reglas de consulta y los 12 resultados JUnit con los criterios de T02. Las verificaciones posteriores de persistencia, SAF y preferencia de orden conservan sus asignaciones.

## T03 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. Room 2.8.5 y KSP2 2.3.12 se incorporaron a `:app` mediante el catálogo. Se generaron `LibraryDao_Impl` y `LibraryDatabase_Impl` y se exportó el esquema v1 a `app/schemas`. Room se mantiene en `data`.

- **CA-01, persistencia.** Escritura y lectura instrumentadas con cierre y reapertura de la base en disco, creada desde el JSON con `room-testing`.
- **CA-02, persistencia.** El índice único de `authority` y `documentId` evita altas simultáneas duplicadas. La prueba de reemplazo verifica que una colisión conserva la fila anterior. El ID interno permanece estable y la concesión se guarda como referencia opaca.
- **CA-03, consultas.** Las pruebas comparan los tres órdenes, los desempates, la búsqueda literal Unicode y las ventanas con las reglas del dominio. Se fijó un máximo de 100 filas por ventana. Las pruebas incluyen una observación activa que recibe los cambios de escritura.

El primer ensayo del esquema falló con `AbstractMethodError` al combinar Kotlinx Serialization JSON 1.8.1 de `room-testing` con Core 1.7.3 del grafo de la app. Se alineó el BOM transitivo a 1.8.1 mediante una restricción de Gradle. La reapertura desde el JSON pasó con las versiones autorizadas de Room y KSP2.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0. El script de verificación registró JDK 21.0.12 desde el daemon. APK debug generada. |
| `testDebugUnitTest` | 12 pruebas JUnit aprobadas. Dos ejecutaron 53 comprobaciones JVM de SPEC-002. |
| `lintDebug` | 0 errores y 2 advertencias conocidas (`AndroidGradlePluginVersion`, `MissingApplicationIcon`). |
| `connectedDebugAndroidTest` | 7 aprobadas en el emulador API 37, cinco de Room y dos de SPEC-002. |
| Reproducibilidad del esquema | `:app:assembleDebug --no-build-cache` generó el JSON en una copia local limpia que carecía del esquema previo. La segunda compilación y el esquema del repositorio coincidieron byte a byte con el recién generado. SHA-256 `9C09516097D8DB532725B82B46B17C02E112E16D3672454D24769D5B74FA2D96`. |
| Fases de SPEC-002 vía ADB en API 37 | 15 comprobaciones aprobadas. `access` 5, `reopen` 2, `picker` 6 y `recover-copy` 2 tras interrumpir la copia parcial. |

La integración con importación, el ciclo de vida de permisos y la restauración de la preferencia de orden siguen en las tareas asignadas. Se conservan los pendientes de API 23 y de repetir las suites autónomas ZIP y temporales directamente en Android. Registros locales en `.gradle/spec-003-t03/` e informes de Gradle en `app/build/`.

**Validación de T03, 16 de noviembre de 2026.** El usuario autorizó explícitamente la validación de T03 y la implementación exclusiva de T04. Se verificaron las versiones del catálogo, JDK 21.0.12 efectivo, los archivos generados, los cinco resultados instrumentados de Room y la coincidencia del esquema exportado. Se conservan la restricción de Kotlinx Serialization 1.8.1 y los pendientes documentados.

## T04 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. El selector individual delega en `ImportIntoLibrary`, que obtiene la identidad y el nombre mediante el adaptador SAF, reutiliza `CbzImportRepository` para validar el documento y traduce el resultado de la escritura en Room. La UI distingue alta confirmada, duplicado, identidad inválida y fallo de escritura. El caso de uso mantiene una validación activa y usa una nueva sesión del importador por selección para volver a validar el contenido tras un fallo de escritura o una selección repetida.

- **CA-01, importación individual.** La prueba JVM mantiene suspendida la escritura y comprueba que el éxito espera el resultado. Las pruebas instrumentadas comprueban el rechazo de un CBZ corrupto y el fallo real de apertura para escritura de Room. El selector SAF en modo avión verifica la escritura del título derivado, la conservación al cancelar y el acceso al mismo registro tras relanzar el proceso.
- **CA-02, identidad directa.** Las solicitudes concurrentes producen un alta y un duplicado. Cerrar y reabrir Room conserva el ID interno y el selector vuelve a identificar el mismo documento como duplicado tras relanzar Sumi. Una identidad inválida interrumpe el flujo antes del importador. El acceso por árbol y la deduplicación entre modalidades se comprueban en T11 y T12.

La cancelación previa a la escritura se comprobó con una validación simulada que devuelve un resultado tarde. La cancelación del selector se comprobó con el sistema real. Si la validación termina y falla el alta, la concesión persistente puede quedar disponible para el reintento. La liberación coordinada de concesiones sigue asignada a T07. La biblioteca visual y la recuperación de orígenes se mantienen en sus tareas. Se conservan los pendientes de API 23 y las suites autónomas directamente en Android.

| Comando | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | Código 0 con JDK 21.0.12 efectivo. APK generada. JUnit ejecutó 17 pruebas con 0 fallos y 0 omitidas. Cinco cubren T04 y dos agrupan las 53 comprobaciones JVM de SPEC-002. Lint informó 0 errores y 2 advertencias conocidas (`AndroidGradlePluginVersion`, `MissingApplicationIcon`). |
| `gradlew.bat --offline --no-daemon :app:connectedDebugAndroidTest` | Código 0. 10 aprobadas en API 37, tres de integración de T04, cinco de Room y dos de regresión SPEC-002. |
| `adb shell am instrument -r -w -e phase picker com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` | Seis comprobaciones pasaron en cada una de dos ejecuciones en API 37. Ambas se hicieron sin red predeterminada. Entre ejecuciones se detuvo y relanzó Sumi. Los títulos y los IDs guardados permanecieron y la selección repetida se informó como duplicada. |

El primer build encontró una referencia incremental de DEX a la salida local usada en la verificación de T03. Pasó al ejecutar las tareas con `--rerun-tasks`; los comandos finales también pasaron sin esa opción. Los informes y registros están en `app/build/` y `.gradle/spec-003-t04/`. Se cotejaron los hashes de SPEC-002 y del esquema Room v1 con los anteriores a T04.

**Validación de T04, 16 de noviembre de 2026.** El usuario autorizó explícitamente la validación de T04 y la implementación exclusiva de T05. Se cotejaron la confirmación durable de éxito, los duplicados concurrentes y tras relanzar, los fallos y las cancelaciones. Se conservan el incidente DEX y su recuperación con `--rerun-tasks`, las pruebas pendientes de API 23 y de suites autónomas en Android, y la gestión de concesiones en T07.

## T05 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. La pantalla actual ofrece una lista de recuperación con comprobación de acceso al volver al primer plano. Un fallo transitorio ofrece reintento. Un documento ausente o un permiso revocado ofrecen reselección individual mediante `ACTION_OPEN_DOCUMENT` para la entrada afectada.

**CA-06, recuperación individual.** Se comprobaron por separado revocación, ausencia y fallo transitorio con el proveedor SAF de prueba. El reemplazo verifica la identidad del candidato, vuelve a validar el CBZ y actualiza la referencia en Room conservando ID, título y fecha. Se comprobó que una colisión y un CBZ inválido dejan intactos ambos registros, que una validación cancelada con resultado tardío no alcanza la escritura y que cancelar el selector conserva el registro. Los originales se abren en solo lectura.

La clasificación depende de las señales del proveedor SAF. Se conservan los pendientes de API 23, las suites autónomas en Android, la gestión de concesiones de T07 y la recuperación por árbol de T11 y T12. Los hashes de SPEC-002 y del esquema Room v1 coinciden con los anteriores a T05.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0. El script local registró JDK efectivo 21.0.12. APK debug generada. |
| `testDebugUnitTest` | 18 ejecutadas y aprobadas, sin fallos ni omitidas. Incluye la prueba nueva de cancelación de reselección y las dos pruebas que agrupan las 53 comprobaciones JVM de SPEC-002. Gradle reutilizó esos resultados tras el ajuste de texto. |
| `lintDebug` | 0 errores y 2 advertencias conocidas (`AndroidGradlePluginVersion`, `MissingApplicationIcon`). |
| `connectedDebugAndroidTest` | 12 aprobadas en API 37, sin fallos ni omitidas. Incluye dos pruebas nuevas de T05 y dos de regresión instrumentadas de SPEC-002. |
| `adb shell am instrument -r -w -e phase <fase> com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` | `picker` 6 comprobaciones aprobadas y `recovery-picker` 3 aprobadas, en API 37 sin red predeterminada. La fase de recuperación cubrió revocación, cancelación, colisión y recuperación del mismo ID. |

Los informes de Gradle están en `app/build/` y los registros en `.gradle/spec-003-t05/`.

**Validación de T05, 16 de noviembre de 2026.** El usuario autorizó explícitamente la validación de T05 y la implementación exclusiva de T06. Se cotejaron las 18 pruebas JVM, las 12 instrumentadas y las fases SAF. La evidencia confirma los tres estados de acceso, la conservación del ID, título y fecha y la integridad del registro ante cancelación, rechazo y colisión. Se conservan los pendientes de API 23, suites autónomas Android, concesiones de T07 y árboles de T11/T12.

## T06 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. La pantalla existente ofrece una portada a pedido por vez. El adaptador reabre el CBZ en solo lectura, verifica la estructura y el orden de SPEC-002, decodifica la primera página admisible y completa su flujo para verificar CRC y tamaño. Un origen inaccesible o una imagen inválida muestra el respaldo. La clave de caché incorpora la entrada, el origen, la página, su CRC y tamaño. El adaptador coteja la fila vigente antes de publicar.

- **CA-04.** Se probaron JPG, PNG y WebP, una imagen pequeña sin ampliación, primera página natural por canal y copia temporal, redimensionamiento proporcional, regeneración, revocación con caché poblada, desalojo y limpieza. Se muestrea antes de escalar a 512 px. El límite defensivo de cabecera es de 100 millones de píxeles y el lado de salida del muestreo se acota a 1024 px. La caché usa hasta 256 MiB en disco y un objetivo de 32 MiB en RAM que se reduce a 8 MiB ante la señal de presión de memoria. Los archivos parciales propios se limpian al terminar y en el primer acceso de una sesión nueva.
- **CA-10, control compartido.** Importación, reemplazo y portadas usan un turno por proceso. Una portada cancelada en una lectura bloqueada mantiene el turno hasta cerrar el temporal. La importación que espera puede continuar después. El presupuesto agregado de 2 GiB cuenta los archivos propios existentes y sigue limitado por el espacio real disponible. Los lotes y su presupuesto de metadatos se verifican en sus tareas asignadas.

La prueba de memoria registra la asignación de bitmaps en la decodificación y una muestra puntual del heap nativo. La revisión con hardware y archivos representativos sigue pendiente. Una portada cacheada vuelve a validar el archivo en cada solicitud para comprobar acceso e identidad de la página. Un proveedor lento puede prolongar la espera de las operaciones que comparten el turno. Se conservan los pendientes de API 23, suites autónomas Android, concesiones de T07 y árboles de T11/T12.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 con JDK 21.0.12 efectivo. APK debug generada. |
| `testDebugUnitTest` | 18 aprobadas. Dos agrupan las 53 comprobaciones JVM de SPEC-002. |
| `lintDebug` | 0 errores y 2 advertencias conocidas (`AndroidGradlePluginVersion`, `MissingApplicationIcon`). |
| `connectedDebugAndroidTest` | 18 aprobadas en API 37, con 6 nuevas de T06 y 2 regresiones instrumentadas de SPEC-002. |
| `adb shell am instrument -r -w -e phase picker com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` | 6 comprobaciones del selector de SPEC-002 aprobadas en API 37 sin red predeterminada. |

Para imágenes sintéticas de 2400 × 1200, los tres formatos produjeron 512 × 256. El pico de asignación de los bitmaps observados fue de 1 244 288 bytes. El mayor incremento puntual de heap nativo durante el escalado fue de 1 266 768 bytes. La prueba de caché RAM retuvo 32 MiB y bajó a 8 MiB al aplicar presión. Con una cuota de disco inyectada de 628 bytes se midieron 419 bytes después del desalojo. La cuota configurada en producción es de 256 MiB. Los registros quedaron en `.gradle/spec-003-t06/`.

**Validación de T06, 16 de noviembre de 2026.** El usuario autorizó explícitamente validar T06 e implementar exclusivamente T07. Se cotejaron las pruebas de JPG, PNG y WebP, tamaño y proporción, regeneración, desalojo, limpieza, cancelación con lectura bloqueada y arbitraje de importaciones, portadas y temporales. Se conservan API 23, suites autónomas directamente en Android y mediciones con dispositivos y archivos representativos. El riesgo de rendimiento sigue abierto. Una miniatura cacheada evita decodificar, pero cada solicitud vuelve a validar el CBZ bajo el turno compartido y puede demorar otras operaciones.

## T07 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`, según el estado confirmado por el usuario al retomar T08.

- **CA-05, eliminación y reimportación.** La acción de la biblioteca pide confirmación y borra la fila de Room antes de limpiar recursos derivados. Se verificó que cancelar el diálogo conserva la entrada y que confirmar la elimina incluso al reiniciar el proceso. La prueba de persistencia reimportó el mismo documento con un ID nuevo y comparó los bytes del CBZ original. Un fallo en Room conserva la fila, la portada y la concesión. Si falla la limpieza posterior al borrado, el estado informa que queda por reintentar y la reconciliación del siguiente arranque o regreso al primer plano elimina los residuos.
- **CA-05, miniaturas y concurrencia.** Las miniaturas incluyen el ID de libro en el nombre para limpiar todas sus revisiones en RAM y disco. Se descartan las miniaturas de la versión anterior cuya clave no permite identificar el libro. Se verificó que una portada en vuelo descarta su resultado al solicitar la eliminación, que la limpieza espera el cierre de la operación y que cancelar un borrado en espera conserva sus recursos.
- **CA-05, concesiones.** El registro privado de Sumi conserva el URI y el instante de persistencia solo para nuevos permisos de lectura adquiridos por la biblioteca. El turno compartido coordina importación, reselección, comprobación de acceso, portadas, borrado y reconciliación. La liberación exige que la concesión registrada siga coincidiendo y que Room no tenga referencias dependientes. Se probaron concesiones preexistentes e inciertas, dos libros con una referencia compartida, liberación al quitar el último, reinicio durante la limpieza y validaciones fallidas. [Permisos persistentes de Android](https://developer.android.com/reference/android/content/ContentResolver#takePersistableUriPermission(android.net.Uri,int)).

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest` | Código 0 con JDK 21.0.12 efectivo. APK debug generada. JUnit ejecutó 18 pruebas y todas pasaron, incluidas dos que agrupan 53 casos JVM de SPEC-002. |
| Mismos parámetros de Gradle con `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0. Compilación y JUnit reutilizaron el resultado anterior. Lint registró 0 errores y 2 advertencias conocidas, `AndroidGradlePluginVersion` y `MissingApplicationIcon`. Instrumentación API 37 ejecutó 27 pruebas, 27 aprobadas, 0 fallidas y 0 omitidas. Incluye 9 nuevas de T07 y 2 regresiones integradas de SPEC-002. |
| `adb shell am instrument -r -w -e phase picker com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` | 6 comprobaciones autónomas aprobadas en API 37 sin red predeterminada. El primer script PowerShell interpretó mal la salida multilínea y salió con código 1 después de recibir las seis aprobaciones. Con el script corregido se repitieron las seis comprobaciones con código 0 y se reimportó desde el selector la entrada eliminada durante la prueba de UI. |
| `adb shell uiautomator dump`, `adb shell input tap`, `adb shell am force-stop` y `adb shell am start` | Comprobación separada en API 37 con código 0. Tres escenarios aprobados, cancelación del diálogo, confirmación y ausencia de la entrada en un PID nuevo con el segundo libro intacto. |

Se cotejaron los hashes de SPEC-002 y del esquema Room v1. Los registros de verificación están en `.gradle/spec-003-t07/`.

**Pendientes y riesgos.** Se conservan API 23, las demás suites autónomas Android y las mediciones de rendimiento con proveedores, dispositivos y CBZ representativos. Una operación bloqueada puede demorar la limpieza mientras retenga el turno compartido. Si el proceso se interrumpe entre persistir el permiso en Android y registrar la titularidad, se conserva el permiso por falta de prueba de propiedad. El caso de concesión compartida usó dos filas con la misma referencia y un permiso SAF real. La navegación y validación de descendientes de un árbol real corresponden a T11 y T12.

## T08 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`.

- **CA-01 y CA-03.** La pantalla observa ventanas de 20 entradas desde Room y actualiza la cuadrícula cuando cambia la consulta o la base. Reutiliza la búsqueda normalizada y el desempate por ID del repositorio. El orden inicial es Recientes y la selección se guarda en `SharedPreferences` mediante un contrato de dominio.
- **CA-04 a CA-07.** La cuadrícula usa celdas Material 3 con un mínimo adaptable de 148 dp y claves por ID. Pide la portada a la caché existente cuando la celda entra en el área visible y muestra el respaldo durante la carga o cuando el archivo no es accesible. Reutiliza la recuperación individual y el borrado con confirmación. Distingue carga inicial, biblioteca vacía, búsqueda vacía y error con reintento.
- **Archivos.** `LibraryScreen.kt`, `LibraryRecoveryPanel.kt`, `LibraryRecoveryViewModel.kt`, `LibrarySortPreference.kt`, `LocalLibrarySortPreference.kt`, `SumiApplication.kt`, `MainActivity.kt`, `strings.xml`, `LibraryGridTest.kt` y `app/src/androidTest/scripts/verify-library-restart.ps1`. Se cotejaron los hashes de SPEC-002 y Room v1.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 con JDK 21.0.12. APK debug generada. |
| `testDebugUnitTest` | 18 aprobadas, 0 fallidas y 0 omitidas. Dos pruebas agrupan 53 casos JVM de SPEC-002. |
| `lintDebug` | 0 errores y 3 advertencias. Se mantienen `AndroidGradlePluginVersion` y `MissingApplicationIcon`. `UseKtx` señala la escritura de la preferencia. Se usa el retorno de `commit()` para mostrar errores de almacenamiento. |
| `connectedDebugAndroidTest` | 30 aprobadas, 0 fallidas y 0 omitidas en API 37. Las tres nuevas comprueban el cambio de una a dos columnas a 300 y 390 dp con portadas visibles, estados y acciones accesibles con destino táctil de 48 dp, y lectura de la preferencia al recrear su adaptador. Las pruebas de Room cubren los tres órdenes, búsqueda y desempate. |
| `pwsh -File app/src/androidTest/scripts/verify-library-restart.ps1 -Serial emulator-5562 -AdbPath <ruta-a-adb>` | 6 comprobaciones del selector SAF y 3 de recuperación aprobadas sin conexión. Se seleccionaron los tres órdenes, se restauró Z–A con un PID nuevo y se comprobó la búsqueda con mayúsculas mixtas. El borrado actualizó la cuadrícula y la búsqueda vacía. Limpiar la búsqueda volvió a mostrar el otro libro. |

Los registros de esta ejecución están en `.gradle/spec-003-t08/`. Se conservan los pendientes de API 23, las suites autónomas Android ajenas a los escenarios ejecutados y las mediciones con dispositivos y archivos representativos. La cuadrícula reduce las solicitudes a celdas visibles, pero el generador sigue revalidando el CBZ para comprobar su acceso y la identidad de la página incluso cuando la portada está en caché.

**Validación de T08, 16 de noviembre de 2026.** El usuario autorizó la validación de T08 y la implementación exclusiva de T09. Se cotejaron el resultado de Gradle y el registro del reinicio, incluidos los dos anchos, las portadas visibles, la búsqueda, la selección de los tres órdenes y la restauración del orden, la recuperación, el borrado y las acciones accesibles. La advertencia `UseKtx` se acepta porque la extensión devuelve `Unit`. El código conserva el `Boolean` de `commit()` para publicar el nuevo orden solo si se guardó y mostrar un error cuando falla.

## T09 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. El usuario autorizó validar T09 e implementar exclusivamente T10 el 16 de noviembre de 2026. La evidencia registrada a continuación satisface sus criterios de finalización.

- **CA-08, coordinación.** `ImportLibraryBatch` consume un `Flow` con un productor y un trabajador por lote. El canal suspende al productor al llenar las 32 plazas. Puede quedar un candidato ofrecido por el productor en un envío suspendido que todavía no ingresó al canal. El límite de 1000 se aplica antes de deduplicar. El resumen conserva solo contadores y causas agrupadas. Distingue pendientes conocidos, exceso conocido y descubrimiento incompleto.
- **CA-10, recursos compartidos.** Cada candidato usa `ImportIntoLibrary` y el mismo turno que las portadas. El turno se libera cuando termina la limpieza y antes de pasar al próximo candidato. `TemporaryCbzStore` aplica el presupuesto agregado de 2 GiB a los temporales propios y verifica el espacio disponible al copiar.
- **CA-10, cancelación y escritura.** Cancelar cierra la admisión, descarta la cola y deja el estado en cancelación mientras una lectura bloqueada conserva recursos. Antes de guardar y dentro de la transacción se comprueba la cancelación. La confirmación de Room incrementa el contador incluso si la cancelación impide devolver el resultado final del candidato. La transacción se revierte si detecta la cancelación antes de confirmar.
- **Archivos.** `ImportLibraryBatch.kt`, `ImportIntoLibrary.kt`, `LibraryRepository.kt`, `RoomLibraryRepository.kt`, `LibraryDao.kt`, `SumiApplication.kt`, `ImportLibraryBatchTest.kt`, `LibraryDatabaseTest.kt`, `LibraryBatchMemoryTest.kt` y `LibraryCoverTest.kt`.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 con JDK 21.0.12. APK debug generada. |
| `testDebugUnitTest` | 22 aprobadas, 0 fallidas y 0 omitidas. Las 4 nuevas comprueban contrapresión, un lote activo, el borde 1000/1001 antes de deduplicar, resumen parcial ante fallos y contabilización de un alta confirmada si se cancela la devolución del resultado. Dos pruebas agrupan 53 casos JVM de SPEC-002. |
| `connectedDebugAndroidTest` | 33 aprobadas, 0 fallidas y 0 omitidas en API 37. Las nuevas verifican reversión de una inserción cancelada antes de confirmar, medición de metadatos y cancelación de un lote con una lectura SAF bloqueada mientras espera una portada. Esta última comprobó un único temporal activo, cierre del lector, liberación de su permiso, cero aperturas del candidato en cola y una sola fila confirmada. También se verificó el presupuesto agregado con un residual y el rechazo de 2 GiB más un byte de longitud anunciada. |
| `lintDebug` | 0 errores y 3 advertencias ya registradas, `AndroidGradlePluginVersion`, `MissingApplicationIcon` y `UseKtx`. |
| `pwsh -File app/src/androidTest/scripts/verify-library-restart.ps1 -Serial emulator-5562 -AdbPath <ruta-a-adb>` | Código 0. Pasaron 6 comprobaciones del selector SAF y 3 de recuperación sin conexión. También se verificaron el reinicio, la búsqueda y el borrado observable. |

**Medición de metadatos.** Emulador `Medium_Phone_API_37.0`, lote sintético de 1000 candidatos únicos, ventana observable de 21 filas de Room y 5000 descriptores del primer CBZ validados con el lector real. El heap Java tras solicitar GC fue de 2 872 064 bytes antes del lote, 3 491 120 con la cola llena y 3 052 016 al concluir. La mayor diferencia observada fue de 619 056 bytes (0,59 MiB) frente al objetivo de 16 MiB. Las muestras incluyen el estado de la cola y de la consulta. El muestreo tras GC mide retención en esos puntos y deja pendientes el pico instantáneo, los dispositivos físicos y los metadatos del recorrido de carpetas.

Se cotejaron los hashes de SPEC-002 y del esquema Room v1. Los registros están en `.gradle/spec-003-t09/`. Quedan pendientes API 23 y mediciones con dispositivos, proveedores y archivos representativos. Una lectura bloqueada puede mantener ocupado el turno hasta que el proveedor devuelva el control.

## T10 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`.

- **Selector.** Se agregó `OpenCbzDocuments` usando `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, `EXTRA_ALLOW_MULTIPLE` y los mismos cuatro MIME de SPEC-002. El parser conserva todos los URI de `ClipData`, agrega `data` solo si no está ya presente, acepta un URI único en `data` y devuelve selección vacía al cancelar.
- **Integración.** La biblioteca ofrece una acción separada para varios CBZ. Los URI seleccionados se convierten en referencias opacas y se envían a `ImportLibraryBatch`; cada candidato reutiliza la validación CBZ, persistencia individual de permisos y escritura durable de T04. Los duplicados y errores se contabilizan por candidato; los éxitos confirmados sobreviven a fallos posteriores. Se presenta un resumen accesible al concluir el lote. El progreso detallado y la cancelación visible siguen asignados a T13.
- **Pruebas.** Se añadió `CbzMultiPickerTest`. Verifica `ClipData` con varios URI y repetidos, `data` solapado, respuesta de un URI y cancelación. Pasa los URI resultantes al ViewModel y comprueba que duplicados y un origen sin acceso se informen por separado. Con DocumentsUI comprobó una selección real de un URI en modo múltiple, la persistencia de su permiso y la conservación del resultado al cancelar.
- **T08 / `UseKtx`.** Se mantiene el `commit()` de `SharedPreferences`: la API devuelve un `Boolean` necesario para publicar el orden solo después de persistirlo y para reflejar fallos de almacenamiento. `SharedPreferences.edit {}` devuelve `Unit`, por lo que cambiarlo perdería la confirmación requerida; la advertencia de lint es deliberada.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --continue` | Código 0 con JDK 21.0.12 efectivo. `assembleDebug` pasó; `testDebugUnitTest` quedó `UP-TO-DATE` con 22 pruebas registradas, 0 fallos; `lintDebug` pasó. |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -I .gradle/spec-003-t06/daemon.init.gradle :app:connectedDebugAndroidTest --continue` | Código 0 en `Medium_Phone_API_37.0` / Android API 37. Se ejecutaron 34 pruebas: 34 aprobadas, 0 fallidas, 0 omitidas. Incluye la nueva prueba T10 y las regresiones instrumentadas de SPEC-002. |
| `lintDebug` | 0 errores y 3 advertencias conocidas: `AndroidGradlePluginVersion`, `MissingApplicationIcon` y `UseKtx`. No quedaron las advertencias transitorias de `PluralsCandidate`. La justificación de `UseKtx` está documentada en T08. |

La instrumentación inicial usó un `ClipData` simulado para el caso de varios URI. La comprobación del flujo real está registrada en la validación de T10. Quedan pendientes API 23, otros dispositivos y proveedores SAF y las regresiones autónomas Android. Los hashes de la documentación de SPEC-002 y del esquema Room v1 permanecen sin cambios.

**Validación de T09, 16 de noviembre de 2026.** El usuario autorizó validar T09 e implementar exclusivamente T10. Se revisó su evidencia de contrapresión, límites, medición de metadatos, recursos compartidos, cancelación y alta tardía. Las 22 pruebas JVM y las 33 instrumentadas de T09 pasaron; se registró `Validated`. La lectura bloqueante externa puede retener el turno hasta que el proveedor devuelva el control, como ya se documentó.

**Validación de T10, 16 de noviembre de 2026.** El usuario autorizó validar T10 e implementar exclusivamente T11. Se completó la comprobación pendiente con DocumentsUI en API 37 mediante ADB. En el proveedor de almacenamiento interno, un lote real de cuatro elementos produjo dos altas, un duplicado de un documento ya guardado y un fallo por contenido inválido. Las dos altas seguían visibles en un proceso nuevo y `adb shell dumpsys activity permissions` confirmó un permiso persistente de lectura por documento. El caso de URI repetido dentro del mismo `ClipData` sigue cubierto por la prueba instrumentada de T10. La prueba JVM de T09 cubre el fallo de escritura aislado dentro del lote. En un primer ensayo Downloads presentó dos `documentId` distintos para un mismo archivo y quedaron dos filas, tal como permite la política de identidad SAF del plan.

## T11 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. El usuario autorizó exclusivamente T11 junto con la validación de T10.

- **CA-02.** La identidad sigue siendo el par `authority` y `documentId`. Al volver a elegir por URI directo un libro importado mediante árbol, Room devuelve un duplicado y conserva el ID, el URI y la concesión de la fila existente.
- **CA-05.** La biblioteca registra la concesión del árbol en la columna de Room existente. El mismo turno coordina importación, portada, comprobación de acceso y eliminación. El permiso administrado se conserva mientras quede una fila o una lectura activa. Tras la última referencia y el cierre de la lectura se libera. La prueba de titularidad incierta conserva un permiso persistido antes de que la operación de biblioteca lo usara.
- **CA-09, acceso durable.** Los adaptadores construyen el URI con `DocumentsContract.buildDocumentUriUsingTree` y comprueban la concesión persistida exacta del árbol. La apertura en el proveedor comprueba el acceso efectivo y la pertenencia, incluso si el documento externo tiene la misma autoridad, un ID con prefijo parecido y un permiso directo propio. Se verificaron rechazo de concesión no persistible sin altas, reapertura en un proceso nuevo sin conexión y pérdida de acceso tras revocación. El lector CBZ y la copia temporal siguen aplicando los límites de SPEC-002.
- **Archivos.** Se añadieron `SafTreeDocument.kt` y `SafTreeLibraryTest.kt`. Se actualizaron `SafDocumentSource.kt`, `SafLibraryDocumentMetadataSource.kt`, `SafLibraryMaintenance.kt`, `RoomLibraryRepository.kt`, `PickerFixtureProvider.java`, `SafTestProvider.java`, `SafDocumentSourceTest.kt` y este documento. Se verificó el SHA-256 del esquema Room v1 y de los cuatro documentos de SPEC-002 frente a la evidencia previa.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 con JDK 21.0.12 efectivo. Compilación debug exitosa. |
| `testDebugUnitTest` | 22 ejecutadas y aprobadas, 0 fallidas y 0 omitidas. Dos pruebas agrupan las 53 comprobaciones JVM de SPEC-002. |
| `lintDebug` | 0 errores y 3 advertencias conocidas (`AndroidGradlePluginVersion`, `MissingApplicationIcon`, `UseKtx`). El intento inicial falló por tres referencias del fixture sin resolver y una excepción interna de lint. Tras corregir el fixture se repitió el comando con un trabajador. |
| `connectedDebugAndroidTest` | 37 ejecutadas y aprobadas en API 37, 0 fallidas y 0 omitidas. Incluye 3 pruebas de T11, 2 regresiones instrumentadas de SPEC-002 y el selector múltiple de T10. |
| `adb shell am instrument -r -w -e phase <fase> com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` | En API 37 sin conexión, `tree-prepare` persistió la concesión e importó el descendiente. Tras `adb shell am force-stop com.agustin.sumi`, `tree-reopen` reabrió y validó el CBZ y liberó la última concesión administrada. Pasaron también las 6 comprobaciones de `picker` y las 3 de `recovery-picker`. |

La instrumentación de árbol concede el permiso desde un `DocumentsProvider` controlado. El selector, el recorrido y la reselección del árbol se verifican en T12. Quedan pendientes la ejecución en API 23 y la verificación con otros proveedores. Los registros Gradle están en `.gradle/spec-003-t11/` y los informes en `app/build/`.

**Validación de T11, 16 de noviembre de 2026.** El usuario autorizó validar T11 e implementar exclusivamente T12. Se cotejaron la reapertura en proceso nuevo, revocación, rechazo de URI externos, deduplicación, titularidad y liberación de concesiones, y el registro Gradle con 37 pruebas instrumentadas aprobadas. Se conservan los pendientes de compatibilidad.

## T12 — Evidencia del 16 de noviembre de 2026

**Estado.** `Validated`. El usuario autorizó validar T12 el 16 de noviembre de 2026 junto con la implementación de T13. La evidencia siguiente cubre sus criterios de recorrido, límites, recuperación por identidad y cancelación.

- **CA-06.** Los libros de una carpeta inaccesible permiten volver a elegir la carpeta o el archivo. La reselección comprueba la identidad SAF dentro del grupo afectado y vuelve a validar el CBZ antes de actualizar cada origen. Conserva ID, título y fecha, e invalida la miniatura previa del libro recuperado. El resultado indica cuántas entradas se recuperaron y cuántas quedaron sin recuperar. Los documentos nuevos no se agregan en esta operación.
- **CA-09.** El selector usa `ACTION_OPEN_DOCUMENT_TREE` y comprueba la oferta de lectura persistente. El adaptador mantiene la concesión activa hasta que termina el lote, incluidas las validaciones pendientes. Construye los URI con `DocumentsContract` y reutiliza el importador de SPEC-002 para validar el contenido y comprobar la pertenencia efectiva. Se admiten `.cbz` y `.zip`, sin distinguir mayúsculas y con MIME genérico. Se omiten ocultos, archivos ajenos y ramas inaccesibles, repetidas o cíclicas. [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
- **CA-10, parte de T12.** El recorrido iterativo retiene un solo cursor del proveedor a la vez y aplica 10 niveles debajo de la raíz, 10 000 filas y 1000 candidatos antes de deduplicar. Entrega candidatos al coordinador de T09, que conserva su cola y su arbitraje con portadas. La cancelación detiene la admisión, señaliza la consulta al proveedor, cierra el cursor y espera la limpieza del trabajo activo antes de soltar la concesión. Se conservan las altas confirmadas. El estado registra omisiones, límites y exploración incompleta para el resumen de T13.
- **Archivos nuevos.** `LibraryTreeSource.kt`, `SafLibraryTreeSource.kt`, `RecoverLibraryTree.kt`, `OpenLibraryTree.kt` y `SafTreeDiscoveryTest.kt`.
- **Archivos actualizados.** `MainActivity.kt`, `SumiApplication.kt`, `LibraryDao.kt`, `RoomLibraryRepository.kt`, `SafLibraryMaintenance.kt`, `SafTreeDocument.kt`, `ImportIntoLibrary.kt`, `ImportLibraryBatch.kt`, `LibraryViewModel.kt`, `LibraryRecoveryViewModel.kt`, `LibraryScreen.kt`, `LibraryRecoveryPanel.kt`, `strings.xml`, `PickerFixtureProvider.java`, `SafTestProvider.java`, `SafTreeLibraryTest.kt` y este documento.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 en la ejecución final con JDK 21.0.12 efectivo. Se generó la APK debug. El intento inicial llegó a la instrumentación sin un dispositivo conectado y se repitió tras iniciar el emulador existente en modo de solo lectura. |
| `testDebugUnitTest` | 22 ejecutadas y aprobadas, 0 fallidas y 0 omitidas. Dos agrupan las 53 comprobaciones JVM de SPEC-002. |
| `lintDebug` | 0 errores. Las 3 advertencias conocidas son `AndroidGradlePluginVersion`, `MissingApplicationIcon` y `UseKtx`. |
| `connectedDebugAndroidTest` | 42 ejecutadas y aprobadas en API 37, 0 fallidas y 0 omitidas. Las 5 de T12 cubren contrato del selector y cancelación del resultado, recorrido con un proveedor controlado, límites, recuperación parcial por identidad y cancelación del recorrido con liberación segura tras el último uso. La ejecución incluye las regresiones instrumentadas de SPEC-002 y los escenarios de T09 y T11. |
| DocumentsUI y ADB en API 37 | Se seleccionó una carpeta sintética del proveedor de almacenamiento interno. El lote real guardó dos archivos válidos en distintos niveles, rechazó uno inválido y reportó cero pendientes. `adb shell dumpsys activity permissions` confirmó un permiso de lectura persistente para el árbol. Los dos títulos seguían visibles tras `adb shell am force-stop com.agustin.sumi` y el relanzamiento con un PID distinto. |

Se cotejaron los SHA-256 de los cuatro documentos de SPEC-002 y del esquema Room v1. Los registros de ejecución están en `.gradle/spec-003-t12/`. Quedan pendientes API 23, pruebas con otros proveedores y mediciones con carpetas y dispositivos representativos. Un proveedor que no atiende `CancellationSignal` puede demorar el cierre del cursor y la liberación de recursos hasta que devuelva el control.

**Validación de T12.** La autorización y el cotejo de evidencia se registran en el estado anterior; la validación conserva como pendientes las comprobaciones de compatibilidad indicadas arriba.

## T13 — Revisión de validación del 16 de noviembre de 2026

**Revisión inicial.** Pendiente de validación; la revisión detectó que el ViewModel no publicaba progreso intermedio. La corrección, sus pruebas y la validación autorizada se registran en «Corrección de T13».

- La ejecución previa de `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con código 0 en JDK 21.0.12 y API 37. Los XML registran 23 pruebas JVM y 42 instrumentadas, con cero fallos, errores u omisiones. Lint registró 0 errores y 7 advertencias. El log está en `.gradle/spec-003-t13/gradle-verified2.log`. Estas cifras corresponden a T13.
- **Bloqueo de RF-09 y T13.** `LibraryViewModel.observeBatch` publica el valor inicial y espera el primer estado terminal. Las actualizaciones intermedias del coordinador no llegan a la pantalla. El indicador determinado no refleja el avance mientras se procesan archivos, el contador de carpeta queda en el valor inicial y el botón conserva el estado anterior durante la limpieza de una lectura bloqueada. Las pruebas existentes de cancelación y recibos de escritura cubren el coordinador. Los resultados disponibles no acreditan que su progreso se publique en pantalla.
- Para cerrar el bloqueo, falta publicar el progreso intermedio y el estado de cancelación en la interfaz, y verificar ambos durante una operación activa.

### Corrección de T13

**Estado.** `Validated`. El 16 de noviembre de 2026 el usuario autorizó validarla junto con T14. Se cotejaron las dos pruebas de progreso intermedio, la regresión SAF de lectura bloqueada y las 44 pruebas instrumentadas aprobadas. La evidencia cubre la espera visible de limpieza, la conservación del alta confirmada y el descarte de resultados tardíos.

- **Causa raíz.** El predicado de `first` en el ViewModel ignoraba las emisiones `RUNNING` y `CANCELLING`. El coordinador ya publicaba los contadores y esperaba el fin de la limpieza antes de emitir un estado terminal.
- **Corrección.** El ViewModel publica las emisiones activas del flujo existente y cierra su observación con el primer estado terminal. La pantalla muestra preparación, avance según archivos procesados, avance indeterminado para carpetas y espera de limpieza con el botón de cancelar deshabilitado. El resultado de cancelación se muestra una sola vez, con el conteo confirmado y los pendientes conocidos. El resumen distingue un fallo al abrir una carpeta de una carpeta sin candidatos y usa los mensajes localizados para las causas de rechazo.
- **Pruebas agregadas.** `LibraryGridTest` conecta Compose, el ViewModel, T09 y Room. Una prueba detiene la segunda lectura después de la primera alta y comprueba en pantalla el avance de 1 de 3 y la espera de limpieza antes de liberar la lectura. Tras liberarla, comprueba una sola alta persistida, dos pendientes y que el tercer archivo no se abrió. La otra controla el descubrimiento de carpeta para verificar preparación, avance indeterminado con un archivo procesado antes del cierre, finalización y fallo de acceso. La regresión SAF existente de `LibraryCoverTest` comprueba el cierre del descriptor, la limpieza del temporal y la liberación del turno compartido con las portadas.
- **Archivos modificados.** `LibraryViewModel.kt`, `LibraryScreen.kt`, `strings.xml`, `LibraryGridTest.kt` y este documento.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --continue` | Código 0 en la ejecución final con JDK 21.0.12. |
| `assembleDebug` | APK debug generada. |
| `testDebugUnitTest` | 23 aprobadas, 0 fallidas y 0 omitidas. La ejecución final reutilizó el resultado del primer intento. Incluye las regresiones JVM de SPEC-002. |
| `lintDebug` | 0 errores y 4 advertencias. `AndroidGradlePluginVersion`, `PluralsCandidate`, `MissingApplicationIcon` y `UseKtx`. |
| `connectedDebugAndroidTest` | 44 aprobadas en API 37, 0 fallidas y 0 omitidas. Incluye las dos pruebas nuevas, la lectura SAF bloqueada de T09 y las regresiones de SPEC-002. El primer intento falló al consultar el estado habilitado del nodo de texto del botón. La aserción pasó al verificar la acción clicable en el árbol de accesibilidad. |

Los registros de ambos intentos están en `.gradle/spec-003-t13/recheck/`. Los SHA-256 de SPEC-002 y Room v1 coinciden con los anteriores a la corrección.

## T14 — Verificación integral

**Estado.** `Validated` el 16 de noviembre de 2026 por autorización explícita de cierre de SPEC-003, tras cotejar la cobertura integral y los resultados registrados aquí.

| Criterio | Evidencia de T14 |
| --- | --- |
| CA-01 | `verify-library-restart.ps1` abrió DocumentsUI sin red para importar un CBZ y un ZIP desde el proveedor controlado. Verificó cancelación sin cambiar el resultado anterior y un archivo no disponible. La suite de integración comprobó que un archivo inválido no llega a Room. En Descargas se comprobó importación, concesión persistida y portada visible tras relanzar el proceso. |
| CA-02 | Las suites de Room, lote y árbol verificaron identidad compuesta, deduplicación concurrente, después de reabrir y entre URI directo y de árbol. En DocumentsUI se volvió a elegir una URI persistida para comprobar el duplicado en un lote real. |
| CA-03 | Se probaron los tres órdenes y su desempate en Room. Sin red, el script cambió de Recientes a A–Z y Z–A, verificó Z–A en un proceso nuevo y buscó con mayúsculas mixtas. |
| CA-04 | `LibraryCoverTest` verificó la primera página natural, dimensiones, proporción, respaldo ante revocación, regeneración, desalojo y ajuste de memoria. En Descargas un PNG generado de 32 × 48 px produjo una portada visible y accesible después de relanzar Sumi sin red. Se usó este PNG para la comprobación de portada porque los archivos mínimos de lote eran fixtures de importación y mostraban el respaldo. |
| CA-05 | Las pruebas instrumentadas verificaron borrado durable, reimportación, caché y liberación al quitar la última entrada con permiso compartido. En Descargas se quitó el libro de prueba, se observó la eliminación de su portada y cesó la concesión persistida. El original conservó su SHA-256. |
| CA-06 | El script ejecutó tres comprobaciones del selector real ante revocación, cancelación, selección en conflicto y recuperación con el mismo ID. Las pruebas de documentos y árbol cubrieron ausencia, errores transitorios y reselección parcial por identidad. |
| CA-07 | `LibraryGridTest` comprobó dos anchos, portadas visibles, estados vacíos, carga, error, acciones accesibles y progreso antes de terminar. |
| CA-08 | Lote real de tres selecciones desde almacenamiento interno sin red. El resumen mostró 1 alta nueva, 1 duplicado de un documento ya persistido, 1 ZIP inválido y 0 pendientes. Se conservó la concesión de lectura de los dos válidos. El ZIP inválido quedó sin permiso persistido. Las suites también cubrieron un URI en modo múltiple, dos altas en un lote y fallo aislado de escritura. |
| CA-09 | El selector real de carpetas guardó un CBZ en la raíz y un ZIP anidado, rechazó un ZIP inválido, omitió 2 elementos y reportó 0 pendientes. Se observó la concesión de árbol de solo lectura persistida y ambos títulos en un proceso nuevo sin red. Las fases `tree-prepare` y `tree-reopen` comprobaron reapertura, validación del descendiente y liberación de la concesión en un proveedor controlado. La suite cubrió MIME genérico, ramas ilegibles, URI ajenos y permisos revocados o no persistibles. |
| CA-10 | Se verificaron cola de 32, máximo de 1000 candidatos, 10 niveles, 10 000 filas, ciclos, espacio simulado insuficiente y presupuestos descritos abajo. Las pruebas de UI verificaron progreso intermedio, omisiones y espera de limpieza. La prueba SAF de lectura bloqueada conservó un alta confirmada, descartó el resultado tardío y el candidato en cola, y comprobó el cierre del descriptor, la eliminación del temporal y la liberación del turno. |
| RNF-03 | JDK 21.0.12 efectivo. JUnit, AndroidX Test con `AndroidJUnitRunner` y `room-testing` 2.8.5 desde Gradle. Se ejecutaron las regresiones de SPEC-002 integradas en ambas suites. |

### Comandos y resultados

- `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 -I .gradle/spec-003-t06/daemon.init.gradle :app:assembleDebug :app:testDebugUnitTest --rerun :app:lintDebug :app:connectedDebugAndroidTest --continue` terminó con código 0. La APK estaba al día. Los XML registraron 23 pruebas JVM y 44 instrumentadas en el emulador API 37, todas aprobadas, sin fallos ni omisiones. Dos de las pruebas JVM agrupan 53 comprobaciones de archivo y temporales de SPEC-002. Las dos regresiones instrumentadas de SPEC-002 verifican acceso a documentos y repositorio CBZ.
- `app/src/androidTest/scripts/verify-library-restart.ps1 -Serial emulator-5562 -AdbPath <ruta-a-adb>` terminó con código 0. Ejecutó 6 comprobaciones del selector, 3 de recuperación y verificó orden persistido, búsqueda y borrado. Las fases adicionales `tree-prepare` y `tree-reopen` aprobaron una comprobación cada una por `adb shell am instrument`, con `adb shell am force-stop com.agustin.sumi` entre fases. Estos conteos quedan fuera de las cifras de Gradle.
- Para los flujos manuales se usaron `adb push`, `adb shell uiautomator dump`, `adb shell am force-stop`, `adb shell am start -W`, `adb shell dumpsys connectivity` y `adb shell dumpsys activity permissions`. Se verificó `Active default network: none` durante los flujos manuales. Los proveedores ejercitados fueron almacenamiento interno, Descargas y los proveedores controlados de pruebas.
- Los cuatro SHA-256 documentados de SPEC-002 y el SHA-256 del esquema exportado de Room v1 coinciden con la evidencia anterior. Se cotejó el contrato actual del importador con SPEC-002 y ADR-003, incluido el uso de referencias opacas, el retorno tras la limpieza y la propagación de cancelación. T14 solo modificó este documento.

### Presupuestos y mediciones

| Escenario | Resultado y alcance |
| --- | --- |
| Heap de metadatos | Medición en API 37 con carga sintética de 1000 candidatos, 5000 descriptores activos y ventana Room de 21 entradas. Heap Java muestreado tras GC de 2 901 312 bytes al inicio, 3 515 712 con cola llena y 3 084 800 al terminar. Mayor delta observado de 614 400 bytes (0,59 MiB), frente al objetivo de 16 MiB. |
| Decodificación y caché RAM | Con imágenes sintéticas JPEG, PNG y WebP se observaron 1 244 288 bytes de bitmaps simultáneos en cada caso. El mayor incremento de heap nativo muestreado hasta el escalado fue 1 256 768 bytes. La prueba de caché alcanzó 32 MiB y cayó a 8 MiB bajo presión simulada. |
| Caché de disco | Tope de producción comprobado en código de 256 MiB. Desalojo medido con una cuota inyectada de 628 bytes y retención final de 419 bytes. En el flujo real de Descargas, `du -b` informó 4241 bytes en el directorio de portadas antes de borrar y 4096 después, incluido el directorio. |
| Temporales y espacio disponible | Pruebas de borde del presupuesto agregado de 2 GiB con archivos y tamaños declarados de prueba. Las regresiones simulan falta de espacio antes de copiar y durante la copia. La lectura SAF bloqueada llegó a crear un temporal real que se eliminó al liberar la lectura. |

### Advertencias de lint

| Aviso | Clasificación |
| --- | --- |
| `AndroidGradlePluginVersion` | Mantenimiento de dependencias. Lint señala una versión posterior a la 9.3.1 aprobada para este proyecto. |
| `PluralsCandidate` | Localización. El contador de carpeta usa «procesados» para cualquier cantidad, incluido 1. |
| `MissingApplicationIcon` | Presentación del launcher. El manifiesto aún carece de un icono explícito. |
| `UseKtx` | Sugerencia de estilo. `LocalLibrarySortPreference` usa `commit()` para devolver si la preferencia se guardó correctamente. La sugerencia debe preservar ese resultado y el guardado síncrono. |

Lint terminó con 0 errores y 4 advertencias. Los registros locales están en `.gradle/spec-003-t14/` y los informes en `app/build/`.

**Pendientes no bloqueantes.** El único AVD y la única imagen de sistema instalados eran API 37. Quedan pendientes API 23, otros proveedores SAF externos, mediciones con archivos representativos y dispositivos físicos, el pico instantáneo de memoria durante desplazamiento prolongado, la saturación de disco real y el llenado de la cuota de producción de portadas. La repetición autónoma de ZIP y temporales directamente en Android también queda pendiente; las regresiones de SPEC-002 sí se ejecutaron mediante Gradle. Estas verificaciones adicionales no son criterios obligatorios incumplidos. El usuario autorizó el cierre condicionado de SPEC-003 el 16 de noviembre de 2026.
