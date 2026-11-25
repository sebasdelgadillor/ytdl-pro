# Guía de mantenimiento — YTDL Pro (`@sebasdelgadillor` & `@Benzaiten7`)

Este proyecto es una versión independiente de YTDLnis desarrollada por **@sebasdelgadillor** y **@Benzaiten7**. La arquitectura desacopla las reglas web, la inspección JS del navegador y los canales de `yt-dlp` para que actualizar ante cambios de plataformas sea rápido y localizado.

- **Repositorio oficial del fork**: https://github.com/sebasdelgadillor/ytdl-pro

## 1. Actualizar reglas de plataformas, dominios, selectores y rate-limits

- **Archivo base versionado**: [`app/src/main/assets/web_rules.json`](app/src/main/assets/web_rules.json)
- **Registro en tiempo de ejecución**: [`app/src/main/java/com/deniscerri/ytdl/util/rules/WebRulesRegistry.kt`](app/src/main/java/com/deniscerri/ytdl/util/rules/WebRulesRegistry.kt)

Qué controla sin tocar código Kotlin:
- `quickPlatforms`: accesos rápidos en Navegador e Inicio (`showOnHome`).
- `domains`: alias (`youtu.be -> youtube.com`), cookies relacionadas (`relatedCookieDomains`), orígenes CDN (`cdnCookieOrigins`), nombres visibles y cookies de sesión (`authCookieExact`, `authCookieSubstrings`).
- `rateLimits`: dominios estrictos/estándar, intervalos min/max y marcadores de error HTTP 429 (`rateLimitErrorMarkers`).
- `mediaDetection`: selectores CSS de publicaciones (`postContainerSelectors`), filtros de imágenes/ruido, marcadores de permalink/video y atributos `data-*` / claves JSON.
- `fallbackScraper`: dominios de telemetría excluidos y claves JSON embebidas en HTML para `LocalFallbackEngine`.

### Actualización en caliente sin recompilar
En **Ajustes → Actualizaciones → Reglas web y extractores** puedes pegar e importar un JSON actualizado (se guarda en `files/rules/web_rules_override.json` y se fusiona sobre la configuración base) o restaurar las reglas integradas.

## 2. Actualizar la inspección DOM/medios del navegador

- **Inspector de medios y post único**: [`app/src/main/assets/browser_media_inspector.js`](app/src/main/assets/browser_media_inspector.js)
- **Inspector de iconos/sesión**: [`app/src/main/assets/browser_icon_inspector.js`](app/src/main/assets/browser_icon_inspector.js)
- **Cargador**: [`app/src/main/java/com/deniscerri/ytdl/util/BrowserScriptLoader.kt`](app/src/main/java/com/deniscerri/ytdl/util/BrowserScriptLoader.kt)

Todos los selectores y palabras clave de `browser_media_inspector.js` se inyectan dinámicamente desde `WebRulesRegistry.toBrowserJsConfigJson()`.

## 3. Actualizar `yt-dlp` y dependencias nativas

- La actualización de `yt-dlp` permanece desacoplada mediante [`UpdateUtil.updateYTDL`](app/src/main/java/com/deniscerri/ytdl/util/UpdateUtil.kt) y [`YTDLUpdater`](app/src/main/java/com/deniscerri/ytdl/core/YTDLUpdater.kt) usando los canales oficiales (`stable`, `nightly`, `master` o repositorio personalizado en Ajustes).
- Para habilitar OTA propia de la app en el futuro, define `UpdateUtil.APP_RELEASES_URL` apuntando a la API de Releases del fork (actualmente deshabilitado para no consultar el repo base).

## 4. Pruebas y compilación (JDK 21)

```powershell
$env:JAVA_HOME = "C:\Users\T\AppData\Local\Programs\TemurinJDK21\jdk-21.0.13.1+2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --no-daemon --console=plain
```

Notas técnicas importantes:
- Mantener `applicationId = "com.ytdlp.fork"`, `namespace = "com.deniscerri.ytdl"` y `DBManager` v30 para conservar sesiones y descargas existentes sin migraciones destructivas.
- En [`BrowserFragment`](app/src/main/java/com/deniscerri/ytdl/ui/browser/BrowserFragment.kt), conservar `AccessibilityUtil.isScreenReaderActive` para no instalar listeners táctiles sobre el `WebView` cuando TalkBack o Jieshuo estén activos.
