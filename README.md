# WebData Explorer

Aplicación Android nativa (Kotlin) con navegador integrado basado en Android WebView. Permite inspeccionar solicitudes accesibles de la navegación, explorar endpoints y recursos multimedia, revisar sesiones y exportar registros.

## Arquitectura actual

- **UI y navegador:** `app/src/main/java/com/webdata/explorer/MainActivity.kt`.
- **WebView:** navegación, JavaScript, DOM Storage y cookies habilitadas para compatibilidad con sitios legítimos.
- **Captura pasiva:** `WebViewClient.shouldInterceptRequest()` registra URL, método y encabezados disponibles. No se bloquea ni se reemplaza el tráfico normal.
- **fetch/XHR:** instrumentación JavaScript instalada mediante AndroidX WebKit al inicio del documento cuando está soportado; hay instalación posterior como alternativa. Los eventos se envían a la app mediante un puente limitado a registros de captura.
- **Detalles:** encabezados, cuerpos textuales accesibles, estado HTTP, tipo de contenido, duración y tamaños del contenido capturado. Se pueden copiar, buscar dentro de la sección, comparar respuestas y exportar.
- **API y multimedia:** agrupación heurística de endpoints observados, claves JSON, comparación de respuestas y clasificación de manifiestos HLS/DASH y segmentos.
- **Sesiones:** cada navegación principal guarda una sesión independiente en el almacenamiento privado de la aplicación. Se conservan hasta 20 sesiones recientes.
- **Exportación:** JSON y CSV; límites configurables para cuerpos de 4, 12 o 24 KB; filtro opcional de imágenes y segmentos.
- **Diagnóstico:** recuentos de solicitudes/respuestas, errores recientes y limitaciones conocidas.

## Prueba controlada integrada

1. Instalá la APK de depuración.
2. Abrí el navegador integrado.
3. Elegí **PRUEBA CAPTURA**.
4. La página de prueba hace un GET y un POST contra una ruta local interceptada por la propia app y recibe JSON conocido.
5. Revisá el resultado en la página de prueba y en el registro de solicitudes. El resultado debe mostrar si el GET, el POST, el cuerpo enviado y la respuesta JSON se capturaron desde el puente nativo.
6. Abrí **DIAGNÓSTICO** para revisar los contadores y limitaciones.

La prueba genera tráfico local controlado: valida la ruta de instrumentación WebView/JavaScript, pero no demuestra compatibilidad universal con sitios externos ni con reproductores de terceros.

## Limitaciones técnicas importantes

- `shouldInterceptRequest()` no expone directamente los cuerpos originales de todas las solicitudes o respuestas.
- `fetch`/XHR se inspeccionan solo si la instrumentación se ejecutó antes de la llamada y el contexto permite observarla.
- Android WebView puede restringir encabezados por CORS; algunas APIs nativas, service workers, iframes o reproductores de terceros pueden quedar fuera del alcance.
- La instrumentación de iframes depende de que AndroidX WebKit soporte scripts de inicio de documento para ese WebView. Si no, la alternativa posterior solo cubre el documento en el que se ejecuta.
- Los cuerpos binarios, imágenes, audio y video no se almacenan. Los manifiestos y segmentos se registran como solicitudes cuando son observables.
- Los cuerpos se truncan por límite de tamaño; los campos sensibles reconocidos se redactan antes de guardarlos, aunque la redacción heurística no puede garantizar la detección de todo secreto posible.
- La herramienta no elude autenticación, DRM, Cloudflare ni otras protecciones de acceso.
- Las pestañas actuales guardan URL y al volver a ellas recargan la página; no son WebViews aislados con estado JavaScript independiente.

## Construcción

El proyecto usa Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17 y AndroidX WebKit 1.12.1. La compilación de depuración se realiza mediante GitHub Actions.

## Pruebas y estado

Una compilación exitosa solo confirma que el proyecto compila. La prueba controlada debe ejecutarse en un dispositivo/emulador Android para confirmar la captura real. Las páginas externas deben probarse por separado porque su comportamiento depende del sitio, WebView, origen, CORS, autenticación y reproductor.
