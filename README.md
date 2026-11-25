<h1 align="center">
	<img src="logo_ytdl_pro.svg" width="128" alt="YTDL Pro logo" /> <br>
	YTDL Pro
</h1>

<h3 align="center">
	Independent Android fork of <a href="https://github.com/deniscerri/ytdlnis">YTDLnis</a> powered by <code>yt-dlp</code>, built-in persistent browser, unified Video/Audio/Image downloader, and local fallback engine.
</h3>

<p align="center">
	Developed by <a href="https://github.com/sebasdelgadillor"><strong>@sebasdelgadillor</strong></a> &amp; <strong>@Benzaiten7</strong><br>
	Upstream project by Denis Çerri · Licensed under GNU GPL v3.0
</p>

<p align="center">
	<a href="https://github.com/sebasdelgadillor/ytdl-pro">Repository</a>
	&nbsp;·&nbsp;
	<a href="MAINTAINING.md">Maintaining Guide</a>
	&nbsp;·&nbsp;
	<a href="LICENSE">GPL-3.0 License</a>
</p>

---

## ✨ Key Changes & Features

- **Unified 3-Tab Download Sheet (`Video / Audio / Image`):** Simplified resolution and quality capsules for MP4 Video, MP3/M4A Audio, and JPG/PNG/WEBP Images with authenticated image preview.
- **Dynamic Media Type Routing:** Automatically routes video, audio, or image links across the built-in browser, Share menu (`ShareActivity`), and Home screen—keeping all 3 tabs accessible whenever media type is uncertain.
- **Persistent Session Browser:** Embedded WebView browser with cookie persistence (`SiteSessionManager`), quick platform shortcuts on Home, smart single-post contextual detection, and free format selection.
- **100% Local Fallback Engine:** Uses `NewPipeExtractor`, authenticated HTTP/HTML scraping, and a headless WebView sniffer (`LocalFallbackEngine`) when `yt-dlp` fails.
- **Screen Reader Accessibility:** Built-in compatibility for **TalkBack** and **Jieshuo** (`AccessibilityUtil`) across browser navigation and download sheets.
- **Modular Web Rules:** Decoupled JSON rules (`app/src/main/assets/web_rules.json` + hot-reload override) and independent `yt-dlp` channel updates without recompiling. See [MAINTAINING.md](MAINTAINING.md).

## 🛠️ Build & Test (JDK 21)

Application ID: `com.ytdlp.fork` (`namespace` remains `com.deniscerri.ytdl` for existing compatibility).

```powershell
$env:JAVA_HOME = "C:\Path\To\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --no-daemon --console=plain
```

## 📄 License & Attribution

- Distributed under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
- This independent edition is developed and maintained by **@sebasdelgadillor** and **@Benzaiten7**, based on the open-source [YTDLnis](https://github.com/deniscerri/ytdlnis) codebase by Denis Çerri.
- Thanks to [yt-dlp](https://github.com/yt-dlp/yt-dlp), [youtubedl-android](https://github.com/yausername/youtubedl-android), and [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).
