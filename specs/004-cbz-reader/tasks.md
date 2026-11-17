# SPEC-004 — Tareas propuestas

**Estado.** Todas las tareas están `Proposed`; no hay tareas autorizadas ni iniciadas. Depende de SPEC-002 y SPEC-003 `Validated`. Ver criterios en [spec.md](spec.md#criterios-de-aceptación-propuestos) y decisiones técnicas en [plan.md](plan.md).

| ID | Tarea | Dependencias | Criterios | Finalización verificable |
| --- | --- | --- | --- | --- |
| T01 | Definir modelos/contratos de sesión, página, dirección, progreso y errores. | Aprobación SPEC-004. | CA-01, CA-04 y CA-05. | Dominio Kotlin puro; pruebas JVM de secuencia natural, sentido LTR/RTL, posición confirmada y estados de error. |
| T02 | Implementar índice estructural y acceso individual a entradas CBZ mediante SAF. | T01. | CA-02, CA-06 y CA-07. | Pruebas seekable/secuencial, CRC/tamaño, archivo cambiado/corrupto, límites y cancelación. Evidencia de que abrir no expande páginas; ninguna modificación al contrato de SPEC-002. |
| T03 | Implementar decodificación de una página y zoom básico con memoria limitada. | T01–T02. | CA-03, CA-04. | Instrumentar JPG/PNG/WebP, muestreo, escala 3x, liberación y presión de RAM; medir objetivo 32 MiB/techo provisional 64 MiB en API 23/37 disponible. No precarga ni región. |
| T04 | Persistir página confirmada y dirección por libro mediante migración Room. | T01. | CA-05, CA-08. | Migración no destructiva v1→v2 conserva entradas/IDs/permisos; prueba de DB v1, escritura, restauración por reinicio y esquema reproducible. No modificar v1 en forma retroactiva. |
| T05 | Coordinar sesión, SAF, errores, cancelación y ciclo de vida desde ViewModel. | T02–T04. | CA-01, CA-06 a CA-08. | Pruebas de permiso revocado, ausencia, lectura bloqueada, cierre, resultado tardío, recreación, segundo plano, relanzamiento y limpieza de temporales. |
| T06 | Construir pantalla de una página con navegación horizontal y zoom accesibles. | T01, T03 y T05. | CA-04 y CA-09. | Pruebas Compose de estados, gesto LTR/RTL, botones anterior/siguiente, zoom 1x–3x, etiquetas/roles/targets 48 dp y dos tamaños de ventana. |
| T07 | Conectar biblioteca y lector en el flujo de navegación actual. | T05–T06. | CA-01 y CA-08. | Abrir por ID estable y volver; la biblioteca observa cambios; apertura de ID eliminado y errores no rompe la app. Sin nueva dependencia de navegación no aprobada. |
| T08 | Verificación integral y registro de evidencia. | T01–T07. | CA-01 a CA-10. | Ejecutar comandos Gradle obligatorios, suites regresivas de SPEC-002/003, API 23/37 si disponible, revisar lint, recursos, migración, memoria/temporales y ciclo de vida. Registrar conteos, dispositivo, límites y pendientes; no validar sin aprobación del usuario. |

No comenzar ninguna tarea hasta que SPEC-004 esté `Approved` y el usuario autorice la tarea concreta. La migración Room requiere conservar el esquema v1 y verificar la migración con datos existentes.
