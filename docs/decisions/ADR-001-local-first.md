# ADR-001 — Priorizar un MVP local

**Estado: Proposed.** Pendiente de ratificación formal.

## Contexto

Sumi está orientada a lectores que ya tienen cómics y mangas guardados en su dispositivo. La primera entrega debe cubrir el recorrido desde la importación hasta retomar la lectura.

## Decisión

Construir primero un MVP local que lea CBZ y guarde la biblioteca, el progreso y las preferencias en el dispositivo. El flujo principal funcionará sin conexión y sin cuenta.

## Consecuencias

- Las primeras especificaciones pueden centrarse en la biblioteca y el lector. El alcance por formato se mantiene en [Producto](../product.md).
- La app debe contemplar archivos movidos, eliminados o inaccesibles. La estrategia de acceso y recuperación se decidirá en la especificación de importación.
- La persistencia local debe permitir retomar la lectura entre sesiones. La identidad de cada título y la política de duplicados se definirán en la especificación de biblioteca.
