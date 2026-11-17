# SPEC-003 — Biblioteca local de Sumi

**Estado.** `Validated` el 16 de noviembre de 2026, por autorización explícita de cierre de SPEC-003. La revisión confirmó T01–T14, CA-01 a CA-10 y RNF-03. La evidencia, los resultados y las verificaciones adicionales pendientes están en [tasks.md](tasks.md). La aprobación original se registró el 12 de noviembre de 2026, a partir del mensaje «Autorizo explícitamente la aprobación condicionada de SPEC-003: Biblioteca local de Sumi».

T01 debe seleccionar y documentar las versiones compatibles de las dependencias de prueba antes de incorporarlas y verificar que el daemon use JDK 21. KSP2 2.3.12 está autorizado como candidato, sujeto a la integración con Room 2.8.5 en T03. Si resulta incompatible, detener la tarea y solicitar autorización antes de cambiar la versión. El [plan](plan.md) y las [tareas](tasks.md) identifican la evidencia pendiente.

## Objetivo y alcance

Consultar y organizar los CBZ importados desde una biblioteca persistente que funcione sin conexión. La biblioteca ofrece tres modalidades obligatorias de importación, muestra títulos, portadas y disponibilidad, y permite buscar, ordenar, quitar entradas o volver a seleccionar un archivo inaccesible. Importar una carpeta es una acción puntual. La sincronización automática de carpetas queda fuera del alcance.

Quedan fuera el lector visual, el progreso de lectura, los catálogos online, otros formatos, las colecciones avanzadas, la búsqueda avanzada y FTS. Se aplican el [alcance del producto](../../docs/product.md), la [arquitectura aprobada](../../docs/architecture.md) y el [contrato de importación de SPEC-002](../002-cbz-import/spec.md).

## Requisitos funcionales

- **RF-01.** Guardar en Room cada CBZ validado cuyo origen pueda reabrirse con permiso persistente de lectura. La escritura es atómica por archivo. Un archivo inválido, cancelado antes de confirmar su escritura o cuya escritura falle no crea una entrada. Los éxitos ya confirmados se conservan tras errores, cancelación o reinicio. Dos documentos distintos pueden compartir título.
- **RF-02.** Mostrar títulos y portadas en una cuadrícula adaptable. Buscar por título sin distinguir mayúsculas y ofrecer A–Z, Z–A o fecha de incorporación descendente. Desempatar por ID interno y conservar la preferencia de orden al reiniciar.
- **RF-03.** Generar bajo demanda la miniatura de la primera página válida según el orden de SPEC-002, con APIs de Android. Limitar la dimensión mayor a 512 píxeles, mantener la proporción y evitar ampliar imágenes pequeñas. Mostrar una portada de respaldo durante la carga, ante un fallo o si el documento queda inaccesible. Room guarda solo metadatos de portada.
- **RF-04.** Permitir quitar una entrada y descartar su portada derivada. Mantener intacto el original. Liberar una concesión administrada por la biblioteca solo cuando ningún libro dependa de ella ni haya operaciones activas que la usen.
- **RF-05.** Mantener visible la entrada cuando el documento desaparezca o el permiso se revoque. Ofrecer reseleccionar el archivo o el árbol afectado. Al reseleccionar un árbol, recuperar las entradas cuyos descendientes coincidan por identidad SAF y sigan accesibles. Al reemplazar un archivo, validar el nuevo documento y conservar el ID interno. Una reselección cancelada, inválida o que apunte a un origen asociado a otra entrada conserva los registros previos.
- **RF-06.** Distinguir carga inicial, biblioteca vacía, búsqueda sin resultados y error de carga. Conservar el contenido disponible ante errores de una operación individual y ofrecer reintento cuando corresponda.
- **RF-07.** Ofrecer «Importar un archivo» con `ACTION_OPEN_DOCUMENT`, «Importar varios archivos» con `ACTION_OPEN_DOCUMENT` y `EXTRA_ALLOW_MULTIPLE`, e «Importar una carpeta» con `ACTION_OPEN_DOCUMENT_TREE`. Recorrer las subcarpetas accesibles dentro de los límites. En carpetas, admitir archivos `.cbz` y `.zip` sin distinguir mayúsculas, incluso con MIME genérico, y omitir archivos y subcarpetas cuyo nombre empiece con `.`. Las tres modalidades validan el contenido con SPEC-002. Cancelar el selector conserva la biblioteca.
- **RF-08.** Deduplicar dentro del lote y entre sesiones por el par SAF `authority` + `documentId`. Un mismo documento seleccionado directamente o desde un árbol corresponde a un solo libro con un ID interno independiente. Excluir nombre, URI completo y hash del contenido de la identidad. Persistir la concesión de lectura del documento o del árbol y comprobar el acceso a cada descendiente. Si falta una identidad SAF verificable o acceso durable, informar el error sin guardar el archivo. Si un origen guardado pierde acceso, aplicar RF-05.
- **RF-09.** Mostrar progreso, ofrecer cancelación y presentar un resumen de importados, duplicados, fallidos y pendientes conocidos al detenerse. Indicar si la exploración quedó incompleta y distinguir una carpeta sin candidatos de una carpeta que no pudo explorarse. Un error de archivo o de rama permite continuar con los elementos independientes mientras los recursos lo permitan. Al cancelar, dejar de admitir trabajo y conservar las escrituras ya confirmadas.

## Requisitos no funcionales

- **RNF-01.** Usar Room para la persistencia y Material 3 para la pantalla, dentro de `:app` y según los [límites de capa](../../docs/decisions/ADR-002-package-architecture.md). Mantener el dominio en Kotlin puro. La UI debe identificar acciones y estados con texto y semántica accesible.
- **RNF-02.** Aplicar la política de acceso y la ampliación para árboles de [ADR-003](../../docs/decisions/ADR-003-cbz-document-access.md). Procesar archivos e imágenes fuera del hilo principal, con límites de bytes, píxeles y concurrencia. Conservar solo miniaturas derivadas en una caché privada y regenerable. Excluir de los registros de diagnóstico los URI, títulos y rutas internas.
- **RNF-03.** Ejecutar pruebas unitarias JUnit con `testDebugUnitTest` y pruebas instrumentadas AndroidX Test con `AndroidJUnitRunner` mediante `connectedDebugAndroidTest`. Usar `room-testing` 2.8.5 para persistencia. Registrar conteos y resultados reales, conservar las regresiones de SPEC-002 y justificar cualquier sustitución de una verificación Gradle por una suite autónoma.
- **RNF-04.** Acotar trabajadores, cola, profundidad, nodos visitados, candidatos y temporales agregados. Recorrer solo el árbol concedido, detectar visitas repetidas y cerrar los recursos del proveedor. Mantener los límites de SPEC-002 por CBZ y comprobar espacio real disponible. Room conserva referencias y metadatos. El original permanece en SAF y el respaldo del contenedor es temporal.

## Límites de importación

| Recurso | Máximo |
| --- | --- |
| Profundidad de subcarpetas | 10 niveles. La carpeta seleccionada es el nivel 0. Se procesan los archivos de las carpetas del nivel 10 y se omiten las carpetas más profundas. |
| Nodos explorados | 10 000 filas de descendientes inspeccionadas, incluidos directorios, archivos ajenos y referencias repetidas. |
| Candidatos CBZ por lote | 1000 candidatos admitidos antes de deduplicar. Aplica a selección múltiple y carpeta. |
| Cola pendiente | 32 candidatos en espera, además de la validación activa. |
| Validaciones de CBZ simultáneas | 1, compartida con las reaperturas de portada que requieran validar el CBZ. |
| Copias temporales activas | 1, compartida entre importación y portadas. |
| Presupuesto temporal agregado | 2 GiB y sujeto al espacio disponible real. Incluye temporales propios pendientes de limpieza. |
| Metadatos en memoria | Objetivo de 16 MiB para metadatos retenidos durante importación y consulta de biblioteca. |
| Caché de portadas en RAM | Objetivo adaptable de hasta 32 MiB de imágenes cacheadas. Reducir el presupuesto ante presión de memoria. |
| Caché de portadas en disco | Máximo de 256 MiB, independiente del presupuesto de temporales. |

## Criterios de aceptación

| ID | Comprobación y resultado esperado |
| --- | --- |
| CA-01 | Elegir un CBZ con `ACTION_OPEN_DOCUMENT` individual. La entrada aparece después de confirmar la escritura y se puede reabrir tras relanzar el proceso sin conexión. Cancelar el selector o rechazar un CBZ inválido conserva la biblioteca. |
| CA-02 | Repetir un documento dentro de un lote, tras reiniciar y desde URI directos y de árbol con el mismo par `authority` + `documentId`. Queda un solo ID interno, incluso ante escrituras concurrentes. Pares diferentes con el mismo título o contenido se guardan por separado. Un candidato sin identidad SAF verificable produce un fallo individual. |
| CA-03 | Buscar un fragmento con distinta capitalización y limpiar la búsqueda. Verificar A–Z, Z–A y fecha descendente con datos fijos y empates por ID interno. Seleccionar un orden distinto del inicial y confirmar que se restaura al relanzar el proceso. |
| CA-04 | Solicitar la portada y comprobar que corresponde a la primera página válida. Verificar 512 px máximos, proporción y conservación del tamaño de una imagen pequeña. Comprobar regeneración, limpieza, desalojo antes de 256 MiB y respaldo cuando el origen queda inaccesible, incluso con una miniatura cacheada. Medir el objetivo adaptable de 32 MiB en RAM. |
| CA-05 | Quitar un libro con portada y relanzar Sumi. Desaparecen la entrada y su miniatura. El original queda intacto y se puede reimportar. Con dos libros bajo una concesión, quitar uno conserva el acceso del otro. Al quitar el último, liberar la concesión administrada una vez finalizado su uso activo. |
| CA-06 | Simular un permiso revocado y un archivo ausente. Conservar título e ID interno y ofrecer reselección. Reseleccionar un árbol recupera los descendientes accesibles con la misma identidad SAF. Reseleccionar un archivo válido recupera la misma entrada. Cancelar o elegir un archivo inválido o asociado a otra entrada conserva los registros previos. |
| CA-07 | Mostrar la cuadrícula con títulos y portadas en dos anchos de pantalla. Simular carga, biblioteca vacía, búsqueda sin resultados y fallo de Room. Cada estado muestra el mensaje y la acción correspondiente. Verificar etiquetas, roles y áreas táctiles de los controles. |
| CA-08 | Seleccionar varios documentos con `ACTION_OPEN_DOCUMENT` y `EXTRA_ALLOW_MULTIPLE`. Con dos CBZ válidos, un inválido y una referencia repetida, quedan dos altas durables y el resumen indica dos importados, un fallido y un duplicado. Verificar también el resultado de un único URI en modo múltiple y un fallo de Room aislado. |
| CA-09 | Seleccionar un árbol con `.cbz` y `.zip` válidos en raíz y subcarpetas, incluido MIME genérico. Rechazar contenido inválido y omitir archivos ajenos, ocultos y ramas inaccesibles. Verificar reapertura tras reiniciar, rechazo de URI fuera del árbol, revocación y ausencia de altas si el permiso del árbol no se puede persistir. |
| CA-10 | Verificar los límites de importación y sus bordes con escenarios acotados, un ciclo y agotamiento de espacio. Medir metadatos frente al objetivo de 16 MiB. Cancelar con un archivo confirmado y otro en lectura bloqueada. Solo permanece el confirmado. Al liberar la lectura, comprobar cierre y limpieza sin altas tardías. El resumen comunica límites, omisiones conocidas y exploración incompleta. |

Las propuestas y decisiones pendientes se registran en el [plan](plan.md). La [matriz de tareas](tasks.md) vincula los criterios con la evidencia prevista.
