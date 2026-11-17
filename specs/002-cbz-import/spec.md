# SPEC-002 — Importación de archivos CBZ

**Estado: Validated.** El usuario aprobó SPEC-002 el 12 de noviembre de 2026 en el mensaje «Autorizo explícitamente el cambio de estado de SPEC-002 a Approved». Ese mismo día autorizó el cierre condicionado en el mensaje «Autorizo la validación de T08 y el cierre condicionado de SPEC-002». La revisión confirmó los criterios CA-01 a CA-06 y el cierre de T01 a T08. [Revisión final y autorización](tasks.md#revisión-final-y-cierre) · [Matriz y verificaciones pendientes](verification.md#evidencia-integral-de-t08)

## Objetivo

Permitir que una persona seleccione un CBZ disponible en el dispositivo y reciba un resultado de importación con una referencia al documento y una lista ordenada de páginas. El resultado debe poder integrarse más adelante con la biblioteca y el lector. [Alcance del producto](../../docs/product.md)

**Base técnica.** El setup Android está implementado y versionado en Git. [SPEC-001](../001-project-setup/spec.md) figura como `Approved`. Su cierre sigue pendiente de la evidencia de compilación desde un checkout versionado registrada en [sus tareas](../001-project-setup/tasks.md#revisión-final--11-de-noviembre-de-2026).

## Requisitos funcionales

| ID | Requisito |
| --- | --- |
| RF-01 | Ofrecer una acción mínima de importación que abra el selector SAF mediante `ACTION_OPEN_DOCUMENT`. Filtrar tipos MIME compatibles con CBZ y ZIP y validar el contenido seleccionado. Cancelar el selector vuelve al estado previo. |
| RF-02 | Conservar el URI como referencia al origen y solicitar permiso persistente de lectura. Priorizar la lectura directa y usar una copia privada temporal si el proveedor carece de acceso aleatorio utilizable. Comprobar el acceso al reabrir el URI y ofrecer reselección si el permiso se revoca o el archivo desaparece. |
| RF-03 | Verificar la estructura ZIP, incluido el directorio central, su registro de fin y la coherencia de las entradas con sus cabeceras locales. Comprobar el CRC de cada imagen admitida mediante lectura por bloques. Distinguir archivo de cero bytes, ZIP vacío, contenedor inválido o corrupto, y ZIP válido sin páginas compatibles. |
| RF-04 | Identificar páginas JPG, JPEG, PNG y WebP por contenido, incluidas las de carpetas internas y con extensiones en mayúsculas. Omitir directorios y archivos auxiliares. Aplicar el [orden natural y los desempates](plan.md#orden-e-identidad). |
| RF-05 | Entregar un resultado completo con referencia opaca al origen y descriptores ordenados de página. Evitar resultados duplicados para el mismo URI durante la sesión de importación y evitar el hash completo por defecto. Mostrar errores recuperables sin cerrar la app. |

## Requisitos no funcionales

- El flujo principal funciona con archivos disponibles localmente y con el dispositivo sin conexión.
- La lectura y validación usan coroutines y `Dispatchers.IO`. Se procesa una entrada a la vez, por bloques y con memoria acotada. La cancelación impide publicar resultados parciales o registrar un éxito, incluso si una lectura bloqueante sigue pendiente. Se cierran los recursos y se eliminan los temporales propios al terminar. Si una lectura sigue bloqueada al cancelar, la limpieza se verifica cuando se libera la operación. Al reiniciar la app se limpian los temporales de importaciones interrumpidas antes de admitir una nueva importación.
- Los límites se verifican con valores reales leídos y escritos. Los metadatos sirven para rechazar temprano, y una entrada que exceda un límite se rechaza aunque declare un tamaño menor.
- Los nombres de entradas se tratan como datos no confiables. Se rechazan rutas absolutas y recorridos de directorio. Los nombres internos se usan para identificar páginas dentro del ZIP. Los diagnósticos excluyen URIs y nombres de archivos del usuario.

### Límites iniciales

| Recurso | Máximo |
| --- | --- |
| Contenedor CBZ comprimido | 2 GiB |
| Entradas ZIP, incluidos directorios y archivos auxiliares | 5000 |
| Cada imagen expandida | 100 MiB |
| Total expandido del ZIP | 4 GiB |
| Copia temporal privada | El menor entre 2 GiB y el espacio disponible |

## Importación y lectura posterior

La importación valida el contenido accesible en ese momento y devuelve una referencia opaca al URI y los descriptores de página. El permiso persistido permite intentar reabrir el documento. El archivo puede moverse, desaparecer, cambiar o quedar inaccesible. El temporal pertenece a la operación que lo creó y su ruta queda fuera del resultado.

La futura lectura deberá reabrir el URI y comprobar el acceso. Si el proveedor requiere respaldo, la operación de lectura deberá crear su propio temporal. Si el origen no está disponible, deberá ofrecer reselección. Esta especificación define el contrato para esa integración. [ADR-003](../../docs/decisions/ADR-003-cbz-document-access.md) · [Permisos SAF](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions)

## Criterios de aceptación

| ID | Verificación esperada |
| --- | --- |
| CA-01 | Con el dispositivo sin conexión, un CBZ local ofrecido como CBZ o ZIP puede seleccionarse desde la UI. Cancelar el selector conserva el resultado previo y permite elegir otro archivo. |
| CA-02 | Tras reiniciar la app, el adaptador puede reabrir el URI persistido si el documento sigue disponible. Un URI revocado, ausente o sin permiso persistible produce un error recuperable y permite reselección. Se verifica acceso directo sin copia cuando admite todas las comprobaciones y respaldo temporal con un proveedor sin acceso aleatorio. |
| CA-03 | Cero bytes, ZIP vacío, contenedor ajeno, ZIP sin imágenes, directorio central ausente o incoherente y CRC incorrecto producen los resultados previstos. Se rechaza un archivo que permite recorrer entradas locales con `ZipInputStream` y carece de un directorio central válido. Una imagen con firma admitida y CRC incorrecto se rechaza aunque conserve la extensión y el tamaño declarado. |
| CA-04 | Un CBZ mixto devuelve las páginas con firmas JPG/JPEG, PNG o WebP, incluidas las de carpetas internas. Una extensión de imagen con bytes ajenos no se acepta como página. El orden sigue el comparador documentado y es reproducible para `page2.jpg`, `page02.jpg`, `page10.jpg` y los empates de mayúsculas. |
| CA-05 | Se rechazan rutas inseguras, archivos que exceden los límites reales y copias para las que falta espacio. Las pruebas cubren cada límite y el primer exceso, directorio central sobredimensionado, ZIP64 y cancelación durante copia o lectura. Al cancelar una lectura bloqueada simulada, no se publican resultados parciales ni se registra éxito, aun si la fuente devuelve datos al liberarse. Se comprueba el cierre de recursos y la eliminación de temporales al terminar, incluida la salida de la lectura bloqueada. Tras reiniciar la app se limpian los temporales huérfanos propios y se conservan archivos ajenos. |
| CA-06 | El resultado devuelve una referencia opaca al origen y un descriptor por página, incluso con nombres base iguales en carpetas distintas. Sigue identificando el origen después de eliminar el temporal. Selecciones repetidas o concurrentes del mismo URI generan un solo resultado durante la sesión. El contrato excluye tipos Android, rutas temporales, recursos abiertos y detalles ZIP. |

## Exclusiones

Lector visual, zoom, navegación entre páginas, biblioteca persistente con Room, deduplicación entre sesiones, descargas, catálogos online, EPUB, PDF, CBR, copias permanentes del CBZ y extracción permanente de sus entradas.

## Decisiones técnicas y verificación pendiente

| ID | Decisión o seguimiento |
| --- | --- |
| D01 | URI persistente de solo lectura, acceso directo preferido y copia privada temporal como respaldo. [ADR-003](../../docs/decisions/ADR-003-cbz-document-access.md) |
| D02 | ZIP estándar con `ZipFile` o `ZipInputStream` según el acceso. En ambos casos se exige la validación estructural y de integridad definida en el plan. |
| D03 | Aplicar los límites iniciales y comprobar en dispositivos representativos el coste de I/O y memoria. La cancelación bloqueada se verifica con una fuente simulada sin exigir un plazo fijo para proveedores externos. [Asignación de riesgos](plan.md#riesgos-y-manejo-de-errores) |
| D04 | Orden natural con desempates estables y deduplicación por URI durante la sesión. |
| D05 | Acción mínima de importación y filtros MIME CBZ/ZIP. Los proveedores que no puedan abrir el documento offline devuelven un error recuperable. |
| D06 | Coroutines con `Dispatchers.IO`. La versión o incorporación directa y cualquier herramienta de pruebas adicional quedan sujetas a revisión y autorización. |

[Plan técnico](plan.md) · [Tareas propuestas](tasks.md)
