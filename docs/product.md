# Sumi — Producto

## Visión y usuarios

Sumi es una aplicación nativa Android para importar, organizar y leer cómics y mangas guardados en el dispositivo. Está dirigida a lectores que ya tienen archivos propios y quieren retomar su lectura sin conexión.

## Alcance del MVP

- Importar archivos CBZ locales mediante Storage Access Framework (SAF).
- Organizar los títulos importados en una biblioteca con portada y título.
- Abrir un CBZ y leer sus imágenes en orden.
- Navegar de izquierda a derecha o de derecha a izquierda y usar el desplazamiento vertical.
- Hacer zoom y ajustar su configuración. Elegir entre páginas simples y dobles.
- Guardar y recuperar el progreso por título. Conservar las preferencias básicas de lectura.

**Pending:** definir qué ajustes de zoom se ofrecen, cómo interactúan el desplazamiento vertical y las páginas dobles, y si las preferencias se aplican por título o de forma global. Las especificaciones del lector y de la biblioteca definirán estos comportamientos y sus criterios de aceptación.

## Exclusiones y roadmap

CBR, EPUB, PDF y catálogos online quedan para etapas posteriores. El enunciado inicial mencionaba CBR en la importación y la lectura del MVP. Para esta documentación se aplica la exclusión explícita y se mantiene el MVP en CBZ local, de acuerdo con [AGENTS.md](../AGENTS.md).

La configuración inicial se planifica en [SPEC-001](../specs/001-project-setup/spec.md). Cada funcionalidad del MVP tendrá su propia especificación.
