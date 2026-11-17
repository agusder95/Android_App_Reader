# SPEC-002 — Tareas propuestas

**Estado de SPEC-002: Validated.** T01 a T08 están `Validated`. La [revisión final](#revisión-final-y-cierre) registra la autorización y las verificaciones pendientes.

| ID | Tarea propuesta | Depende de | Criterios | Finalización verificable |
| --- | --- | --- | --- | --- |
| T01 | Preparar la matriz de verificación a partir de las decisiones D01 a D06. | Aprobación de SPEC-002 y autorización de T01 | CA-01 a CA-06 | La matriz cubre ambas rutas de acceso, reapertura y limpieza tras reiniciar, estructura y ZIP64, CRC, cada límite y su primer exceso, cancelación con lectura bloqueada, orden y URI duplicados. |
| T02 | Definir los modelos y el contrato de importación. | T01 | CA-06 | La referencia y los descriptores identifican el origen y las entradas sin tipos Android ni del parser. El resultado sigue identificando el origen tras eliminar el temporal y carece de recursos abiertos o rutas temporales. |
| T03 | Implementar el acceso de solo lectura, la persistencia URI y el respaldo temporal. | T01, T02 | CA-02, CA-05 | Se comprueban el acceso directo SAF y sus limitaciones, el respaldo con un proveedor secuencial y la reapertura tras reinicio. Se prueban permisos ausentes o revocados, archivo ausente, límites de copia, limpieza al terminar y recuperación de temporales huérfanos. |
| T04 | Validar la estructura y la integridad ZIP con lectura por bloques. | T01, T02 | CA-03, CA-05 | Pruebas automatizadas cubren directorio central inválido o sobredimensionado, ZIP64 válido y malformado, offsets o recuentos incoherentes, CRC incorrecto, rutas inseguras, límites y cancelación. Se documenta si las APIs estándar permiten acotar la inspección antes de cargar el índice. |
| T05 | Identificar páginas por firma y aplicar el orden natural. | T04 | CA-04, CA-06 | Los fixtures comprueban los cuatro tipos, nombres engañosos, carpetas internas, rutas duplicadas, empates e identificadores distinguibles. |
| T06 | Integrar el acceso, el parser y la deduplicación por URI en el repositorio. | T03, T05 | CA-02, CA-03, CA-05, CA-06 | El acceso directo SAF y el temporal dan resultados equivalentes. La pérdida de acceso permite reselección y el mismo URI produce un solo resultado por sesión. Con una fuente simulada bloqueada, se comprueba que cancelar no publique resultados parciales ni registre éxito antes o después de liberarla. Al liberarse se verifica el cierre de recursos y la limpieza del temporal, sin imponer un plazo a proveedores externos. |
| T07 | Conectar la acción mínima de importar con SAF y presentar el resultado. | T06 | CA-01, CA-02, CA-05 | En un dispositivo sin conexión se comprueban los filtros CBZ/ZIP, selección, cancelación, éxito y reselección ante un error de acceso. Se registra el comportamiento de proveedores que informan un MIME genérico. |
| T08 | Ejecutar las verificaciones del plan y presentar evidencia. | T07 | CA-01 a CA-06 | Se registran comandos, fixtures, resultados y pendientes. El cierre se rige por la Definition of Done de [AGENTS.md](../../AGENTS.md). |

## T01 — Evidencia de ejecución del 12 de noviembre de 2026

Se verificaron las precondiciones de T01. SPEC-002 figura como `Approved` y el usuario autorizó exclusivamente T01 en el mensaje «Autorizo exclusivamente T01 de SPEC-002». Se revisaron la arquitectura, el stack y los ADR aprobados.

La [matriz de verificación](verification.md) define 24 casos únicos con entradas, pasos y resultados esperados para CA-01 a CA-06. La revisión documental confirmó las dos rutas de acceso, los cinco límites de recursos, ZIP64, CRC, limpieza y cancelación con lectura bloqueada. Los 24 casos de importación quedan pendientes de ejecución junto con las tareas que implementen el flujo. La selección o incorporación de dependencias adicionales de D06 queda sujeta a autorización.

| Comprobación de la base Android | Resultado |
| --- | --- |
| `./gradlew.bat --version` | Gradle `9.5.0` y JDK `21.0.12`. |
| `./gradlew.bat :app:assembleDebug` | Código 0 y `BUILD SUCCESSFUL`. Las 36 tareas estaban al día. |
| `./gradlew.bat :app:testDebugUnitTest` | Código 0 y `NO-SOURCE`. La base carece de fuentes de pruebas unitarias. Se descubrieron y ejecutaron 0 pruebas. |
| `./gradlew.bat :app:lintDebug` | Código 0, 0 errores y 2 advertencias: `AndroidGradlePluginVersion` por una versión posterior disponible y `MissingApplicationIcon` por el ícono ausente. |

Los tres comandos de verificación se ejecutaron por separado con `--offline --no-daemon --console=plain --max-workers=2` y el caché local. Los registros quedaron en `.gradle/spec-002-t01/`.

**Validación de T01 — 12 de noviembre de 2026.** El usuario autorizó la validación en el mensaje «Autorizo la validación de T01 y la implementación exclusiva de T02 de SPEC-002». Se revisaron la matriz y la evidencia de la base Android. T01 queda `Validated` por la entrega documental. Los 24 casos de `verification.md` siguen pendientes de ejecución.

## T02 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobó que SPEC-002 está `Approved`, que T01 está `Validated` y que el usuario autorizó T02 en el mensaje registrado arriba. Se agregaron `CbzSourceReference`, `CbzPageDescriptor` y `CbzImportResult` en `domain/model`, y `CbzImportRepository` en `domain/repository`, con el stack existente.

Se cubrió la parte de CA-06 correspondiente al contrato. El éxito contiene la referencia opaca al origen y la lista ordenada de descriptores con identificadores distintos para páginas de rutas distintas. La revisión de las firmas confirmó que el dominio carece de tipos Android, rutas temporales, recursos abiertos y tipos del parser. Los fallos se representan mediante errores de dominio. El contrato exige propagar la cancelación.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug` | Código 0 y `BUILD SUCCESSFUL`. Se recompiló Kotlin y se generó la APK debug. |
| `./gradlew.bat :app:testDebugUnitTest` | Código 0 y `NO-SOURCE`. Se descubrieron y ejecutaron 0 pruebas unitarias. Esta tarea define modelos y un contrato sin lógica de importación. |
| `./gradlew.bat :app:lintDebug` | Código 0. El informe registró 0 errores y las 2 advertencias preexistentes `AndroidGradlePluginVersion` y `MissingApplicationIcon`. |
| Comprobación JVM de `ContractSmoke.kt` con el compilador Kotlin 2.4.21 en caché y `java -cp … ContractSmokeKt` | Código 0 y 5 comprobaciones satisfactorias con datos sintéticos sobre la referencia tras borrar un temporal de prueba, el orden conservado, la distinción de páginas y la omisión del origen y los identificadores en `toString()`. |

Los tres comandos Gradle se ejecutaron por separado con `--offline --no-daemon --console=plain --max-workers=2`. Los registros y la comprobación JVM local quedaron en `.gradle/spec-002-t02/`, excluido de Git.

Los 24 casos de la matriz siguen pendientes. La comprobación JVM ejercitó el modelo en aislamiento. El flujo real con temporales, la generación y validación de identificadores y la deduplicación por URI se verificarán con las tareas correspondientes.

**Validación de T02 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo explícitamente la validación de T02 y la implementación exclusiva de T03 de SPEC-002». Se revisaron las firmas del dominio y la evidencia registrada. T02 queda `Validated` por la entrega del contrato. Las pruebas funcionales siguen pendientes.

## T03 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobaron la aprobación de SPEC-002, la validación de T01 y T02 y la autorización explícita de T03. El adaptador en `data/local/document` reabre el URI original en solo lectura, conserva el permiso de lectura mediante Android y distingue los errores de acceso recuperables de los de E/S. El dominio conserva el contrato de T02.

- **CA-02, acceso al origen.** En API 37, un proveedor de prueba entregó un descriptor con longitud y posicionamiento y un flujo secuencial. Se verificaron lectura posicionada directa, respaldo y eliminación del temporal, permiso persistible, permiso no persistible, revocación, documento ausente y reapertura del mismo URI tras detener el proceso de la app. La capacidad de posicionarse habilita el acceso directo si el consumidor puede completar sus comprobaciones. El consumidor puede solicitar respaldo cuando requiera un archivo.
- **CA-05, copia y limpieza.** Se comprobaron los bytes reales en el límite reducido y el primer exceso, espacio insuficiente antes y durante la copia, cierre y eliminación tras éxito, fallo o cancelación cooperativa. En API 37 se verificó que el adaptador elimina un temporal huérfano simulado antes de la primera apertura tras reiniciar el proceso y conserva un archivo ajeno. El límite de producción es 2 GiB y la copia se acota al espacio disponible.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug` | Correcta. Se recompiló Kotlin y se generó la APK debug. |
| `./gradlew.bat :app:testDebugUnitTest` | `NO-SOURCE`, 0 pruebas descubiertas y ejecutadas por esta tarea. La suite autónoma se ejecutó por separado. En el intento inicial, Gradle rechazó la suite bajo el directorio convencional porque carece de un framework de descubrimiento. |
| `./gradlew.bat :app:lintDebug` | 0 errores y 2 advertencias preexistentes, `AndroidGradlePluginVersion` y `MissingApplicationIcon`. |
| `./gradlew.bat :app:assembleDebugAndroidTest` | Correcta. Se generó la APK instrumentada con un proveedor de prueba y el runner de plataforma. |
| `java -cp <jar-de-pruebas>;<kotlin-stdlib> com.agustin.sumi.data.local.document.TemporaryCbzStoreTestKt` | 9 pruebas JVM pasaron. Se compiló el jar con `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` 2.4.21 del caché, usando los modelos de dominio, `DocumentAccessException.kt`, `TemporaryCbzStore.kt` y el archivo de pruebas en `app/src/test/standalone`. |
| `adb -s emulator-5562 shell am instrument -r -w <runner>` | 5 pruebas instrumentadas pasaron en API 37. Se instalaron la APK debug con `adb install -r` y la APK de pruebas con `adb install -r -t`. |
| `adb -s emulator-5562 shell am force-stop com.agustin.sumi` y `adb -s emulator-5562 shell am instrument -r -w -e phase reopen <runner>` | 2 pruebas instrumentadas pasaron tras reiniciar el proceso en API 37. |

El Wrapper terminó con código 0 al ejecutar las cuatro tareas juntas con `--offline --no-daemon --console=plain --max-workers=2`. El runner instrumentado es `com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest`. Los registros quedaron en `.gradle/spec-002-t03/`, excluido de Git. Se usaron las APIs de Android y dependencias transitivas ya resueltas, sin agregar dependencias al build.

La evidencia de T03 cubre el adaptador con un proveedor controlado y límites sintéticos. Los 24 casos integrales de la matriz siguen pendientes. Quedan por comprobar el selector y proveedores reales, API 23, el límite de producción con un CBZ representativo y la falta real de espacio. La validación ZIP y la equivalencia de ambas rutas con un CBZ válido corresponden a T04 y T06. La cancelación con E/S bloqueada corresponde a T06.

**Validación de T03 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo la validación de T03 y la implementación exclusiva de T04 de SPEC-002». Se comprobó la evidencia obligatoria del adaptador, incluidos los 9 casos JVM y los 7 de API 37. T03 queda `Validated`. Se conservan las verificaciones pendientes registradas arriba.

## T04 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobaron la aprobación de SPEC-002, la validación de T01 y T02 y la autorización explícita de T04. El lector y la inspección estructural se agregaron en `data/local/archive`. La validación recibe un canal de solo lectura o el archivo temporal privado de la operación. El contrato del lector deja el contexto de E/S al adaptador que lo invoque en T06.

- **CA-03, estructura e integridad.** Se verifican el fin del directorio, el localizador y registro ZIP64, los límites del directorio central, sus offsets y recuentos, las cabeceras locales y los descriptores de datos de 32 y 64 bits. El CRC y los tamaños se comprueban leyendo cada entrada por bloques. Se distinguen archivo de cero bytes, ZIP vacío, formato ajeno y contenedor corrupto. La identificación de imágenes y el resultado para un ZIP válido sin páginas compatibles corresponden a T05.
- **CA-05, recursos.** Se validan rutas antes de leer los datos. Se comprueban los límites de longitud comprimida, cantidad real de entradas, tamaño de imagen y total expandido, incluidos auxiliares. Un clasificador de prueba usa el prefijo de la entrada para activar el límite por imagen. Se verificó la propagación de la cancelación cooperativa durante la inspección y la lectura.

La inspección previa a la descompresión lee campos de tamaño acotado y conserva hasta 5000 registros de metadatos. `ZipFile` se usa para copias privadas compatibles con entradas almacenadas y directorio de hasta 1 MiB. En los demás casos, `ZipInputStream` decodifica un tramo acotado por entrada después de verificar sus registros originales. Así se comprueba también la longitud comprimida de DEFLATE y se evita cargar un índice grande. El umbral de 1 MiB selecciona la estrategia de lectura. Los límites de aceptación siguen siendo los de la especificación. Se admiten ZIP y ZIP64 de un solo volumen con entradas STORED o DEFLATE. Se rechazan archivos cifrados, multivolumen y diseños con registros adicionales fuera del trazado local y central comprobado. [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT) · [ZipInputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipInputStream.html)

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug` | Correcta. Se recompiló Kotlin y se generó la APK debug. |
| `./gradlew.bat :app:testDebugUnitTest` | `NO-SOURCE`, 0 pruebas descubiertas y ejecutadas por esta tarea. La suite autónoma se ejecutó por separado con el stack existente. |
| `./gradlew.bat :app:lintDebug` | 0 errores y 2 advertencias preexistentes, `AndroidGradlePluginVersion` y `MissingApplicationIcon`. |
| `java -cp <jar-de-pruebas>;<kotlin-stdlib> com.agustin.sumi.data.local.archive.CbzArchiveReaderTestKt` | 28 casos pasaron en JDK 21. Se compiló el jar con `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` 2.4.21 del caché. |
| `d8 --min-api 37 --lib <android.jar> --output <dex.jar> <jar-de-pruebas> <kotlin-stdlib>` y `adb -s emulator-5562 shell dalvikvm -Djava.io.tmpdir=/data/local/tmp -cp /data/local/tmp/sumi-t04-archive-tests.jar com.agustin.sumi.data.local.archive.CbzArchiveReaderTestKt` | Los mismos 28 casos pasaron en el runtime Android API 37. El jar DEX se copió al emulador de prueba con ADB. |

El Wrapper terminó con código 0 al ejecutar las tres tareas juntas con `--offline --no-daemon --console=plain --max-workers=2`. Los registros `gradle.log`, `archive-jvm.log` y `archive-android37.log` quedaron en `.gradle/spec-002-t04/`, excluido de Git.

La suite cubre la parte del lector de V08 a V11, V14 a V18 y V20 con archivos sintéticos y límites reducidos. Los 24 casos integrales de la matriz siguen pendientes. Quedan por comprobar API 23, consumo con archivos representativos cerca de los límites de producción, equivalencia con proveedores SAF reales y cancelación con E/S bloqueada.

### Corrección de la búsqueda EOCD — 12 de noviembre de 2026

La revisión reprodujo `CORRUPT_ARCHIVE` con un ZIP válido que contenía un EOCD señuelo en el comentario. La búsqueda elegía la última firma cuya longitud de comentario terminaba al final del archivo, antes de comprobar el directorio. Si la firma era falsa, el error terminaba la búsqueda.

La búsqueda ahora recorre la ventana de 22 bytes de cabecera más hasta 65.535 bytes de comentario. Para cada candidato verifica el final del archivo y la coherencia del directorio central con las cabeceras locales. Descarta candidatos estructuralmente incoherentes. Conserva los controles ZIP64, CRC, rutas y límites. Cuando hay otra firma EOCD después del registro elegido, usa los tramos ya verificados para evitar que `ZipFile` reinterprete el comentario. [Formato ZIP de PKWARE, § 4.3.16 y § 4.4.25–26](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)

La suite autónoma del lector pasó **34 de 34 casos en JDK 21 y 34 de 34 en Android API 37**, con seis regresiones nuevas de [V09 a V11](verification.md#estructura-integridad-e-imágenes). Incluye ZIP sin comentario, comentario convencional, firmas falsas múltiples en el máximo de 65.535 bytes, un candidato previo con directorio incoherente, EOCD real corrupto, ZIP64 válido y malformado y CRC incorrecto. El archivo que antes se rechazaba ahora devuelve una entrada. En JDK 21 con `-Xmx24m` también se repitieron las pruebas de un directorio central de 156,5 MiB con 5000 entradas y 256 MiB expandidos por DEFLATE.

Se ejecutó la suite con `java -cp … CbzArchiveReaderTestKt` en JDK 21 y con `adb shell dalvikvm -cp … CbzArchiveReaderTestKt` tras convertirla con D8 para API 37. El comando `./gradlew.bat --offline --no-daemon --console=plain --max-workers=2 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` terminó con código 0. Se generó la APK debug. `testDebugUnitTest` dio `NO-SOURCE` y ejecutó 0 pruebas. Lint informó 0 errores y las 2 advertencias preexistentes `AndroidGradlePluginVersion` y `MissingApplicationIcon`. El daemon de Gradle usó JDK 25 por la configuración local preexistente. Los registros quedaron en `.gradle/spec-002-t04-eocd/`.

**Validación de T04 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo explícitamente la validación de T04 y la implementación exclusiva de T05 de SPEC-002». Se comprobó la evidencia obligatoria de T04, incluidas las 34 pruebas en cada entorno, la regresión EOCD y las comprobaciones Gradle. T04 queda `Validated`. Las verificaciones integrales y los riesgos asignados a tareas posteriores siguen pendientes.

## T05 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobó la aprobación de SPEC-002, el cierre de T04 y la autorización explícita de T05. Se consultó `android-clean-architecture` y se aplicó el ADR-002 para mantener el comparador en Kotlin puro y el acceso al ZIP en `data/local/archive`, dentro de `:app`.

- **CA-04.** Se identifican JPG/JPEG, PNG y WebP por firma, incluidas páginas en carpetas y con extensiones en mayúsculas o engañosas. Se omiten directorios y entradas sin firma compatible. El orden compara segmentos de ruta, secuencias de dígitos ASCII de cualquier longitud y, al final, la grafía literal de la ruta normalizada. El resultado es independiente del idioma y conserva el desempate `page2.jpg`, `page02.jpg`, `page10.jpg`. [PNG](https://www.w3.org/TR/png-3/) · [WebP](https://developers.google.com/speed/webp/docs/riff_container) · [JPEG SOI](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-T.84-199607-I%21%21PDF-E&lang=e&type=items)
- **CA-06, descriptores e identidad de página.** Se quitan segmentos vacíos y `.` conservando mayúsculas y minúsculas. Se rechazan rutas duplicadas tras normalizar y se generan identificadores opacos de tamaño fijo con SHA-256 de cada ruta normalizada. Los nombres base iguales en carpetas distintas producen identificadores distintos. Se interpretan nombres UTF-8 señalizados y CP437 heredado. Durante el ordenamiento se conservan hasta 32 rutas decodificadas en caché.
- **Resultado sin imágenes y límite por imagen.** Se distingue `NO_SUPPORTED_IMAGES` de un ZIP vacío. La detección por firma activa el límite por imagen del lector existente, incluso si el nombre de la entrada tiene otra extensión.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug` | Correcta. Se recompiló Kotlin y se generó la APK debug. |
| `./gradlew.bat :app:testDebugUnitTest` | `NO-SOURCE`. Gradle descubrió y ejecutó 0 pruebas. |
| `./gradlew.bat :app:lintDebug` | 0 errores y 2 advertencias preexistentes, `AndroidGradlePluginVersion` y `MissingApplicationIcon`. |
| `java -cp … CbzArchiveReaderTestKt` en JDK 21 | 44 de 44 casos autónomos pasaron. Son 10 de T05 y 34 regresiones de T04. |
| `d8 --min-api 37 …` y `adb shell dalvikvm -cp … CbzArchiveReaderTestKt` | Los mismos 44 casos autónomos pasaron en Android API 37. |

El Wrapper terminó con código 0 al ejecutar las tres tareas juntas con `--offline --no-daemon --console=plain --max-workers=2`. El daemon usó JDK 25 por la configuración local preexistente. La suite incluye integración del lector con la clasificación mediante un canal y un archivo temporal de prueba. La ejecución en Android fue autónoma, fuera de Gradle y del runner instrumentado. Los registros quedaron en `.gradle/spec-002-t05/`.

Se cubren V12, V13 y la parte de identidad y duplicados de V23 en el lector. La matriz integral conserva como pendientes la reapertura real y eliminación del temporal vía SAF, la deduplicación por URI y la cancelación bloqueada. También quedan pendientes API 23 y medir el coste de ordenar archivos representativos cerca de los límites de producción.

**Validación de T05 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo la validación de T05 y la implementación exclusiva de T06 de SPEC-002». Se comprobaron CA-04 y la identidad de página de CA-06 con los fixtures y las 44 pruebas en cada entorno, además de la evidencia de Gradle. T05 queda `Validated`.

## T06 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobaron la aprobación de SPEC-002, el cierre de T03 y T05 y la autorización explícita de T06. Se aplicaron el ADR-002, el ADR-003 y las pautas compatibles de `android-clean-architecture`. `LocalCbzImportRepository` coordina el acceso SAF y el lector existente dentro de `:app`.

- **CA-02 y CA-03.** Un proveedor Android de prueba sirvió el mismo CBZ como descriptor posicionable y como tubería. Ambas rutas devolvieron las mismas páginas ordenadas y conservaron la referencia original. Un CBZ inválido devolvió su error de validación. Se probaron documento ausente y permiso revocado después de un éxito. La reselección con permiso vigente volvió a importar el documento. Una falla libera el permiso de lectura adquirido por la operación y conserva un permiso previo.
- **CA-05.** Se verificó la cancelación cooperativa después de leer y antes de devolver el resultado. Con la tubería bloqueada y un temporal parcial, se canceló la operación que compartían dos solicitudes. Antes y después de liberar la lectura no se devolvieron resultados. Al liberar la lectura se verificaron el cierre del descriptor, la eliminación del temporal y la liberación del permiso nuevo. Un nuevo intento volvió a abrir el URI y tuvo éxito. También se verificó la recuperación de un temporal huérfano sin borrar un archivo ajeno.
- **CA-06.** Las solicitudes concurrentes del mismo URI compartieron una apertura y un resultado. Cancelar una solicitud que esperaba permitió que continuara la operación compartida. Una selección posterior comprobó el acceso y reutilizó el resultado de la sesión sin releer los datos. La referencia y los descriptores permanecieron disponibles después de eliminar el temporal.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug` y `:app:assembleDebugAndroidTest` | Correctas. Se generaron la APK debug y la APK instrumentada. |
| `./gradlew.bat :app:testDebugUnitTest` | `NO-SOURCE`. Gradle ejecutó 0 pruebas unitarias. |
| `./gradlew.bat :app:lintDebug` | 0 errores y 1 advertencia preexistente, `MissingApplicationIcon`. |
| `adb shell am instrument -r -w -e phase repository <runner>` | Los 6 casos instrumentados de T06 pasaron en API 37 en dos ejecuciones. |
| `adb shell am instrument -r -w -e phase access <runner>` y `-e phase reopen` tras `am force-stop com.agustin.sumi` | Pasaron los 7 casos instrumentados de regresión de T03 en API 37. |

El Wrapper terminó con código 0 con `--offline --no-daemon --console=plain --max-workers=2`. El runner fue `com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest`. La ejecución instrumentada usó ADB. Los registros quedaron en `.gradle/spec-002-t06/`.

La integración verifica V05 a V07, V21, V23 y V24 a nivel del repositorio con un proveedor controlado. La UI de selección y reselección corresponde a T07. Quedan pendientes API 23, proveedores SAF externos y los ensayos integrales de la matriz con archivos representativos cerca de los límites de producción. El resultado en caché comprueba que el URI siga accesible durante la sesión. Los cambios de contenido bajo el mismo URI quedan para la reapertura y validación del documento.

**Validación de T06 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo la validación de T06 y la implementación exclusiva de T07 de SPEC-002». Se verificó la evidencia obligatoria de las dos rutas SAF, reselección, deduplicación, cancelación y limpieza, incluidos los seis casos instrumentados en dos ejecuciones y las siete regresiones de T03. T06 queda `Validated`. Se conservan los pendientes registrados arriba.

## T07 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobó que SPEC-002 está `Approved`, T06 está `Validated` y T07 cuenta con autorización explícita. Se consultaron `android-clean-architecture` y `mobile-android-design`. El ensamblado quedó en `MainActivity`, el estado y la UI en `feature/library`, y la importación en el repositorio existente.

- **CA-01 y V01–V02.** El botón abre `ACTION_OPEN_DOCUMENT` para un solo archivo de lectura, con `CATEGORY_OPENABLE` y los cuatro MIME aprobados. El selector del sistema permitió importar un documento ofrecido como CBZ y otro como ZIP con modo avión activado, Wi-Fi desactivado y sin red predeterminada. Cancelar conservó el resultado anterior y permitió volver a abrir el selector. El documento de prueba con MIME `application/octet-stream` apareció deshabilitado. [Contrato de Android para documentos](https://developer.android.com/guide/components/intents-common)
- **CA-02 y CA-05.** El repositorio sigue gestionando permisos, validación y temporales. La UI presenta espera, cantidad de páginas importadas y errores recuperables. Se seleccionó un documento cuyo proveedor rechazaba la apertura, se comprobó el mensaje de acceso y se importó otro archivo mediante reselección.
- **Accesibilidad.** La pantalla usa el tema Material 3, una acción con etiqueta textual y un área táctil mínima de 48 dp comprobada en instrumentación. Los cambios de estado se exponen en una región activa de accesibilidad.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest` | Correcta. Se generaron las APK de la app y de instrumentación. |
| `./gradlew.bat :app:testDebugUnitTest` | `NO-SOURCE`. Gradle ejecutó 0 pruebas unitarias. |
| `./gradlew.bat :app:lintDebug` | 0 errores y 1 advertencia preexistente, `MissingApplicationIcon`. |
| `adb -s emulator-5562 shell am instrument -r -w -e phase picker <runner>` | 6 comprobaciones instrumentadas pasaron en API 37 con el selector real y un `DocumentsProvider` de prueba. |

El Wrapper terminó con código 0 con `--offline --no-daemon --console=plain --max-workers=2`. El runner fue `com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest`. El estado de red se comprobó con ADB y la instrumentación se ejecutó fuera de Gradle. Los registros quedaron en `.gradle/spec-002-t07/`.

Quedan pendientes API 23 y las verificaciones integrales de la matriz en dispositivos y proveedores representativos. El comportamiento del MIME genérico se observó en el selector y el proveedor probados.

**Validación de T07 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo explícitamente la validación de T07 y la implementación exclusiva de T08 de SPEC-002». Se cotejaron los criterios de T07 con las seis comprobaciones instrumentadas en API 37 y la evidencia del Wrapper. T07 queda `Validated`. El filtro aprobado se conserva y la compatibilidad con `application/octet-stream` sigue siendo opcional. API 23 y otros proveedores SAF quedan como verificaciones de compatibilidad.

## T08 — Evidencia de ejecución del 12 de noviembre de 2026

Se comprobó el cierre de T07, la aprobación de SPEC-002 y la autorización explícita de T08. Se consultaron `android-clean-architecture` y `mobile-android-design` para revisar la separación de capas y los resultados de UI. Se agregaron dos fases de instrumentación para interrumpir una copia real y verificar su recuperación al reiniciar el proceso.

| Comprobación | Resultado |
| --- | --- |
| `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` | Código 0 y `BUILD SUCCESSFUL`. `assembleDebug` quedó al día y se generó la APK de instrumentación. `testDebugUnitTest` devolvió `NO-SOURCE` y ejecutó 0 pruebas. Lint informó 0 errores y 1 advertencia preexistente, `MissingApplicationIcon`. |
| `java -cp … CbzArchiveReaderTestKt` y `TemporaryCbzStoreTestKt` | 53 casos autónomos pasaron en JDK 21, 44 de archivo y páginas y 9 de temporales. |
| `d8 --min-api 37 …` y `adb shell dalvikvm -cp …` con los mismos dos puntos de entrada | Pasaron los mismos 53 casos en Android API 37. |
| `adb shell am instrument -r -w -e phase <fase> <runner>` | 21 comprobaciones distintas pasaron en API 37. Fueron 6 del selector, 7 de acceso y reapertura, 6 del repositorio y 2 de recuperación tras interrupción. La fase del selector se repitió, por lo que hubo 27 ejecuciones de comprobaciones. |
| `adb shell am force-stop com.agustin.sumi` durante `interrupt-copy`, seguido de `recover-copy` | La fase interrumpida terminó intencionalmente con código de instrumentación 0. Se comprobó que sobrevivió una copia de 12 bytes. Tras el reinicio, se reabrió un CBZ ya importado con el URI persistido y se eliminó la copia parcial conservando el archivo de control. |
| `java -Xmx24m -cp … LargeCbzProbeKt` y `adb shell dalvikvm -Xmx24m -cp … LargeCbzProbeKt` | Un CBZ sintético de 64 páginas JPEG de 1600 × 2200 píxeles y 202.111.268 bytes produjo los mismos 64 descriptores por canal y archivo en ambos entornos. Se generó con cuatro JPEG de ruido pseudoaleatorio (semilla 20261112) almacenados en el ZIP. El límite del heap fue de 24 MiB. Tiempos de la sonda en canal y archivo, respectivamente, de 141 y 60 ms en JDK 21 y de 123 y 42 ms en el emulador. Son mediciones del lector sobre el fixture, con la caché del sistema sin controlar. |

El Wrapper usó `--offline --no-daemon --console=plain --max-workers=2` y el daemon tomó JDK 25 de la configuración local preexistente. La instrumentación se ejecutó con ADB mediante `com.agustin.sumi.test/com.agustin.sumi.data.local.document.SafDocumentSourceTest`. En la primera lectura del estado de red, el emulador todavía mostraba una red predeterminada. La segunda ejecución del selector confirmó `Active default network: none` antes y después. Los registros se guardaron en `.gradle/spec-002-t08/`.

La [matriz de verificación](verification.md#evidencia-integral-de-t08) registra la cobertura de CA-01 a CA-06 y el nivel de cada comprobación.

## Revisión final y cierre

**Validación de T08 y SPEC-002 — 12 de noviembre de 2026.** El usuario autorizó el cierre en el mensaje «Autorizo la validación de T08 y el cierre condicionado de SPEC-002». Se verificó que T01 a T07 ya estaban `Validated` y que T08 registró comandos, fixtures, resultados y pendientes. El Wrapper terminó correctamente, con 0 pruebas unitarias por `NO-SOURCE`. Pasaron los 53 casos autónomos en cada entorno y las 21 comprobaciones instrumentadas distintas en API 37. Lint informó 0 errores y la advertencia preexistente `MissingApplicationIcon`.

Se confirmaron CA-01 a CA-06 con la evidencia de la matriz. Para CA-02 se comprobó el rechazo del permiso no persistible en el adaptador y la reselección ante un error de acceso en la UI. La revisión del código confirmó que ese rechazo usa el mismo mensaje recuperable y la misma acción de reselección. Para CA-05 se usaron límites reducidos y espacio insuficiente simulado, según la estrategia de pruebas aprobada, además de la interrupción real de una copia y la sonda con un CBZ de 192,7 MiB. T08 y SPEC-002 quedan `Validated`.

Se conservan como verificaciones adicionales API 23, proveedores SAF externos, el recorrido completo de UI con un proveedor que no otorgue persistencia, falta de espacio real y mediciones cerca de los límites en hardware representativo. `application/octet-stream` apareció deshabilitado y su compatibilidad sigue siendo opcional.

[Especificación](spec.md) · [Plan técnico](plan.md)
