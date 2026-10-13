# PkgBrowser v1.4

Gestor de descargas estilo IDM con navegador dentro, adaptado a PS4 con jailbreak (GoldHEN).

- **Navegador**: WebView a pantalla completa con barra de direcciones. Al tocar un
  enlace de descarga, el `IDMStyleDownloadListener` hereda la sesión del navegador
  (cookies + referer + user-agent), sondea el enlace (nombre real vía
  `Content-Disposition`, tamaño vía `Content-Length`/`Content-Range`) y pide
  confirmación.
- **🔍 Resolver enlace directo (v1.2)**: botón `🔍` en el navegador — pegas la URL
  de la página y la app obtiene sola el enlace directo, sin per-site code: un
  WebView oculto (1×1) comparte la sesión (cookies/referer/UA, así los tokens
  firmados no dan 403), olfatea peticiones a archivos (.pkg, .zip, …), cosecha
  enlaces del DOM con JS inyectado (reintenta a los 6 y 12 s) y, si la URL no
  trae extensión, sondea por mimetype (`octet-stream`/`attachment`). Si hay
  varios candidatos, eliges cuál. El flujo sonda → confirmación → encolar es el
  mismo de siempre (extraído a `DownloadStarter`). Limitación: `blob:` no se
  resuelve (vive en memoria del WebView).
- **Motor**: descarga multi-hilo x4 con `Range: bytes=`, progreso por hilo en
  SQLite (reanuda entre reinicios), foreground service con notificación y wake lock.
  Sin dependencias externas: solo `HttpURLConnection` + `RandomAccessFile`.
- **Enviar a PS4**: cada descarga completa tiene `⬆ Enviar a PS4`. La app pide al
  foreground service que levante el **puente persistente** (mini servidor HTTP en
  la IP LAN del teléfono, `:8080/pkg`, con soporte de `Range`) y manda la orden
  al Remote Package Installer de GoldHEN
  (`POST http://<ip-ps4>:12800/api/install`, socket crudo estilo `curl --http0.9`).
  El puente queda ACTIVO con notificación (`🌉 Puente activo` + botón ⏹ Detener)
  durante TODA la descarga de la PS4 — v1.0 lo detenía a los segundos y la PS4
  fallaba a mitad del PKG. La IP de la PS4 se edita y se recuerda en la pantalla
  de Descargas.
- **NovapsRPI :12801 + enlace directo (v1.4)**: la pantalla de Descargas trae
  dos selectores (guardados en `SharedPreferences`): el **instalador**
  (`NovapsRPI :12801` por defecto / `RPI Flatz :12800`) y el **modo de envío**.
  En modo **🔗 Enlace directo**, `⬆ Enviar a PS4` manda el enlace de descarga
  original tal cual con `POST http://<ip-ps4>:<puerto>/api/install`
  (`{"type":"direct","packages":["<url>"]}` — mismo protocolo que flatz, solo
  cambia el puerto): la PS4 descarga el PKG de internet por BGFT e instala en
  segundo plano, sin que el teléfono descargue nada. En modo
  **🌉 Puente :8080** el flujo clásico sigue igual (el teléfono sirve el
  archivo), pero la orden va al puerto del instalador elegido.
- **⬆ Enviar a PS4 en el diálogo de descarga (v1.4.1)**: el diálogo "Descargar
  archivo" del navegador trae un tercer botón `⬆ Enviar a PS4` que manda el
  enlace directo al NovapsRPI (`:12801` por defecto) sin bajar nada al
  teléfono; si el enlace exige sesión, el error sugiere bajarlo con ACEPTAR y
  usar el modo 🌉 Puente.

## Compilar (GitHub Actions)

1. Sube este proyecto a un repo de GitHub.
2. Actions → "Compilar PkgBrowser" (corre solo en push a main/master o manual).
3. El APK queda en Artifacts → `PkgBrowser-debug` (30 días):
   `app/build/outputs/apk/debug/app-debug.apk`.

Package: `com.panda.pkgbrowser` · versionCode 5 · versionName "1.4" ·
AGP 8.13.0 · compileSdk/targetSdk 34 · minSdk 24 · Kotlin 2.0.21.
