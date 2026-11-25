<h1 align="center">
	<img src="logo_ytdl_pro.svg" width="128" alt="Logo de YTDL Pro" /> <br>
	YTDL Pro
</h1>

<h3 align="center">
	Fork independiente en Android de <a href="https://github.com/deniscerri/ytdlnis">YTDLnis</a> con navegador de sesiones persistentes, detección dinámica Video/Audio/Imagen, motor fallback 100% local, accesibilidad total TalkBack/Jieshuo y reglas web actualizables por JSON.
</h3>

<p align="center">
	Desarrollado por <a href="https://github.com/sebasdelgadillor"><strong>@sebasdelgadillor</strong></a> y <strong>@Benzaiten7</strong><br>
	Basado en el proyecto original de Denis Çerri · Licencia GNU GPL v3.0
</p>

<p align="center">
	<a href="https://github.com/sebasdelgadillor/ytdl-pro">Repositorio GitHub</a>
	&nbsp;·&nbsp;
	<a href="MAINTAINING.md">Guía de Mantenimiento</a>
	&nbsp;·&nbsp;
	<a href="LICENSE">Licencia GPL-3.0</a>
</p>

---

> [!NOTE]
> **Guía de contexto para retomar el desarrollo (en otro chat o con otro asistente):**
> Este `README.md` concentra identidad del proyecto, arquitectura exacta, invariantes críticos, comandos de compilación/despliegue/pruebas y estado verificado para continuar sin perder contexto. Complementar con [`MAINTAINING.md`](MAINTAINING.md).

---

## 1. Identidad del Proyecto y Versionado

| Propiedad | Valor | Notas clave |
| --- | --- | --- |
| **Nombre visible** | **YTDL Pro** | Definido en `app_name` y mostrado en la app |
| **Autores del fork** | **@sebasdelgadillor** y **@Benzaiten7** | Diálogo `ForkInfoDialog`, pantalla de bienvenida, docs y `Co-authored-by` |
| **Proyecto base (upstream)** | [deniscerri/ytdlnis](https://github.com/deniscerri/ytdlnis) | Atribución explícita en `ForkInfoDialog`, `WelcomeGuideFragment`, `README.md` |
| **`applicationId`** | `com.ytdlp.fork` | **No cambiar** (evita perder sesiones/datos del usuario en actualizaciones) |
| **Kotlin/Android `namespace`** | `com.deniscerri.ytdl` | Conservado para compatibilidad interna de paquetes y Room |
| **Base de datos Room** | `DBManager` **v30** | Esquema exportado en `app/schemas/com.deniscerri.ytdl.database.DBManager/30.json` |
| **Build Flavor principal** | `githubDebug` (`versionName = 1.9.1.1`) | Target `minSdk = 24`, `compileSdk = 36`, ABI `arm64-v8a` |
| **Licencia** | GNU General Public License v3.0 | Archivo [`LICENSE`](LICENSE) |

---

## 2. Resumen de Mejoras Respecto a YTDLnis Base

1. **Navegador integrado con sesiones persistentes (`BrowserFragment` + `SiteSessionManager`):**
   - Cookies persistentes en `CookieManager` sincronizadas con almacenamiento y accesos rápidos en una cuadrícula de plataformas activas en **Inicio** (`HomeFragment` + `SiteSessionGridAdapter`).
   - Inspector DOM/JS (`browser_media_inspector.js` y `browser_icon_inspector.js`) alimentado dinámicamente desde `web_rules.json` (`WebRulesRegistry.toBrowserJsConfigJson()`).
   - Ocultación/ajuste inteligente de la barra inferior para evitar solapamientos con barras web y mejora de reproducción embebida / pantalla completa (`MainActivity.enterWebFullscreen`).
   - Pulsación larga sobre publicaciones individuales en feeds (detectando permalink real del post, filtrando enlaces ruido como `/reels/audio/...`, `/liked_by/`, títulos genéricos *Video player* / *Instagram* o `aria-label` de avatares *Foto del perfil de…*).
   - Observador de duplicados (`alreadyExistsUiState` -> `DownloadsAlreadyExistDialog`) y soporte de SPA `history.pushState` (`doUpdateVisitedHistory`) desde el navegador.
   - **Selector múltiple con checkboxes para carruseles (`showAllDetectedMediaSheet`):** permite elegir diapositivas concretas (imágenes y videos), **Seleccionar todo / Limpiar**, formato de salida en lote (**JPG / PNG / WEBP**) y **Descargar seleccionados (N)** accesible para TalkBack/Jieshuo, manteniendo opciones individuales en botón lateral.
2. **Hoja de descarga unificada de 3 pestañas (`Video / Audio / Imagen`):**
   - `DownloadBottomSheetDialog` + `DownloadFragmentAdapter` con cápsulas limpias de calidad (`QualityCapsuleUtil`) para Video (MP4), Audio (MP3/M4A/Opus) e Imagen (JPG/PNG/WEBP).
   - `DownloadImageFragment` + `ImageDownloadHelper` con vista previa autenticada (cookies/User-Agent del navegador cuando el CDN lo exige).
3. **Detección dinámica de medios y carruseles en Compartir, Inicio y Navegador:**
   - Detecta dinámicamente si un enlace es imagen directa, carrusel fotográfico (`buildCarouselImageResultItems` + deduplicación de variantes `deduplicateCarouselImageUrls`), audio o video en `ShareActivity`, `HomeFragment`, `HomeAdapter`, `SelectPlaylistItemsDialog`, `ConfigureMultipleDownloadsAdapter`, `ResultRepository` y `DownloadViewModel`.
   - En imágenes directas o páginas fotográficas cambia automáticamente a la pestaña **Imagen** (y adapta la tarjeta en Inicio mostrando botón **Imagen** en lugar de **Audio/Video**). En carruseles/listas mixtas de imágenes y videos, `SelectPlaylistItemsDialog` y `DownloadMultipleBottomSheetDialog` respetan `resolveDownloadTypeForResult(...)` por ítem.
   - Cuando el tipo de medio es incierto o falla la extracción automática, **nunca cierra ni bloquea con un diálogo muerto**: mantiene/abre el menú completo de 3 pestañas (**Video / Audio / Imagen**) con el botón **Actualizar** y aviso `media_type_uncertain_choose_format`.
4. **Motor fallback 100% local e invisible (`LocalFallbackEngine`):**
   - Si `yt-dlp` falla o no extrae resultados, intenta en cadena: `NewPipeExtractor` → HTTP/HTML autenticado (`FallbackMediaRules`, incluyendo decodificación `efg` de Meta y scraping de `og:video`/`og:image`/JSON embebido) → WebView sniffer headless (`HeadlessWebViewSniffer`) con captura temprana tanto para video/HLS/DASH como para imágenes DOM.
   - Mapeo seguro en `YTDLPUtil` de IDs pasivos `fb_*` hacia selectores válidos de `yt-dlp` para evitar errores `-f fb_mp4_0`.
5. **Compatibilidad total con lectores de pantalla (TalkBack y Jieshuo):**
   - `AccessibilityUtil` y reglas estrictas en `BrowserFragment`, `PlaylistAdapter` y `SelectPlaylistItemsDialog` para que el foco táctil y de lectura entre al contenido web sin quedar atrapado en botones de la barra superior/inferior, además de `contentDescription` y `stateDescription` accesibles en tarjetas y controles.
6. **Resiliencia, mantenibilidad y actualización remota de reglas:**
   - Toda la lógica mutable de plataformas (dominios alias, cookies CDN, selectores CSS, marcadores de permalink/feed/ruido, palabras clave, cooldowns anti-rate-limit y claves del scraper HTML) vive en `app/src/main/assets/web_rules.json` con **actualización en 1 toque desde GitHub** (`WebRulesRegistry.fetchAndApplyRemoteRules` → `raw.githubusercontent.com/sebasdelgadillor/ytdl-pro/main/app/src/main/assets/web_rules.json`), importación JSON manual o restauración desde **Ajustes → Actualizaciones → Reglas web y extractores** sin recompilar la app.
   - `PlatformRateLimiter` (pausas automáticas por dominio), aislamiento de cookies antibot y `ProcessCacheGuardian` (limpieza segura de cachés huérfanas).

---

## 3. Mapa de Arquitectura y Archivos Clave

### Navegación Web, Reglas y Detección de Medios
- `app/src/main/assets/web_rules.json` — Configuración JSON versionada (`rulesVersion`) de plataformas rápidas, dominios, selectores, filtros de ruido/placeholders, scraper fallback y límites de frecuencia.
- `app/src/main/java/com/deniscerri/ytdl/util/rules/WebRulesRegistry.kt` — Carga, fusión (`mergeWithBase`), override en `files/rules/web_rules_override.json` y valores por defecto (`WebRulesConfig.defaults()`).
- `app/src/main/assets/browser_media_inspector.js` — Inspector DOM inyectado en el `WebView`: detecta video/imagen del post bajo toque o visible en pantalla, resuelve permalinks canónicos y filtra títulos/avatares genéricos.
- `app/src/main/assets/browser_icon_inspector.js` y `app/src/main/java/com/deniscerri/ytdl/util/BrowserScriptLoader.kt` — Cargador e inyección segura de scripts en el navegador.
- `app/src/main/java/com/deniscerri/ytdl/ui/browser/BrowserFragment.kt` y `app/src/main/java/com/deniscerri/ytdl/database/viewmodel/BrowserViewModel.kt` — Navegador integrado, barra contextual, menú de medios detectados, diálogo de duplicados y puente hacia `DownloadBottomSheetDialog`.
- `app/src/main/java/com/deniscerri/ytdl/util/BrowserMediaUtil.kt` — Utilidades puras Kotlin (compatibles con pruebas JVM sin Robolectric): clasificación de URLs de imagen/video/listado/ruido, canonicalización de permalinks, filtros de títulos genéricos y logos, inferencia de extensiones/títulos e identificadores `IMAGE_ONLY_FORMAT_ID`.

### Flujo Compartir, Inicio y Tarjeta de Descarga Unificada
- `app/src/main/java/com/deniscerri/ytdl/receiver/ShareActivity.kt` y `app/src/main/AndroidManifest.xml` — Recepción de enlaces (`ACTION_SEND`, `ACTION_VIEW` incluyendo `image/*`) y resolución inicial de tipo con `allow_dynamic_type_switch`.
- `app/src/main/java/com/deniscerri/ytdl/ui/home/HomeFragment.kt`, `HomeAdapter.kt`, `SiteSessionGridAdapter.kt` — Pantalla de Inicio, portapapeles, cuadrícula de sesiones activas, tarjetas adaptables a Video/Audio o Imagen y apertura del menú completo cuando el enlace falla o es incierto.
- `app/src/main/java/com/deniscerri/ytdl/ui/downloadcard/DownloadBottomSheetDialog.kt` y `DownloadFragmentAdapter.kt` — Hoja modal con cambio dinámico de pestaña (`Video` / `Audio` / `Imagen` / `Comando`), prevención de cierre ante error de tipo incierto (`Snackbar` + botón `Actualizar`) y reenvío de listas/carruseles.
- `app/src/main/java/com/deniscerri/ytdl/ui/downloadcard/DownloadVideoFragment.kt`, `DownloadAudioFragment.kt`, `DownloadImageFragment.kt`, `DownloadCommandFragment.kt` — Fragmentos por pestaña con protección contra títulos placeholder.
- `app/src/main/java/com/deniscerri/ytdl/database/repository/ResultRepository.kt`, `database/viewmodel/ResultViewModel.kt`, `database/viewmodel/DownloadViewModel.kt` — Orquestación de `yt-dlp`, atajo de imágenes estáticas, fallback automático y `resolveDownloadTypeForResult(...)`.

### Motor Fallback Local, Descarga de Imágenes y Trabajadores
- `app/src/main/java/com/deniscerri/ytdl/util/extractors/fallback/LocalFallbackEngine.kt` — Orquestador `NewPipe` → HTTP/HTML → `HeadlessWebViewSniffer` tanto para extracción previa (`extractResultItem`/`extractFormats`) como para descarga directa (`download`).
- `app/src/main/java/com/deniscerri/ytdl/util/extractors/fallback/FallbackMediaRules.kt` — Reglas puras de clasificación de streams, parsing Base64 `efg`, rechazo de segmentos (`byteend=`, `bytestart=`, `.m4s`, `.ts`), scraping HTML de video e `imageUrls` (JSON + OpenGraph/Twitter/`image_src`) y comandos FFmpeg.
- `app/src/main/java/com/deniscerri/ytdl/util/extractors/fallback/HeadlessWebViewSniffer.kt` — Captura headless de streams de red, etiqueta `<video>`, meta tags e `<img>` DOM filtrados (cierre temprano en páginas de solo imagen).
- `app/src/main/java/com/deniscerri/ytdl/util/ImageDownloadHelper.kt` — Vista previa autenticada (`fetchPreviewBitmap`), extracción multi-nivel de candidatos desde HTML/WebView y conversión a JPG/PNG/WEBP (`DownloadWorker.kt` rama `DownloadType.image`).

### Sesiones, Accesibilidad, Protección y Pruebas
- `app/src/main/java/com/deniscerri/ytdl/util/SiteSessionManager.kt`, `SiteSessionDao.kt`, `SiteSession.kt`, `SiteSessionViewModel.kt` — Gestión persistente de sesiones por dominio normalizado (`DomainUtil.kt`).
- `app/src/main/java/com/deniscerri/ytdl/util/AccessibilityUtil.kt` — Helpers para TalkBack y Jieshuo+.
- `app/src/main/java/com/deniscerri/ytdl/util/PlatformRateLimiter.kt` y `ProcessCacheGuardian.kt` — Cooldowns por plataforma y limpieza de cachés huérfanas.
- `app/src/main/java/com/deniscerri/ytdl/ui/more/ForkInfoDialog.kt` — Diálogo informativo "Acerca de este proyecto".
- `app/src/main/java/com/deniscerri/ytdl/ui/more/settings/updating/UpdateSettingsModule.kt` — Gestión de reglas web en Ajustes (**Actualizar desde GitHub**, importar JSON, copiar, restaurar integradas).
- **Tests JVM (`app/src/test/java/com/deniscerri/ytdl/`):**
  - `BrowserMediaUtilTest.kt` (15), `FallbackMediaRulesTest.kt` (7), `WebRulesRegistryTest.kt` (4), `QualityCapsuleUtilTest.kt` (5), `DomainUtilTest.kt` (5), `PlatformRateLimiterTest.kt` (3), `MetadataTagTest.kt` (14), `ExampleUnitTest.kt` (1).

---

## 4. Invariantes Críticos y Reglas de Diseño (No Romper)

1. **Accesibilidad TalkBack / Jieshuo intacta:**
   - En `BrowserFragment`, nunca adjuntar listeners táctiles bloqueantes sobre `WebView` cuando `AccessibilityUtil.isScreenReaderActive(...)` sea `true`.
   - En pruebas por ADB sobre dispositivos de usuario, **nunca** modificar `touch_exploration_enabled` ni `enabled_accessibility_services`.
2. **Compatibilidad `minSdk = 24`:**
   - En código de `app/src/main/`, **no** usar APIs añadidas en Android API 26+ (por ejemplo, evitar `java.util.Base64` —decodificar Base64 en Kotlin puro o usar `android.util.Base64` en capas Android— y usar `URLDecoder.decode(raw, StandardCharsets.UTF_8.name())` en vez de pasar `Charset`).
3. **Sincronía de reglas por defecto:**
   - Todo campo o marcador añadido en [`app/src/main/assets/web_rules.json`](app/src/main/assets/web_rules.json) debe reflejarse en `WebRulesConfig.defaults()` y `mergeWithBase` dentro de [`WebRulesRegistry.kt`](app/src/main/java/com/deniscerri/ytdl/util/rules/WebRulesRegistry.kt).
4. **Soluciones generalizables, sin parches por sitio:**
   - Si falla una red social o web nueva, ajustar reglas declarativas (`web_rules.json`), heurísticas genéricas (`BrowserMediaUtil`, `FallbackMediaRules`, `browser_media_inspector.js`) o la máquina de estados común en `DownloadBottomSheetDialog` / `ShareActivity` / `HomeFragment`.
   - Si no hay certeza del medio, conservar siempre disponibles las 3 pestañas (**Video / Audio / Imagen**).
5. **Preservar compatibilidad de datos del usuario:**
   - Mantener `applicationId = "com.ytdlp.fork"` y `DBManager` en versión `30` (o agregar migración segura explícita si alguna vez se cambia el esquema Room).
6. **Notas prácticas de entorno Windows / PowerShell:**
   - Al inspeccionar UI en Android con `uiautomator dump`, usar **`adb pull /sdcard/window_dump.xml <ruta>`** en lugar de `adb shell cat ... > archivo.xml` (PowerShell `>` escribe UTF-16LE que rompe parsers XML UTF-8).
   - Para ejecutar scripts Python inline en PowerShell, usar **`@' ... '@ | python -`** para evitar problemas de comillas.

---

## 5. Flujo de Compilación, Pruebas, Despliegue y GitHub

### Requisitos y Compilación (JDK 21)
```powershell
$env:JAVA_HOME = "C:\Users\T\AppData\Local\Programs\TemurinJDK21\jdk-21.0.13.1+2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --no-daemon --console=plain
```

### Rutas de APK generadas y entrega habitual
- **Build output:** `app\build\outputs\apk\github\debug\YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk`
- **Copia en Escritorio:** `C:\Users\T\Desktop\YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk`
- **Despliegue ADB (ej. Galaxy S23 `RFCW30GSYMP`):**
```powershell
$adb = "C:\Users\T\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apk = "app\build\outputs\apk\github\debug\YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk"
Copy-Item $apk "C:\Users\T\Desktop\YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk" -Force
& $adb -s RFCW30GSYMP push $apk "/sdcard/Download/YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk"
& $adb -s RFCW30GSYMP install -r $apk
```

### Remotos Git y Convención de Commits
- **`origin` (respaldo público del fork):** `https://github.com/sebasdelgadillor/ytdl-pro.git` (ramas `main` y `fork/main`).
- **`upstream` (proyecto original):** `https://github.com/deniscerri/ytdlnis.git` (rama `main` original; **no hacer push** a `upstream`).
- Incluir coautoría en commits relevantes:
```text
Co-authored-by: Benzaiten7 <Benzaiten7@users.noreply.github.com>
```

---

## 6. Estado Verificado Actual (2026-11-25)

- **Pruebas unitarias JVM (`:app:testGithubDebugUnitTest`):** **54/54 aprobadas** (0 fallos, 0 errores, 0 omitidas) tras la última edición de código/tests.
- **APK debug (`arm64-v8a`):** `97,150,875` bytes, instalado en Galaxy S23 (`RFCW30GSYMP`) y copiado tanto en Desktop como en `/sdcard/Download/YTDLFork-1.9.1.1-arm64-v8a-debug-github.apk`.
- **Pruebas en vivo verificadas en Galaxy S23 (`RFCW30GSYMP`):**
  - **Actualización remota de `web_rules.json` en Ajustes:** probado **Actualizar desde GitHub** desde `Reglas web y extractores`; descargó `web_rules_override.json` (`11,631` bytes) desde `sebasdelgadillor/ytdl-pro` y cambió el origen a `JSON personalizado`.
  - **Galería multimedios con checkboxes en Navegador:** sobre página de Instagram con `12` medios detectados, verificados checkboxes por fila, **Seleccionar todo**, chips **JPG / PNG / WEBP**, opciones individuales por ítem y **Descargar seleccionados (2)**; descargó en lote ambas imágenes seleccionadas en `.png` (`2,526,394` bytes y `144,275` bytes).
  - **`SelectPlaylistItemsDialog` y `ShareActivity`:** probados carrusel de Instagram (`/p/.../`), URL de imagen directa (`600.jpg`, `107,556` bytes), URL incierta (`example.com` manteniendo las 3 pestañas) e Inicio con enlace `.webp` adaptando botón **Imagen**.
  - **Estabilidad y accesibilidad:** `NO_CRASHES` en `logcat` y `touch_exploration_enabled = 0` preservado sin alteraciones.
