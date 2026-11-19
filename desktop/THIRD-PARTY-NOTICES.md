# Desktop runtime notices

The desktop package includes software from these upstream projects. Keep their bundled legal files when distributing the installer.

- Tauri and its Rust dependencies: https://github.com/tauri-apps/tauri (MIT / Apache-2.0; dependency licenses vary; see Cargo.lock).
- Eclipse Temurin / OpenJDK: https://adoptium.net/ (GPLv2 with Classpath Exception). The packaged runtime retains its `legal` directory; the source and build information are available from Adoptium for the matching release.
- PostgreSQL and EDB-distributed binary dependencies: https://www.postgresql.org/about/licence/ and the selected binary archive's bundled notices in `pgsql/doc`, `pgsql/share` and `pgsql/lib`. The PostgreSQL license is permissive; additional library licenses vary.
- Microsoft Edge WebView2 Runtime: distributed through Tauri's offline installer mode under Microsoft's WebView2 terms. https://developer.microsoft.com/microsoft-edge/webview2/
- Microsoft Visual C++ Redistributable: the Microsoft-signed x64 redistributable is included as an unmodified prerequisite installer, subject to its accompanying license and redistribution terms. https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist
- Google OR-Tools: https://github.com/google/or-tools (Apache-2.0).
- Spring Boot, React, Ant Design, ECharts, OGL and other existing application dependencies retain their respective upstream licenses.

Before a public release, review the licenses of the exact runtime artifacts and dependencies recorded by the build, preserve their copyright and license notices, and provide the matching source or source offers where required. This file does not replace upstream license terms.
