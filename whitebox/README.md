# Vendored WhiteBox API

`src/main/java/jdk/test/whitebox/{WhiteBox,parser/DiagnosticCommand}.java` are copied unchanged from OpenJDK's `test/lib` at tag `jdk-25+26` (commit c59e44a7aa2aeff0823830b698d524523b996650). They are licensed under the GNU General Public License, version 2 only, as stated in each file's header; the full text is in [LICENSE](LICENSE) (OpenJDK's `LICENSE`).

HotSpot binds these native methods by name and signature when `-XX:+WhiteBoxAPI` is set and the class is loaded from the boot class path (`-Xbootclasspath/a:whitebox/target/wb.jar`). The rest of this repository only calls them from tests, at run time.
