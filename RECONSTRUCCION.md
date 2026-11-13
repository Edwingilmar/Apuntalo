# APÚNTALO · Reconstrucción limpia

Esta rama es independiente de main. No borrar main ni reemplazar el APK hasta que la compilación y pruebas sean satisfactorias.

## Contrato visual
- Conservar el aspecto del video aprobado: fondo grafito, Wave verde menta, tarjetas oscuras, navegación compacta.
- Usar el logo original aprobado en launcher, splash y encabezado. Archivos de referencia: alpha/approved_icon.jpg.base64 y alpha/approved_splash.jpg.base64.
- APÚNTALO no se traduce; textos con inicial mayúscula.
- Cinco pestañas en orden: Tareas, Agenda, Gastos, Mi carro, Config.

## Funciones exigidas
- Tareas y Compras: añadir, editar, marcar, borrar, compartir individualmente y lista completa.
- Recordatorio de tarea opcional, desactivado por defecto, con selectores nativos de fecha y hora solo cuando se activa.
- Agenda mensual, festivos PR/EEUU diferenciados, recordatorios y productividad.
- Gastos desde $0; ingreso con placeholder de ejemplo; ingresos y gastos recurrentes hacia meses futuros sin alterar meses pasados; presupuestos y avisos; donut con colores por categoría.
- Mi carro: ubicación GPS, mapa integrado, distancia en millas, ETA caminando basado en ruta y alertas de proximidad/llegada.
- Config: Idioma, Notificaciones, Apariencia; español, inglés, italiano y francés; 15 temas con Wave, wallpapers y regulador de intensidad; tema aleatorio.
- Widget de Tareas, Compras, Agenda y Gastos.
- Persistencia y permisos Android.

## Validación previa a entrega
1. Construir desde un proyecto Android limpio con Gradle, sin parches de texto frágiles al manifest.
2. Ejecutar compilación Android de debug en GitHub Actions; revisar errores de Kotlin, manifest y recursos.
3. Instalar y probar navegación, formularios, almacenamiento, temas, idioma, notificaciones, GPS y widgets.
4. Publicar APK solo tras confirmar la compilación y distinguir funciones probadas de pendientes.

Estado inicial: rama creada, reconstrucción y APK final pendientes.
