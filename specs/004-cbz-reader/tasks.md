# SPEC-004 — Tareas propuestas

**Estado.** T01–T08 están `Validated`. SPEC-004 quedó `Validated` el 17 de noviembre de 2026 por autorización explícita del usuario, tras revisar la evidencia integral. La especificación depende de SPEC-002 y SPEC-003 `Validated`. Ver criterios en [spec.md](spec.md#criterios-de-aceptación-propuestos) y decisiones técnicas en [plan.md](plan.md).

| ID | Tarea | Dependencias | Criterios | Finalización verificable |
| --- | --- | --- | --- | --- |
| T01 | Definir modelos/contratos de sesión, página, dirección, progreso y errores. | Aprobación SPEC-004. | CA-01, CA-04 y CA-05. | Dominio Kotlin puro; pruebas JVM de secuencia natural, sentido LTR/RTL, posición confirmada y estados de error. |
| T02 | Implementar índice estructural y acceso individual a entradas CBZ mediante SAF. | T01. | CA-02, CA-06 y CA-07. | Pruebas seekable/secuencial, CRC/tamaño, archivo cambiado/corrupto, límites y cancelación. Verificar copia temporal secuencial, cuota agregada de 2 GiB, espacio real y limpieza. Medir hasta 12 bytes descomprimidos por candidato para firmas, aparte de la lectura comprimida. Comprobar lectura integral y CRC solo al solicitar la página. |
| T03 | Implementar decodificación de una página y zoom básico con memoria limitada. | T01–T02. | CA-03, CA-04. | Instrumentar JPG/PNG/WebP, muestreo, escala 3x, liberación y presión de RAM; medir objetivo 32 MiB/techo provisional 64 MiB en API 23/37 disponible. No precarga ni región. |
| T04 | Persistir página confirmada y dirección por libro mediante migración Room. | T01. | CA-05, CA-08. | Migración no destructiva v1→v2 conserva entradas/IDs/permisos; prueba de DB v1, escritura, restauración por reinicio y esquema reproducible. No modificar v1 en forma retroactiva. |
| T05 | Coordinar sesión, SAF, errores, cancelación y ciclo de vida desde ViewModel. | T02–T04. | CA-01, CA-06 a CA-08. | Pruebas de permiso revocado, ausencia, lectura bloqueada, cierre, resultado tardío, recreación, segundo plano, relanzamiento y limpieza de temporales. |
| T06 | Construir pantalla de una página con navegación horizontal y zoom accesibles. | T01, T03 y T05. | CA-04 y CA-09. | Pruebas Compose de estados, gesto LTR/RTL, botones anterior/siguiente, zoom 1x–3x, etiquetas/roles/targets 48 dp y dos tamaños de ventana. |
| T07 | Conectar biblioteca y lector en el flujo de navegación actual. | T05–T06. | CA-01 y CA-08. | Abrir por ID estable y volver; la biblioteca observa cambios; apertura de ID eliminado y errores no rompe la app. Sin nueva dependencia de navegación no aprobada. |
| T08 | Verificación integral y registro de evidencia. | T01–T07. | CA-01 a CA-10. | Ejecutar comandos Gradle obligatorios, suites regresivas de SPEC-002/003, API 23/37 si disponible, revisar lint, recursos, migración, memoria/temporales y ciclo de vida. Registrar conteos, dispositivo, límites y pendientes; no validar sin aprobación del usuario. |

No comenzar ninguna tarea hasta que SPEC-004 esté `Approved` y el usuario autorice la tarea concreta. La migración Room requiere conservar el esquema v1 y verificar la migración con datos existentes.

## T01 — Evidencia de implementación del 16 de noviembre de 2026

**Estado.** `Validated` el 16 de noviembre de 2026, por autorización explícita del usuario en el mensaje «Autorizo validar T01 e implementar exclusivamente T02 de SPEC-004». Se verificaron los contratos Kotlin puros, los resultados y errores tipados, el uso de las identidades existentes y las reglas de posición y dirección. Las 10 pruebas JVM del lector se ejecutaron nuevamente y pasaron.

- **Modelos.** Se añadieron `ReaderSession`, `ReaderSessionId`, `ReaderPage`, `ReaderPosition`, `ReaderDirection`, `ReaderOptions`, `ReaderZoom` y `ReaderProgress`. Reutilizan `LibraryEntryId`, `CbzPageId` y `CbzPageDescriptor`. La sesión valida páginas no vacías, índices contiguos e identidades únicas; posición guardada inexistente vuelve a la primera página.
- **Reglas.** `next`/`previous` siguen el orden natural y devuelven `null` en los extremos o ante una posición que no pertenece a la sesión. En LTR el gesto a la izquierda avanza; en RTL avanza a la derecha. El progreso conserva la dirección por libro y no persiste zoom.
- **Contratos.** `ReaderRepository` abre/cierra sesiones con identidad propia por `LibraryEntryId` y expone solo la entrada solicitada a través de un `ReaderPageInput` acotado por callback, con resultado tipado y sin exigir `ByteArray` de página. El repositorio conserva la propiedad del recurso, cierra ante fallos de apertura, ofrece cierre idempotente y completa CRC antes del éxito. `ReaderProgressRepository` carga progreso predeterminado LTR o persiste la última página confirmada. La cancelación se propaga, no se convierte en resultado exitoso.
- **Pruebas añadidas.** `ReaderSessionTest` cubre restauración, límites de navegación, posición inválida, dirección de gesto y rango de zoom. `ReaderProgressTest` cubre el valor inicial y la restauración de una página guardada o retirada.
- **Archivos modificados.** `ReaderSession.kt`, `ReaderProgress.kt`, `ReaderRepository.kt`, `ReaderSessionTest.kt`, `ReaderProgressTest.kt` y este documento.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 --version` | Correcto. Wrapper Gradle 9.5.0; JVM/daemon JDK 21.0.12. |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | Código 0, `BUILD SUCCESSFUL` en la ejecución final. Se ejecutó con GRADLE_USER_HOME local del proyecto y permiso de loopback para el daemon. |
| `:app:assembleDebug` | Correcto; APK debug generada. |
| `:app:testDebugUnitTest` | 33 aprobadas, 0 fallidas, 0 omitidas. Incluye las 10 pruebas previas de consulta de biblioteca, 13 de importación/repositorio, 2 regresiones JVM de SPEC-002 y las 10 nuevas pruebas del lector. |
| `:app:lintDebug` | 0 errores y 4 advertencias existentes: `AndroidGradlePluginVersion`, `PluralsCandidate`, `MissingApplicationIcon` y `UseKtx`. No se modificaron para silenciarlas. |
| Esquema Room v1 | SHA-256 sin cambio: `9C09516097D8DB532725B82B46B17C02E112E16D3672454D24769D5B74FA2D96`. |
| `git diff --check` | Correcto. |

Un intento Gradle inicial sin permiso de loopback no pudo conectarse al daemon. Un intento posterior descubrió una importación faltante en el test de progreso; se corrigió antes de la ejecución final satisfactoria. La implementación de T01 requirió pruebas JVM porque contiene contratos y lógica Kotlin pura.

## T02 — Avance y bloqueo del 16 de noviembre de 2026

**Bloqueo original en CA-02.** El índice estructural conoce rutas, tamaños, CRC y offsets. El formato JPG/PNG/WebP exigido por `CbzPageDescriptor` se determina por la firma del contenido según SPEC-002. Room v1 guarda la referencia y el ID de portada, sin un índice de páginas verificadas. Para un ZIP DEFLATE no era posible distinguir una imagen de un auxiliar con la prohibición original de descomprimir bytes de entradas no solicitadas. El ajuste autorizado se registra abajo.

- **CA-02, avance:** `CbzArchiveReader.index` reutiliza `ZipStructure`, normalización, orden natural e identidad de SPEC-002. Valida estructura y límites agregados declarados sin abrir decodificadores. `openPage` abre una sola entrada STORED o DEFLATE, comprueba firma y cota de imagen, mide tamaño y CRC reales y completa la verificación aunque el consumidor lea solo un prefijo.
- **CA-06, avance:** el índice permite comparar identidad, offsets, tamaños y CRC del ZIP reabierto. Una prueba rechaza una página alterada sin invalidar la lectura de una página vecina intacta. Falta aplicar estas comprobaciones a una sesión con la referencia vigente de biblioteca y devolver errores de dominio al llamador.
- **CA-07, avance:** el flujo de prueba integra el parser con `SafDocumentSource`, `TemporaryCbzStore` y `CbzWorkGate` por operación. La prueba de fuente bloqueada comprueba ausencia de resultado tardío y que el turno sigue ocupado hasta que se cierra el descriptor y se borra el temporal. Falta conectar la copia de vida de sesión y su cierre al repositorio.
- **Archivos de T02:** `CbzArchiveReader.kt`, `CbzPages.kt`, `CbzPageAccess.kt`, `CbzPageAccessTest.kt`, `SafCbzPageAccessTest.kt` y este documento.

| Verificación | Resultado |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | `BUILD SUCCESSFUL` con JDK 21.0.12. Gradle ejecutó 39 pruebas JVM, incluidas 6 nuevas de T02. Cero fallidas u omitidas. Lint registró 0 errores y 4 advertencias preexistentes. |
| `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:connectedDebugAndroidTest` | `BUILD SUCCESSFUL` en API 37. Gradle ejecutó 46 pruebas, incluidas 2 nuevas de SAF (acceso directo/secuencial y cancelación bloqueada). Cero fallidas u omitidas. |
| `git diff --check` y SHA-256 del esquema Room v1 | Correcto. SHA-256 de v1 coincide con la evidencia de T01. |

Las suites iniciales de Gradle incluyeron las regresiones existentes de importación y biblioteca. Los límites se comprobaron con fixtures y cuotas reducidas, y la cancelación con un proveedor SAF controlado.

## T02 — Autorización y cierre de implementación del 16 de noviembre de 2026

El usuario autorizó el ajuste y el trabajo de T02 mediante el mensaje «Autorizo ajustar CA-02 de SPEC-004 e implementar exclusivamente el cierre pendiente de T02». [CA-02](spec.md#criterios-de-aceptación-propuestos) limita la inspección de firmas a 12 bytes descomprimidos por candidato. La verificación completa se aplica solo a la página solicitada.

- **CA-02.** El índice conserva validación estructural, orden e identificadores del importador. La prueba STORED y DEFLATE produjo 10, 12, 8 y 12 bytes de salida para cuatro candidatos, incluido un auxiliar, y omitió el directorio. En el candidato grande, STORED leyó 12 bytes de la carga y DEFLATE leyó menos que su carga comprimida total. Se registra cada contador por separado. Un archivo menor al tope puede caber entero en el prefijo. El indexador no drena entradas ni calcula sus CRC.
- **CA-06.** La apertura rechaza ZIP incoherentes y errores de descompresión observados en el prefijo. Al solicitar una página, el repositorio valida firma, límite, tamaño real y CRC antes de devolver éxito. Compara el origen SAF vigente y el índice seekable, o verifica disponibilidad y revisión declarada si usa un temporal. Distingue origen ausente, acceso revocado, archivo inválido y página corrupta.
- **CA-07.** La copia del contenedor reutiliza la cuota, la verificación de espacio y la recuperación de huérfanos del almacén compartido. Ocupa el único cupo hasta el cierre. El turno de trabajo se libera entre solicitudes. El cierre cancela el trabajo activo y elimina la copia tras finalizar el uso. Se prueba además la cancelación de una apertura SAF bloqueada sin devolución de sesión.
- **Archivos de esta etapa.** `CbzArchiveReader.kt`, `CbzPageAccess.kt`, `LocalReaderRepository.kt`, `SafDocumentSource.kt`, `TemporaryCbzStore.kt`, `SumiApplication.kt`, `CbzPageAccessTest.kt`, `SafCbzPageAccessTest.kt`, `SafTestProvider.java` y los tres documentos de SPEC-004.

**Verificación final.** `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con código 0 y `BUILD SUCCESSFUL` usando JDK 21.0.12. Un intento anterior falló por sintaxis del fixture incompatible con el nivel de fuente Java 11. Se corrigió el fixture antes de ejecutar la suite final.

| Tarea Gradle | Resultado |
| --- | --- |
| `:app:assembleDebug` | APK de depuración generado. |
| `:app:testDebugUnitTest` | 44 aprobadas, 0 fallidas y 0 omitidas. Incluye 5 pruebas JVM nuevas sobre el avance anterior y las regresiones de SPEC-002/003. |
| `:app:lintDebug` | 0 errores y 4 advertencias preexistentes (`AndroidGradlePluginVersion`, `PluralsCandidate`, `MissingApplicationIcon`, `UseKtx`). |
| `:app:connectedDebugAndroidTest` | 50 aprobadas en el emulador API 37, 0 fallidas y 0 omitidas. Incluye 4 pruebas nuevas de sesión y las regresiones de SPEC-002/003. |

`git diff --check` pasó. El SHA-256 del esquema Room v1 coincide con la evidencia de T01.

**Validación.** T02 quedó `Validated` el 16 de noviembre de 2026 por autorización explícita del usuario en el mensaje «Autorizo validar T02 e implementar exclusivamente T03 de SPEC-004». La revisión confirmó la evidencia de CA-02, CA-06 y CA-07 correspondiente a esta tarea, incluidas las regresiones de importación y biblioteca.

**Límites de la evidencia.** Un proveedor secuencial que omite o no actualiza el tamaño y la fecha puede ocultar cambios del original hasta reabrir la sesión. El tope de 12 bytes mide salida descomprimida. Los bytes comprimidos necesarios dependen del flujo dentro de los límites del ZIP. Las pruebas de cuota y espacio usan valores reducidos. Quedan pendientes otros proveedores SAF y API 23.

## T03 — Decodificación nativa y presupuesto de bitmaps

Autorizada por el usuario en el mensaje «Autorizo validar T02 e implementar exclusivamente T03 de SPEC-004» del 16 de noviembre de 2026.

- **CA-03.** `NativeReaderRenderer` consulta dimensiones con `BitmapFactory` y decodifica en I/O con `inSampleSize`. Las dos lecturas corresponden a la misma página y pasan por el repositorio verificado de T02. La primera verifica la entrada al consultar dimensiones. La segunda verifica la entrada antes de publicar el bitmap. Esto evita guardar una copia expandida y supone dos verificaciones de ZIP/CRC por página. Una sola decodificación puede estar activa. Se reciclan el bitmap sustituido y los candidatos corruptos o cancelados.
- **CA-04, parte de T03.** El resultado conserva proporción al ajustar al área disponible. La geometría usa el mismo bitmap entre 1x y 3x. Los controles y la pantalla corresponden a T06.
- **Medición.** El estimador usa dimensiones muestreadas, margen por píxel y alineación de fila para mantenerse en el objetivo de 32 MiB y en el presupuesto restante del techo de 64 MiB. Se registra `Bitmap.allocationByteCount` durante la decodificación, al publicar y en las consultas de memoria, junto con `CoverCache.retainedBytes`. La cifra combina bitmaps del lector y portadas retenidas. Quedan fuera buffers internos del codec, texturas GPU, portadas retenidas por vistas tras su desalojo y RSS total del proceso.
- **Archivos.** `NativeReaderRenderer.kt`, `ReaderBitmapBudget.kt`, ensamblado en `SumiApplication.kt`, `ReaderBitmapBudgetTest.kt`, `NativeReaderRendererTest.kt`, `SafCbzPageAccessTest.kt` y este documento.

| Medición en emulador API 37 | Bytes | MiB |
| --- | ---: | ---: |
| Pico del lector, PNG sintético de 4096 × 3072 muestreado a 2048 × 1536 | 12 582 912 | 12 |
| Portadas retenidas, 32 bitmaps de 512 × 512 | 33 554 432 | 32 |
| Pico combinado observado | 46 137 344 | 44 |
| Lector tras liberar la página | 0 | 0 |

**Verificación final.** Con JDK 21.0.12, `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con `BUILD SUCCESSFUL` y código 0.

| Tarea Gradle | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 46 aprobadas, 0 fallidas y 0 omitidas. Se añadieron 2 pruebas del muestreo. |
| `:app:lintDebug` | 0 errores y 4 advertencias preexistentes. |
| `:app:connectedDebugAndroidTest` | 55 aprobadas en API 37, 0 fallidas y 0 omitidas. Incluye 5 pruebas de T03 sobre formatos, proporción y zoom 3x, una página por solicitud, liberación, memoria con caché llena, entrada malformada, descarte tras fallo o cambio rápido y decodificación de un PNG en un CBZ abierto mediante SAF. |

Pasaron las regresiones Gradle de SPEC-002/003 y `git diff --check`. El SHA-256 de Room v1 coincide con el registrado en T01.

**Pendientes y riesgos.** API 23 no está disponible en este entorno. Quedan pendientes pruebas de memoria en ese nivel y con dispositivos, CBZ y proveedores representativos. Las dos lecturas integrales por página pueden aumentar latencia en SAF. Se probó la salida a 3x del renderer. La memoria de presentación y la integración de ciclo de vida pertenecen a las verificaciones de las tareas posteriores.

**Validación.** T03 quedó `Validated` el 17 de noviembre de 2026 por autorización explícita del usuario en el mensaje «Autorizo validar T03 e implementar exclusivamente T04 de SPEC-004». La evidencia cumple el alcance de T03 para CA-03 y la parte de CA-04 correspondiente al renderer en la API disponible. Se conserva API 23 como verificación pendiente para la especificación.

## T04 — Persistencia de progreso y migración Room

Autorizada por el usuario en el mensaje «Autorizo validar T03 e implementar exclusivamente T04 de SPEC-004» del 17 de noviembre de 2026.

- **CA-05.** La migración explícita agrega `lastReadPageId` y `readingDirection` con valor inicial LTR a las filas existentes. El progreso y la dirección pertenecen al ID interno del libro. Una página ausente se resuelve a la primera disponible mediante `ReaderProgress.positionIn`. La reselección conserva el libro y su progreso hasta la siguiente página confirmada.
- **Confirmaciones.** El contrato de SPEC-004 captura un comprobante al confirmar la página y luego ejecuta su escritura asíncrona. La instancia única por base mantiene el comprobante más reciente de cada libro. La transacción comprueba vigencia antes y después de actualizar. Una confirmación posterior que coincide con lo guardado invalida escrituras anteriores sin modificar la fila. Esto ordena confirmaciones por momento de captura y permite volver a páginas anteriores.
- **CA-08, persistencia.** Las pruebas crean una base v1 real en disco con el esquema exportado y la reabren con otra instancia de Room. El fixture permite separar la escritura y la reapertura en dos invocaciones de instrumentación para probar el reinicio del proceso. La recreación de pantalla y la restauración visual corresponden a las tareas de ciclo de vida.
- **Archivos.** `LibraryDatabase.kt`, `LibraryEntryEntity.kt`, `ReaderProgressDao.kt`, `RoomReaderProgressRepository.kt`, `ReaderRepository.kt`, `SumiApplication.kt`, esquema `2.json`, `LibraryDatabaseTest.kt`, `ReaderProgressMigrationTest.kt` y este documento.

**Esquemas Room.** KSP exportó `2.json` con `identityHash` `c24217210a8503c9f9ffc9e8edfc5eae`. Al forzar nuevamente `:app:kspDebugKotlin --rerun`, el SHA-256 de `2.json` se mantuvo en `A18465AB82D85DA59D19B702F5B4D1B55F26272A4F8AF8AD36360C38D72D9603`. `1.json` conserva el SHA-256 registrado en T01. La prueba de migración valida el esquema v2 exportado y conserva IDs, título, portada, URI y referencia de concesión.

**Verificación final.** Con JDK 21.0.12, `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` finalizó con `BUILD SUCCESSFUL`. El primer intento encontró que `LibraryDatabase` no era compatible con `use` en el fixture. Se corrigió con cierre explícito y se volvió a ejecutar la suite.

| Tarea Gradle | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 46 aprobadas, 0 fallidas y 0 omitidas. |
| `:app:lintDebug` | 0 errores y 4 advertencias preexistentes. |
| `:app:connectedDebugAndroidTest` | 58 aprobadas en API 37, 0 fallidas y 0 omitidas. Incluye 3 pruebas nuevas de migración, progreso por libro, reselección, escrituras atrasadas y repetidas, y rollback al invalidar una escritura en curso. Incluye las regresiones de SPEC-002/003. |

**Prueba entre procesos.** Gradle retiró el paquete de instrumentación tras la suite. Se reinstalaron ambos APK con `adb install -r`. Se ejecutó la prueba `ReaderProgressMigrationTest#migratesRealV1AndKeepsIndependentProgressAfterReopeningAndReselection` mediante `adb shell am instrument -w` con la fase `prepare`, se detuvo la app del emulador mediante `adb shell am force-stop com.agustin.sumi` y se ejecutó la fase `reopen` con el mismo ID de fixture. Ambas fases informaron `OK (1 test)`, fuera del conteo Gradle. La prueba confirmó que cada libro conserva su progreso y que la reselección mantiene el ID y permite volver a una posición válida.

`git diff --check` pasó. Sigue pendiente la verificación en API 23 y la restauración visual durante el ciclo de vida en T05.

**Validación.** T04 quedó `Validated` el 17 de noviembre de 2026 por autorización explícita del usuario en el mensaje «Autorizo validar T04 e implementar exclusivamente T05 de SPEC-004». Se verificaron la migración explícita, el hash v1, la reproducción de v2, la reapertura entre procesos, la reselección y el orden de confirmaciones de CA-05 y CA-08 en el alcance de T04.

## T05 — ViewModel, sesiones y ciclo de vida

Autorizada por el usuario mediante el mismo mensaje del 17 de noviembre de 2026.

- **CA-01.** `ReaderViewModel` resuelve el libro por `LibraryEntryId`, carga su progreso y solicita la página guardada o la primera válida. Publica título, posición y dirección al quedar disponible el fotograma. La integración de navegación y pantalla corresponde a T06/T07.
- **CA-06.** El estado observable distingue fallo transitorio, documento ausente, permiso revocado y contenido inválido. La recuperación vuelve a abrir el libro usando las operaciones de selección individual y árbol de SPEC-003, con verificación SAF de la nueva referencia.
- **CA-07.** Una solicitud más reciente cancela la anterior y espera su limpieza. `Cleaning` dura hasta que el lector finaliza el trabajo cancelado. El número de fotograma protege tanto la publicación como la confirmación. Se captura el comprobante de T04 al mostrar el fotograma, y se espera su escritura al cerrar o reabrir la sesión.
- **CA-08.** El ViewModel se conserva en el `ViewModelStore` durante la recreación. Al pasar a segundo plano cierra la sesión y libera el bitmap. Al volver carga el progreso duradero y reabre el origen. El estado guardado conserva el ID del libro. Al relanzar, el siguiente acceso SAF recupera temporales huérfanos con el almacén existente.

**Archivos.** `ReaderViewModel.kt`, `ReaderPageRenderer.kt`, `NativeReaderRenderer.kt`, `SumiApplication.kt`, `ReaderViewModelTest.kt`, `NativeReaderRendererTest.kt`, `SafCbzPageAccessTest.kt` y este documento.

**Verificación.** Con JDK 21.0.12, `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con `BUILD SUCCESSFUL`. Un intento previo de lint falló internamente en `AsyncExecutionService` durante el análisis Kotlin. El fallo no se reprodujo al repetir con el código estable.

| Tarea Gradle | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 50 aprobadas, 0 fallidas y 0 omitidas. Cuatro casos nuevos sobre publicación tardía, orden de confirmación, limpieza, restauración y recuperación. |
| `:app:lintDebug` | 0 errores y 4 advertencias preexistentes. |
| `:app:connectedDebugAndroidTest` | 61 aprobadas en API 37, 0 fallidas y 0 omitidas. Tres casos nuevos de ciclo de vida y SAF, más regresiones de SPEC-002/003. |

**Evidencia instrumentada.** `ActivityScenario.recreate()` conservó la instancia del ViewModel y su sesión. El paso a segundo plano eliminó la copia SAF y el retorno reabrió con la posición confirmada en Room. Revocar el permiso produjo `ACCESS_DENIED` y habilitó la reselección. Con una lectura SAF real bloqueada durante la apertura, `Cleaning` se mantuvo hasta que el proveedor devolvió el control, el descriptor se cerró, la copia desapareció y el turno compartido quedó disponible.

**Relanzamiento entre procesos.** Se instalaron los APK compilados con `adb install -r` y se ejecutó `SafCbzPageAccessTest#restoresProgressAndRecoversOrphanOnReaderRelaunch` con `adb shell am instrument -w` en las fases `prepare` y `reopen`, separadas por `adb shell am force-stop com.agustin.sumi`. Ambas informaron `OK (1 test)` fuera del conteo Gradle. El proceso nuevo restauró el libro a partir del ID conocido y el progreso persistido, y eliminó un fixture huérfano con nombre propio del almacén SAF.

`git diff --check` pasó y los dos esquemas Room conservaron sus hashes. API 23 y proveedores SAF adicionales siguen pendientes. El tiempo de limpieza de I/O bloqueante depende de que el proveedor devuelva el control.

**Validación.** T05 quedó `Validated` el 17 de noviembre de 2026 por autorización explícita del usuario en el mensaje «Autorizo validar T05 e implementar exclusivamente T06 de SPEC-004». La revisión confirmó los criterios de finalización de CA-01 y CA-06 a CA-08, incluyendo limpieza SAF bloqueada, descarte de resultados tardíos, progreso confirmado, recreación, segundo plano y restauración entre procesos. Se conservan API 23 y proveedores SAF adicionales como verificaciones pendientes.

## T06 — Pantalla visual del lector

**Estado.** `Validated` el 17 de noviembre de 2026 por el mensaje «Autorizo validar T06 e implementar exclusivamente T07 de SPEC-004». La revisión confirmó la evidencia de CA-04 y CA-09 en API 37, incluida la corrección multitáctil y sus regresiones. Se mantienen los pendientes de compatibilidad indicados abajo.

**Implementación.** `ReaderScreen` consume `ReaderViewModel.state` y llama a `open`, `navigate`, `retry`, `selectDirection`, `confirmDisplayed` y `close`; no duplica coordinación de sesión. Presenta una página por vez, título y posición, controles Material 3 con etiquetas accesibles y targets de 48 dp, navegación LTR/RTL, zoom limitado a 3x por botones, doble toque y gesto pinch, paneo a zoom y restablecimiento al cambiar de página. Los estados visibles cubren apertura/carga, fallo recuperable, reselección individual o de árbol, limpieza, suspensión, cierre y fin. Confirma progreso después de que el callback de dibujo informa la presentación del fotograma; el renderizador conserva la propiedad del bitmap y lo dibuja sin retener una copia en la UI. La pantalla se ajusta al viewport medido y mantiene el área de controles estable.

**Pruebas añadidas.** `ReaderScreenTest` contiene ocho casos: límites y navegación accesible, confirmación solo tras dibujar, límite/reset de zoom, doble toque y LTR/RTL, pinch, recuperación de origen inaccesible, dos anchos y recreación de Activity. Incluye regresiones de las suites existentes.

**Verificación inicial.** Con JDK 21.0.12 se ejecutó `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest`.

| Verificación | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 50 aprobadas, 0 fallidas, 0 omitidas. |
| `:app:lintDebug` | 0 errores; 4 advertencias conocidas: versión AGP con actualización disponible, candidato a plural en una cadena existente, icono de aplicación ausente y sugerencia `UseKtx`. |
| `:app:connectedDebugAndroidTest` | 68 aprobadas, 1 fallida de 69 en API 37. Falló `pinchZoomIsBoundedAndCanBeResetAccessibly`: el pinch inyectado no produjo un fotograma con zoom distinto de 1x. Los otros siete casos de pantalla y las regresiones de SPEC-002/003 pasaron. |

La ejecución inicial dejó el pinch sin verificar. Los anchos de 320 y 600 dp y la recreación de Activity se comprobaron en API 37. API 23, dispositivo físico y otros proveedores SAF permanecen pendientes.

### Corrección multitáctil del 17 de noviembre de 2026

- **Reproducción.** Con `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.agustin.sumi.feature.reader.ReaderScreenTest#pinchZoomIsBoundedAndCanBeResetAccessibly` se reprodujo el fallo original de forma aislada en API 37. La sesión seguía en la primera página y el render registró solo 1x.
- **Causa del fallo original.** La prueba construía el primer y último evento con la sobrecarga simple de `MotionEvent.obtain` y los eventos intermedios como dedos de pantalla táctil explícitos. Tampoco comprobaba el resultado de la inyección, y el sondeo de un booleano aceptaba `false` como resultado. Al normalizar la secuencia y exigir a la vez el valor accesible y el último dibujo, el código original alcanzó 2x, 3x y 1x. Esto sitúa el bloqueo original del zoom en el estímulo de prueba y descarta un reset de escala por recomposición en esa reproducción.
- **Conflicto de navegación.** Con la prueba corregida y el detector original, desplazar juntos dos dedos y continuar con el dedo restante produjo una segunda carga. El detector ahora conserva que hubo más de un dedo durante toda la secuencia. Solo un gesto horizontal completo de un dedo, terminado a 1x, navega. Botones, pinch y doble toque comparten los límites 1x–3x y volver a 1x limpia el paneo.
- **Cobertura.** Se comprueban los valores exactos de zoom en la etiqueta accesible y el último dibujo tras soltar los dedos, ambos límites, el restablecimiento por control y al cambiar de página, y la ausencia de navegación accidental. Los deslizamientos reales de un dedo cubren avance y retroceso en LTR y RTL.

**Verificación posterior a la corrección.** Con JDK 21.0.12, `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.agustin.sumi.feature.reader.ReaderScreenTest` pasó los ocho casos. La ejecución completa con `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` y las mismas opciones de Gradle terminó con `BUILD SUCCESSFUL`.

| Verificación | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 50 aprobadas, 0 fallidas, 0 omitidas. |
| `:app:lintDebug` | 0 errores y 4 advertencias conocidas. |
| `:app:connectedDebugAndroidTest` | 69 aprobadas en API 37, 0 fallidas, 0 omitidas. Incluye las ocho pruebas del lector y las regresiones integradas de importación y biblioteca de SPEC-002/003. |

## T07 — Flujo biblioteca y lector del 17 de noviembre de 2026

**Autorización.** «Autorizo validar T06 e implementar exclusivamente T07 de SPEC-004». T07 quedó `Validated` el 17 de noviembre de 2026 por el mensaje «Autorizo validar T07 y ejecutar exclusivamente T08 de SPEC-004». La revisión confirmó los dos flujos instrumentados, la reanudación por libro, búsqueda, orden, scroll, Atrás, liberación del temporal y la eliminación durante reselección. La batería de T07 terminó con 71 pruebas instrumentadas aprobadas.

**Archivos de T07.** `MainActivity.kt`, `LibraryScreen.kt`, `LibraryRecoveryPanel.kt`, `ReaderScreen.kt`, `ReaderViewModel.kt`, `strings.xml`, `ReaderLibraryFlowTest.kt`, `PickerFixtureProvider.java`, `SafTestProvider.java` y este documento.

- **CA-01.** La acción «Leer» de cada tarjeta entrega el `LibraryEntryId` a `MainActivity`. La actividad cambia de pantalla con estado guardable y el lector resuelve libro, origen y progreso mediante los repositorios existentes. El estado guardable de la cuadrícula conserva el desplazamiento y el ViewModel de biblioteca conserva búsqueda, orden y paginación. Un cambio efectivo de consulta reinicia el desplazamiento. El regreso refresca la disponibilidad en la biblioteca.
- **CA-08 y comprobaciones de integración de CA-05 a CA-07.** Atrás de Android y el control de cierre retornan a la biblioteca y solicitan el cierre de la sesión. La pantalla usa el renderer existente y confirma después de dibujar. La reselección individual o de árbol usa los contratos SAF de biblioteca. Si el resultado del selector llega antes del regreso al primer plano, el ViewModel lo conserva para el mismo ID. Una entrada eliminada ofrece un mensaje recuperable y permite volver.
- **Pruebas.** `ReaderLibraryFlowTest` abre dos CBZ con imágenes reales mediante SAF directo y secuencial. Comprueba confirmación en Room, reanudación independiente, búsqueda, orden, posición estable de la cuadrícula, Atrás y limpieza de la copia de sesión. Un segundo caso usa el selector individual del sistema para recuperar acceso revocado y luego eliminar el libro con el selector abierto. La reselección de árbol se mantiene cubierta a nivel de biblioteca y repositorio por las regresiones existentes.
- **Regresión detectada.** La primera ejecución completa tuvo 70 pruebas instrumentadas aprobadas y una fallida. La creación anticipada del ViewModel con la clave predeterminada de la actividad interfería con el modelo que inyecta la prueba de recreación. La ruta ahora crea el ViewModel al necesitarlo y usa una clave propia en el almacén de la actividad.
- **Estabilidad de pruebas.** La prueba de regreso espera la carga de preferencias antes de escribir la búsqueda, comprueba que se aplicó y mide el scroll en reposo. En una repetición posterior, los dos flujos de T07 y la prueba de recreación pasaron. El único fallo fue el clic sobre la raíz de prueba en el selector múltiple preexistente. Se reinició el proceso del selector en el emulador antes de repetir la batería.

**Verificación final.** Con JDK 21.0.12, `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con `BUILD SUCCESSFUL` en 1 min 49 s.

| Verificación | Resultado |
| --- | --- |
| `:app:assembleDebug` | Correcta. |
| `:app:testDebugUnitTest` | 50 aprobadas, 0 fallidas y 0 omitidas en la ejecución del código de producción final. Gradle reutilizó esos resultados en la última repetición, que solo cambió pruebas instrumentadas. |
| `:app:lintDebug` | 0 errores y 4 advertencias conocidas. |
| `:app:connectedDebugAndroidTest` | 71 aprobadas en API 37, 0 fallidas y 0 omitidas, incluidos los dos flujos nuevos y las regresiones de SPEC-002/003. |

Los hashes de los esquemas Room v1/v2 coincidieron antes y después. API 23, dispositivo físico y otros proveedores SAF quedan pendientes.

## T08 — Verificación integral del 17 de noviembre de 2026

**Autorización de ejecución.** «Autorizo validar T07 y ejecutar exclusivamente T08 de SPEC-004». La evidencia de esta sección corresponde a T08.

**Entorno.** JDK 21.0.12, Gradle 9.5.0, AGP 9.3.1, Kotlin 2.4.21, Compose BOM 2026.09.00 y Room 2.8.5. El único dispositivo visible para ADB fue `emulator-5554`, AVD `Medium_Phone`, Android API 37, `sdk_gphone64_x86_64`. El SDK local solo tenía la imagen de API 37.

**Archivos.** Se agregaron pruebas en `ReaderLibraryFlowTest.kt` y `ReaderScreenTest.kt`, y salida de medición en `CbzPageAccessTest.kt`. Este documento registra la validación de T07 y la evidencia de T08.

**Medición de CA-02.** El fixture mixto incluye JPG, PNG, WebP, un auxiliar y un directorio. Los bytes descomprimidos de los cuatro candidatos fueron `[10, 12, 8, 12]` en ambos métodos. El directorio se omitió y el auxiliar no se identificó como página. Para DEFLATE se leyeron `[12, 8192, 10, 12]` bytes comprimidos de carga. El candidato grande tenía 131 115 bytes comprimidos. Para STORED se leyeron `[10, 12, 8, 12]` bytes de carga frente a 131 075 bytes del candidato grande. El contador de carga es independiente de las lecturas de estructura ZIP. Las pruebas de CRC permiten indexar con un vecino dañado y rechazan ese vecino cuando se lo solicita, aun si el consumidor no termina de leerlo.

**Medición de CA-03.** En API 37, la decodificación de la imagen PNG sintética de 4096 × 3072 usó una asignación de página de 12 MiB a 2048 × 1536. La caché de portadas retuvo 32 MiB y el pico combinado medido fue 44 MiB. El lector quedó en 0 MiB tras liberar el bitmap. El lector quedó debajo de su objetivo de 32 MiB y la suma debajo del techo provisional de 64 MiB. Se midieron bytes asignados y retenidos por los bitmaps. El fixture no mide texturas GPU, buffers del codec, portadas que una vista retiene tras el desalojo ni RSS. La medición equivalente en API 23 es una condición obligatoria de cierre del plan y sigue pendiente.

### Evidencia por criterio

| Criterio | Resultado |
| --- | --- |
| CA-01 | Importación y apertura desde Descargas con el selector real, en modo avión y Wi-Fi apagado. La biblioteca conservó búsqueda, orden y scroll en el flujo T07. La eliminación mientras se reseleccionaba produjo el error recuperable y permitió volver. |
| CA-02 | Orden e identidades coincidentes con el importador en carpetas y nombres mixtos. Prefijo de salida ≤12 bytes por candidato y contadores separados, según la medición anterior. Las pruebas de estructura, errores de prefijo DEFLATE y CRC de la entrada solicitada pasaron. |
| CA-03 | API 37 y API 23 confirmaron muestreo, proporción, una página activa, ausencia de precarga, liberación y pico combinado de 44 MiB con la caché de portadas llena. Las lecturas de dimensiones y decodificación corresponden a la misma página. La comprobación adicional de API 23 se detalla abajo. |
| CA-04 | Los gestos LTR/RTL se comprobaron en pantalla con repositorio de prueba. En el flujo real de Descargas se comprobó LTR inicial, cambio a RTL, avance por control, zoom 3x, ajuste 1x y restauración de RTL al reabrir. Pinch, doble toque, límites, objetivos táctiles y reset de zoom al cambiar de página pasaron en la suite de pantalla. |
| CA-05 | Room confirmó la página dibujada. Los flujos conservaron progreso por libro y el reinicio entre procesos restauró posición y dirección. Pasaron el ajuste de posición inválida, la migración v1→v2, la reapertura, los comprobantes de escritura y la preservación de ID, URI y referencia SAF. Los SHA-256 de ambos esquemas coincidieron con T07. |
| CA-06 | Revocación y reselección individual con selector real. Las pruebas de pantalla muestran origen ausente, acción de reselección y reintento ante fallo transitorio. Las pruebas de ZIP/CRC y decodificación rechazan páginas corruptas y no publican el bitmap candidato. |
| CA-07 | Pasaron SAF directo y secuencial, la cuota de temporales con límites de prueba reducidos, la cancelación bloqueada con liberación del proveedor, el descarte de resultados tardíos y el cierre por Atrás. Se verificó la liberación del turno compartido y la eliminación de la copia temporal. |
| CA-08 | Giro real a horizontal y vertical sobre una página del CBZ importado. Recreación y segundo plano conservaron la posición sin aperturas simultáneas y reabrieron el origen cuando correspondía. El relanzamiento por fases restauró progreso y eliminó el temporal huérfano. |
| CA-09 | Pasaron navegación, zoom y controles accesibles de al menos 48 dp comprobados en la suite. Se verificaron carga, error transitorio, origen inaccesible y fin en áreas de 840 y 1080 px. El emulador limitó el ancho solicitado de 600 dp al ancho disponible de aproximadamente 411 dp en vertical. El giro real cubrió el lector en horizontal. |
| CA-10 | Pasaron las cuatro tareas Gradle con JDK 21 en el dispositivo disponible. Las regresiones integradas de importación y biblioteca están incluidas. No había imagen de API 23 ni teléfono físico conectado. |

### Comandos y resultados

- `gradlew.bat --offline --no-daemon --console=plain --max-workers=1 :app:assembleDebug :app:testDebugUnitTest --rerun :app:lintDebug :app:connectedDebugAndroidTest` terminó con `BUILD SUCCESSFUL` en 2 min 45 s. Compilación correcta, 50 JVM y 73 instrumentadas aprobadas, ninguna fallida u omitida. Lint informó 0 errores y 4 advertencias conocidas, `AndroidGradlePluginVersion`, `PluralsCandidate`, `MissingApplicationIcon` y `UseKtx`.
- ADB reinstaló ambos APK y ejecutó cinco invocaciones de `am instrument -w`, cada una con `OK (1 test)`, fuera del conteo Gradle. El flujo de Descargas se repitió en modo avión y con Wi-Fi apagado. Verificó importación, lectura, giro, reanudación y eliminación con el original aún legible. El proveedor fue `com.android.providers.downloads.documents`, adicional al proveedor controlado. Se restauró la configuración de red anterior.
- Las otras cuatro invocaciones ejecutaron las fases `prepare` y `reopen` de `SafCbzPageAccessTest#restoresProgressAndRecoversOrphanOnReaderRelaunch` y `ReaderProgressMigrationTest#migratesRealV1AndKeepsIndependentProgressAfterReopeningAndReselection`, separadas por `adb shell am force-stop com.agustin.sumi` y con el mismo ID de fixture dentro de cada par. `git diff --check` pasó.

**Pendientes de la ejecución inicial.** La medición de API 23 quedó abierta en esa ejecución y se completó en la continuación de abajo. Hardware físico y CBZ representativos no incluidos en el workspace quedan como cobertura adicional. La doble lectura integral por página y la dependencia de los metadatos de revisión de SAF secuencial siguen siendo riesgos de latencia y detección de cambios.

### Continuación de CA-03 en API 23

El 17 de noviembre de 2026 el usuario autorizó completar exclusivamente CA-03. Se instaló la imagen oficial `system-images;android-23;google_apis;x86_64`, revisión 33. `avdmanager` no encontró la imagen desde la carpeta temporal y sí la encontró al colocar las herramientas dentro del SDK. Se creó `Sumi_CA03_API23` con perfil Pixel, Android 6.0 API 23, ABI x86_64 y 2 050 332 kB de RAM visibles en `/proc/meminfo`. El emulador se ejecutó en `emulator-5580` con SwiftShader. ADB instaló los mismos APK de T08 ya compilados.

| Medición del mismo fixture | API 23 | API 37 |
| --- | ---: | ---: |
| PNG 4096 × 3072 muestreado a 2048 × 1536 | 12 MiB | 12 MiB |
| Caché llena con 32 portadas de 512 × 512 | 32 MiB | 32 MiB |
| Pico combinado | 44 MiB | 44 MiB |
| Bitmap del lector tras liberar | 0 MiB | 0 MiB |

Se midieron `Bitmap.allocationByteCount` y `CoverCache.retainedBytes`. Bajo presión reproducida con la caché llena, el muestreo mantuvo el lector bajo su objetivo de 32 MiB y la suma 20 MiB por debajo del techo de 64 MiB en ambas API. En API 23, el log registró 12 582 912 bytes para el lector, 33 554 432 bytes para portadas y 46 137 344 bytes de pico combinado. La prueba afirma 0 bytes del lector tras `clear()`. La medición cubre bitmaps del fixture y excluye RSS, buffers internos, texturas GPU y bitmaps de portada retenidos por vistas tras el desalojo.

- **Instrumentación compatible.** `adb -s emulator-5580 shell am instrument -w -e class <casos> com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest` devolvió `OK (6 tests)` para `NativeReaderRendererTest` completo y los casos `ReaderScreenTest#accessibleControlsNavigateRespectBoundariesAndConfirmOnlyDrawnFrames` y `#progressIsNotConfirmedUntilTheCanvasDrawsThePage`. Se comprobaron JPG/PNG/WebP, muestreo, proporción, ausencia de precarga, descarte de candidatos, cancelación de cambios rápidos, reciclado y confirmación tras dibujar.
- **CBZ real en pantalla.** Se empujó al AVD un ZIP sintético de 16 779 139 bytes con dos PNG de 4096 × 3072 y una entrada auxiliar. Se importó desde Almacenamiento interno en el selector estándar. Android registró la concesión de `com.android.externalstorage.documents`. La primera captura, en página 1 de 2, tenía una única imagen accesible y tres muestras centrales rojas. Al avanzar, la página 2 de 2 tenía una única imagen y las tres muestras eran azules. Se cerró el lector y se volvió a la biblioteca.
- **Límite del fixture SAF instrumentado.** La ejecución que incluyó `SafCbzPageAccessTest#nativeRendererReadsVerifiedPngFromSafSession` se interrumpió al cargar `SafTestProvider$$ExternalSyntheticLambda4` en el proveedor de prueba. Tras reinstalar los APK, ese caso aislado falló con `SOURCE_UNAVAILABLE` y volvió a aparecer el error de carga en `SafTestProvider.call`. La comprobación del CBZ mediante el proveedor del sistema fue independiente. El caso instrumentado del proveedor controlado queda registrado como fallido en API 23.

**Resultado de CA-03.** Medición obligatoria en API 23 completada y contrastada con API 37.

### Cierre de T08 y SPEC-004

El 17 de noviembre de 2026, por autorización explícita del usuario en el mensaje «Autorizo validar T08 y cerrar SPEC-004, condicionado a confirmar que toda la evidencia obligatoria cumple los criterios aprobados», se revisaron T01–T07, la evidencia de T08 y CA-01 a CA-10. No se encontraron incumplimientos obligatorios. T08 y SPEC-004 quedan `Validated`.

La prueba adicional `SafCbzPageAccessTest#nativeRendererReadsVerifiedPngFromSafSession` en API 23 falló antes de ejercitar el acceso SAF: el proveedor instrumentado no pudo cargar `SafTestProvider$$ExternalSyntheticLambda4` y la operación terminó como `SOURCE_UNAVAILABLE`. Se clasifica como fallo del fixture de prueba, no como aprobación de esa prueba ni evidencia de fallo del producto. El flujo SAF se verificó con el proveedor real de DocumentsUI en API 23 y los accesos directo y secuencial con las suites de API 37. Se conserva pendiente el diagnóstico del fixture.

**Verificaciones adicionales no bloqueantes:** prueba SAF instrumentada adicional en API 23; diagnóstico de carga de clase de `SafTestProvider`; ejecución en hardware físico; cobertura con otros proveedores SAF. No alteran CA-01 a CA-10 ni las condiciones obligatorias de cierre.
