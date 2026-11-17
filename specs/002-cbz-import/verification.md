# SPEC-002 — Matriz de verificación de T01

La matriz convierte [CA-01 a CA-06](spec.md#criterios-de-aceptación) y las decisiones D01 a D06 en casos para las tareas de implementación. La [evidencia integral de T08](#evidencia-integral-de-t08) registra los resultados y las variantes que siguen pendientes.

## Preparación de los casos

- Usá CBZ sintéticos y archivos de prueba propios. El caso válido de referencia contiene dos imágenes PNG pequeñas y un archivo de texto, con cabeceras, directorio central y CRC correctos.
- Para los bordes de recursos, verificá primero los valores de producción de D03. Usá límites de prueba reducidos e independientes para aislar cada control. En cada borde, compará un caso en el máximo con otro que lo exceda por una entrada o un byte. Mantené los demás recursos dentro de sus límites.
- Registrá la versión de Android, el proveedor, la ruta de acceso, el resultado y el estado de los recursos o temporales cuando aplique. La selección de herramientas y versiones adicionales de D06 requiere autorización.

## Selección y acceso al origen

| Caso | Criterio | Entrada y pasos | Resultado esperado |
| --- | --- | --- | --- |
| V01 | CA-01 | En modo avión, abrí la acción de importación y elegí el CBZ de referencia. Repetí con un proveedor que lo anuncie como CBZ y otro que lo anuncie como ZIP. | Ambos documentos locales pueden seleccionarse e importarse. |
| V02 | CA-01 | Con un resultado válido visible, abrí el selector y cancelalo. Volvé a abrirlo. | Se conserva el resultado previo y el selector puede abrirse otra vez. |
| V03 | CA-02 | Seleccioná un documento con permiso persistible de lectura, completá la importación, reiniciá la app y reabrí el mismo URI desde el adaptador mientras el documento sigue disponible. | Se conserva el acceso de lectura y se puede reabrir el URI. |
| V04 | CA-02 | Seleccioná un documento con lectura temporal cuyo proveedor no conceda permiso persistible. | Se informa un error recuperable, se ofrece elegir otro origen y no se registra una importación exitosa. |
| V05 | CA-02 | Tras una importación válida, revocá el permiso o quitá el documento en ejecuciones separadas. Intentá reabrir el URI. | El error permite reseleccionar. El resultado previo no se usa como evidencia de que el documento siga accesible. |
| V06 | CA-02 | Serví el CBZ de referencia desde un proveedor SAF que permita comprobar longitud, estructura y entradas mediante acceso directo. Observá el directorio temporal. | Se obtienen los descriptores esperados sin crear una copia. |
| V07 | CA-02, CA-05 | Serví los mismos bytes desde un proveedor que solo entregue un flujo secuencial. Observá el temporal durante la validación y al terminar. | Se crea una copia privada acotada. El resultado equivale al acceso directo y se elimina el temporal. |

## Estructura, integridad e imágenes

| Caso | Criterio | Entrada y pasos | Resultado esperado |
| --- | --- | --- | --- |
| V08 | CA-03 | Importá por separado un archivo de cero bytes, un ZIP válido sin entradas, bytes ajenos al formato ZIP con extensión `.cbz`, y un ZIP válido con solo archivos de texto. | Se distinguen archivo vacío, ZIP vacío, formato inválido y ausencia de imágenes compatibles. |
| V09 | CA-03 | Quitá el directorio central y el registro de fin del CBZ de referencia, conservando las entradas locales legibles. Probá también offsets y longitudes del comentario incoherentes. En el CBZ válido, compará archivos sin comentario, con comentario convencional y con una o varias firmas EOCD falsas en un comentario de hasta 65.535 bytes. Incluí un candidato anterior al EOCD real con un directorio incoherente. Repetí por ambas rutas. | Se aceptan los archivos válidos después de verificar el EOCD y su directorio. Las firmas falsas se descartan. Un archivo con el EOCD real corrupto se rechaza aunque contenga firmas señuelo o entradas locales legibles. |
| V10 | CA-03, CA-05 | Prepará un ZIP64 estructuralmente válido dentro de los límites y variantes truncadas o con offsets o recuentos incoherentes. Repetí el caso válido y el de localizador corrupto con una firma EOCD falsa en el comentario. Ejecutá por acceso directo viable y respaldo temporal. | Se rechazan los ZIP64 malformados. El caso válido debe completar los controles para poder admitirse. T04 documenta el soporte efectivo y cualquier variante incompatible con el parser acotado. |
| V11 | CA-03 | En una entrada almacenada sin compresión, alterá un byte posterior a la firma de una imagen y conservá el tamaño y CRC originales. Repetí con y sin una firma EOCD falsa en el comentario, por ambas rutas. | Se rechaza por CRC incorrecto aunque coincidan firma, extensión y tamaño. |
| V12 | CA-04 | Usá imágenes reales con nombres `page10.jpg`, `page2.jpg`, `page02.jpg`, `extra/cover.JPEG`, `extra/scan.PNG` y `extra/back.webp`. Agregá un directorio, un archivo de texto y un `fake.jpg` con bytes de texto. | Los descriptores incluyen solo imágenes compatibles. Entre las tres páginas numeradas, el orden es `page2.jpg`, `page02.jpg`, `page10.jpg`. |
| V13 | CA-04 | Compará rutas por segmentos con números en carpeta y página, números con muchos dígitos y un empate de mayúsculas. Repetí con otro idioma de dispositivo y distinto orden de enumeración. | El orden natural y los desempates del plan son deterministas. La secuencia numérica no se desborda y el idioma no altera el resultado. |

## Límites, cancelación y limpieza

| Caso | Criterio | Entrada y pasos | Resultado esperado |
| --- | --- | --- | --- |
| V14 | CA-05 | Agregá por separado `../page.jpg`, `/page.jpg`, `C:\page.jpg` y un recorrido con barras invertidas a un ZIP válido. | Cada contenedor se rechaza. Los archivos ajenos y los ubicados fuera del directorio de temporales se conservan. |
| V15 | CA-05 | Probá longitud comprimida en el máximo y a un byte de exceso. En la ruta secuencial, simulá tamaño desconocido o menor que el real. El valor de producción es 2 GiB. | El borde permitido continúa la validación. El exceso se detecta por longitud o bytes leídos, sin publicar una importación. |
| V16 | CA-05 | Probá el máximo de entradas y una adicional, incluyendo directorios y auxiliares. Usá también un directorio central sobredimensionado con campos largos y recuento declarado menor al real. El valor de producción es 5000. | El conteo incluye todas las entradas. El exceso y la estructura engañosa se detectan antes de cargar un índice potencialmente grande. |
| V17 | CA-05 | Probá imágenes de tamaño expandido en el máximo y a un byte de exceso. Usá una entrada de alta expansión y metadatos que subestimen el tamaño real. El valor de producción es 100 MiB por imagen. | Se admiten tamaños dentro del límite. En el exceso, la lectura por bloques se detiene y el resultado no se publica. |
| V18 | CA-05 | Sumá imágenes y auxiliares que individualmente estén dentro de sus límites. Generá un caso cuyo total expandido alcance el máximo y otro que lo exceda en un byte. El valor de producción es 4 GiB. | Se cuentan los bytes reales de imágenes y auxiliares. El exceso acumulado se rechaza aunque cada imagen quepa por separado. |
| V19 | CA-05 | Forzá la ruta temporal. Probá el borde de copia, una copia que lo excede y un fallo por falta de espacio durante la escritura. El máximo de producción es el menor entre 2 GiB y el espacio disponible. | La copia válida respeta el presupuesto. El exceso o la falta de espacio producen un error controlado y se limpia el archivo parcial. |
| V20 | CA-05 | Señalizá que se empezó a copiar o a inspeccionar una entrada. Cancelá durante cada fase. Inyectá también un error de lectura después de que se haya creado el temporal. | La operación termina sin éxito ni resultado parcial. Se cierran los recursos y se elimina el temporal. |
| V21 | CA-05 | En T06, simulá una fuente que señalice que está bloqueada con un temporal propio ya creado. Cancelá y comprobá los eventos antes de liberar la fuente. Liberala desde la limpieza de la prueba haciendo que devuelva datos tardíos y esperá la finalización. | Antes y después de liberar la lectura hay cero resultados parciales y cero registros de éxito. Tras liberarla se cierran los recursos y se elimina el temporal. El caso se sincroniza con señales, sin exigir un plazo de interrupción a proveedores externos. |
| V22 | CA-05 | Interrumpí el proceso durante una copia y reiniciá la app con un temporal huérfano identificable y un archivo ajeno de control. Intentá una nueva importación. | El temporal propio se limpia antes de aceptar la siguiente importación. El archivo ajeno se conserva. |

## Contrato y deduplicación

| Caso | Criterio | Entrada y pasos | Resultado esperado |
| --- | --- | --- | --- |
| V23 | CA-06 | Importá un CBZ mediante la ruta temporal con `a/page1.jpg` y `b/page1.jpg`. Inspeccioná el resultado tras eliminar la copia y revisá las firmas públicas de dominio. Incluí un ZIP con dos entradas de la misma ruta normalizada. | La referencia identifica el URI después de limpiar el temporal. Las páginas en rutas distintas tienen identificadores distinguibles. La ruta duplicada se rechaza. El contrato carece de tipos Android, rutas temporales, recursos abiertos y tipos del parser. |
| V24 | CA-06 | Importá el mismo URI dos veces en una sesión y repetí con dos solicitudes concurrentes coordinadas por señales. Observá el trabajo del repositorio y los resultados registrados. | Se registra un solo resultado exitoso por URI en la sesión. La concurrencia agrupa el trabajo y la repetición reutiliza el resultado válido, sin calcular un hash completo del CBZ. |

## Evidencia integral de T08

Verificaciones ejecutadas el 12 de noviembre de 2026. La instrumentación usó el selector del sistema y proveedores de prueba en Android API 37. Las suites de archivo y temporales se ejecutaron en JDK 21 y en Android API 37. [Comandos y recuentos](tasks.md#t08--evidencia-de-ejecución-del-12-de-noviembre-de-2026)

| Casos | Criterio | Evidencia y alcance |
| --- | --- | --- |
| V01–V02 | CA-01 | El selector importó CBZ y ZIP sin red predeterminada. Cancelar conservó el resultado y permitió reabrirlo. El MIME genérico apareció deshabilitado en el proveedor de prueba. |
| V03–V07 | CA-02, CA-05 | Un CBZ importado se reabrió con su permiso persistido tras reiniciar el proceso. Se comprobaron ausencia de permiso persistible en el adaptador, revocación y documento ausente en el repositorio, y reselección ante documento inaccesible en la UI. Las rutas directa y temporal devolvieron páginas equivalentes. Queda pendiente la variante completa de UI con un proveedor que no permita persistir el permiso. |
| V08–V11 | CA-03, CA-05 | Las pruebas del lector distinguieron los resultados de archivos vacíos, ajenos al formato o sin imágenes y rechazaron directorios incoherentes, ZIP64 malformado y CRC incorrecto. Se incluyó la regresión de firmas EOCD falsas. El repositorio devolvió el error de un archivo inválido. |
| V12–V13 | CA-04 | Los fixtures mezclaron JPEG, PNG, WebP, directorios y contenido engañoso. Pasaron el orden natural, las rutas internas, los números largos, los empates de mayúsculas y el cambio de idioma. |
| V14–V18 | CA-05 | El lector rechazó rutas inseguras, directorio con recuento falso y excesos de entradas, longitud comprimida y expansión real. Se usaron límites reducidos para probar el máximo y el primer exceso. El lector procesó un CBZ sintético de 64 páginas y 192,7 MiB por canal y archivo con heap limitado a 24 MiB. |
| V19–V20 | CA-05 | Los casos autónomos comprobaron el presupuesto y el espacio insuficiente simulado, la cancelación cooperativa durante copia e inspección, y el cierre y limpieza ante error de lectura. La instrumentación comprobó la cancelación antes de entregar el resultado. Falta reproducir la falta de espacio con almacenamiento real. |
| V21–V22 | CA-05 | La lectura bloqueada cancelada produjo cero resultados y limpió descriptor y temporal al liberar la fuente. Detener el proceso durante otra copia dejó un temporal real de 12 bytes. El primer acceso tras reiniciar eliminó ese temporal y conservó el archivo ajeno. |
| V23–V24 | CA-06 | El resultado conservó su origen y páginas después de limpiar el temporal. Se comprobaron los identificadores distinguibles y el rechazo de rutas duplicadas. Las solicitudes concurrentes compartieron una apertura y las repetidas reutilizaron el resultado. La revisión de código confirmó que la deduplicación usa el URI y el hash se aplica a la ruta de página. |

API 23 y los proveedores externos siguen pendientes como verificaciones de compatibilidad. La sonda de tamaño aporta evidencia en un emulador, con mediciones cerca de los límites de producción en hardware representativo aún pendientes.
