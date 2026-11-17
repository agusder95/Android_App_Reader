# SPEC-004 — Lector visual de páginas CBZ

**Estado.** `Validated` el 17 de noviembre de 2026, por autorización explícita de cierre en el mensaje «Autorizo validar T08 y cerrar SPEC-004, condicionado a confirmar que toda la evidencia obligatoria cumple los criterios aprobados». La revisión final confirmó T01–T08 y CA-01 a CA-10. Se conservan las verificaciones adicionales pendientes registradas en `tasks.md`. Depende de [SPEC-002](../002-cbz-import/spec.md) y [SPEC-003](../003-local-library/spec.md), ambas `Validated`.

## Objetivo y alcance

Abrir desde la biblioteca un libro local y leer sus páginas en Sumi, sin conexión. Mostrar una página a la vez con dirección LTR inicial, RTL configurable por libro, navegación horizontal accesible y zoom básico de hasta 3x. Leer entradas ZIP individuales bajo demanda y guardar la última página confirmada. Reutilizar la identidad y referencia del libro, las reglas y límites de CBZ y SAF aprobados.

Incluye errores recuperables, permisos revocados, recursos acotados, cancelación, ciclo de vida Android y persistencia del progreso. Excluye importación, biblioteca, edición de originales, otros formatos, anotaciones, marcadores, historial, sincronización y catálogos online. El desplazamiento vertical continuo, páginas dobles, emparejamiento de portada, combinaciones avanzadas y decodificación regional no imprescindible se posponen a SPEC-005. No agrega ahora dependencias ni altera el código o Room v1.

## Requisitos funcionales propuestos

- **RF-01 — Apertura.** La acción sobre una entrada de biblioteca abre el lector por `LibraryEntryId`. Se obtiene el origen opaco actual desde Room; nunca se pasa una ruta temporal ni se confía en un URI guardado en la UI.
- **RF-02 — Páginas bajo demanda.** Mostrar una página a la vez en el orden natural y determinista de SPEC-002. Consultar la estructura ZIP sin descomprimir todas las páginas; abrir la entrada solicitada, verificar su CRC y tamaño real, y decodificar solo esa imagen. No extraer páginas permanentemente ni retener imágenes fuera de la ventana visible. Cada solicitud usa el origen actual.
- **RF-03 — Navegación y zoom.** Usar LTR al abrir por primera vez y permitir configurar LTR o RTL por libro. La secuencia de páginas mantiene el orden natural; la dirección cambia el sentido de navegación horizontal. Ofrecer acciones accesibles anterior/siguiente además de gesto horizontal. Permitir zoom de 1x a 3x y volver a ajustar a pantalla; los controles de zoom también deben ser accesibles sin gesto.
- **RF-04 — Progreso.** Guardar por `LibraryEntryId` la última página confirmada tras mostrarla y reanudarla al abrir. Persistir el progreso y la dirección por libro mediante migración Room v1→v2 no destructiva. No persistir estado de bitmap. Si el origen cambió y la página ya no existe, iniciar en una posición válida e informar el resultado sin perder el libro.
- **RF-05 — Acceso y errores.** Reabrir el URI con el permiso existente. Clasificar origen ausente o permiso revocado como recuperable mediante la reselección de SPEC-003; mostrar fallos de archivo, imagen, ZIP o almacenamiento sin cerrar la aplicación ni confirmar una página corrupta.
- **RF-06 — Recursos y cancelación.** Procesar I/O y decodificación fuera del hilo principal. Admitir una lectura y decodificación activa, sin precarga inicial. Mantener los bitmaps de páginas del lector en un objetivo de 32 MiB y un techo provisional de 64 MiB; ajustar el muestreo y liberar la página anterior antes de superar el techo. Cancelar trabajo obsoleto al cambiar de página o cerrar el lector. No publicar resultados tardíos. Cerrar cursores, canales, ZIP, streams y temporales incluso si un proveedor bloquea una lectura; la limpieza termina cuando la operación bloqueada devuelve el control.
- **RF-07 — Ciclo de vida.** Sobrevivir a recomposición y cambios de configuración sin duplicar sesiones. Al volver desde segundo plano, reabrir recursos cerrados y restaurar una página válida. Al cerrar el libro o destruir el proceso, liberar recursos y temporales propios; al reiniciar, limpiar temporales huérfanos identificables.

## Requisitos no funcionales

- Mantener `:app`, Compose/Material 3, MVVM y los límites de [ADR-002](../../docs/decisions/ADR-002-package-architecture.md). El dominio no importa Android; adaptadores SAF, ZIP y bitmap pertenecen a `data`.
- Cumplir los límites CBZ de SPEC-002: contenedor 2 GiB, 5000 entradas, imagen expandida 100 MiB y expansión total 4 GiB. Compartir con SPEC-003 el presupuesto agregado de temporales de 2 GiB, su comprobación de espacio y el arbitraje de operaciones; no duplicar un coordinador.
- Bitmaps de páginas del lector: objetivo de 32 MiB y techo provisional de 64 MiB para memoria asignada. No precargar; conservar solo la página actual y muestrear para el tamaño de presentación. Al acercar hasta 3x, priorizar el techo y aceptar menor nitidez; no se requiere decodificación regional. Verificar asignaciones y picos en API 23 y 37; el efecto combinado con la caché RAM de portadas de SPEC-003 debe medirse antes de confirmar el techo.
- Aplicar SAF de solo lectura, identidad por libro y permisos de ADR-003. Reabrir el URI al iniciar lectura; no asumir que un permiso persistido mantiene disponible el documento. Si se necesita copia temporal para acceso aleatorio, será privada, acotada, de vida de sesión y limpiable al reinicio.
- No registrar URI, título, nombres internos ni rutas. Ejecutar pruebas JVM mediante `testDebugUnitTest`, instrumentadas mediante `connectedDebugAndroidTest`, y conservar regresiones de SPEC-002 y SPEC-003.

## Criterios de aceptación propuestos

| ID | Resultado verificable |
| --- | --- |
| CA-01 | Desde una entrada validada de biblioteca, abrir el lector sin red, mostrar título y primera/última posición válida; regresar a biblioteca conserva su estado. Un ID inexistente ofrece error recuperable. |
| CA-02 | Un CBZ con nombres mixtos y carpetas internas presenta las páginas en el orden de SPEC-002. Al abrir, validar la estructura ZIP e inspeccionar como máximo 12 bytes descomprimidos por entrada candidata, solo para identificar JPG, PNG o WebP por firma. Medir por separado los bytes descomprimidos y los bytes comprimidos leídos. La lectura integral, la comprobación del tamaño real y el CRC corresponden únicamente a la página solicitada. |
| CA-03 | Con un CBZ grande se lee/decodifica solo la página solicitada y no hay precarga. El presupuesto de bitmaps tiene objetivo de 32 MiB y no supera el techo provisional de 64 MiB; al alcanzar el techo se reduce el muestreo y se libera la página reemplazada. Registrar picos en API 23/37. |
| CA-04 | LTR es la dirección inicial; cambiar a RTL se conserva por libro y cambia el sentido de gesto horizontal sin invertir el orden natural. Botones anterior/siguiente accesibles respetan la secuencia. El zoom ofrece ajuste a pantalla y escala hasta 3x, con controles accesibles. |
| CA-05 | Confirmar una página, cerrar/reabrir pantalla y proceso, y reanudarla. Otro libro conserva posición y dirección independientes. Una posición inexistente se corrige sin excepción ni pérdida del libro. La migración desde Room v1 conserva entradas, IDs y concesiones y crea progreso/dirección predeterminados. |
| CA-06 | Revocar el permiso, quitar el documento y simular fallo transitorio, ZIP/CRC inválido e imagen ilegible. El lector ofrece recuperación apropiada, no muestra contenido parcial corrupto y conserva el registro de biblioteca. |
| CA-07 | Cancelar un cambio rápido de página, cerrar la pantalla y cancelar una lectura bloqueada simulada. No se publica contenido tardío; recursos/temporales se cierran al liberar la lectura y no se exige plazo fijo a proveedores externos. |
| CA-08 | Recrear la pantalla por cambio de configuración y volver desde segundo plano. No quedan dos sesiones ni recursos abiertos duplicados; el progreso válido y la página se restauran. Forzar terminación y relanzar limpia temporales huérfanos propios. |
| CA-09 | Navegación y zoom se operan mediante controles con etiquetas/semántica accesibles y áreas táctiles de al menos 48 dp. Probar estados de carga, error, origen inaccesible y fin de lectura en dos tamaños de ventana. |
| CA-10 | En JDK 21, `assembleDebug`, `testDebugUnitTest`, `lintDebug` y `connectedDebugAndroidTest` terminan con los resultados documentados. Ejecutar API 23 y API 37 cuando estén disponibles; conservar las suites de regresión de SPEC-002 y SPEC-003. |

## Decisiones pendientes

Las verificaciones técnicas obligatorias figuran en [plan.md](plan.md) y [tasks.md): acceso individual, tope de 12 bytes descomprimidos por candidato al indexar y CRC de la página solicitada (T02), techo de memoria provisional medido (T03), SAF secuencial y cuota temporal (T02) y migración Room v1→v2 no destructiva (T04). La aprobación está condicionada a estas pruebas. El alcance funcional es una página, LTR inicial, RTL por libro, zoom hasta 3x y progreso básico. Lo pospuesto queda para SPEC-005.
